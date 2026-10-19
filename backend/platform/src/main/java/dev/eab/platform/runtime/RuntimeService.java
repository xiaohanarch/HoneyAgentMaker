package dev.eab.platform.runtime;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.identity.AgentScope;
import dev.eab.platform.identity.DelegatedClient;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.policy.PolicyController;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.platform.registry.OverlayResolver;
import dev.eab.platform.registry.ScopedResolver;
import dev.eab.platform.runtime.RuntimeStores.AlertStore;
import dev.eab.platform.runtime.RuntimeStores.ImChannel;
import dev.eab.platform.runtime.RuntimeStores.JdbcTraceStore;
import dev.eab.platform.runtime.RuntimeStores.MemoryStore;
import dev.eab.platform.runtime.RuntimeStores.RunRecord;
import dev.eab.platform.runtime.RuntimeStores.RunStore;
import dev.eab.policy.PolicyEngine;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.runtime.AgentRuntime;
import dev.eab.runtime.Exec;
import dev.eab.runtime.Journal;
import dev.eab.runtime.Ports;
import dev.eab.runtime.RunContext;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RuntimeDeps;
import dev.eab.runtime.ToolGateway;
import dev.eab.runtime.model.ModelGateway;
import dev.eab.runtime.semantic.SemanticService;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.BuiltinTools;
import dev.eab.spec.Json;
import dev.eab.spec.Layers;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.tracing.Otlp;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.Tracer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台里的运行服务：把运行时的端口接到平台的服务上（组装走注册中心，BO API 走令牌交换，裁决写审计），
 * 保存运行状态与追踪，处理挂起后的确认。HTTP 接口和命令行都通过它发起运行。
 */
public final class RuntimeService {

    private static final Logger LOG = LoggerFactory.getLogger(RuntimeService.class);

    /**
     * @param erpUrl DemoERP 的地址（连接别名 DEMO_ERP 在本环境的绑定），为空时 BO 工具一律拒绝
     * @param otlpEndpoint 可选的 OTLP/HTTP 采集端，运行结束时导出追踪
     * @param connections 连接别名在本环境的绑定（外部 MCP Server、http 工具来源），只在部署配置里出现，不进资产
     */
    public record Settings(
            String erpUrl, ModelGateway gateway, String otlpEndpoint, Map<String, String> connections, int maxTurns) {}

    private final PlatformServices services;
    private final Settings settings;
    private final JdbcTraceStore traces;
    private final RunStore runs;
    private final MemoryStore memory;
    private final AlertStore alerts;
    private final ImChannel im;
    private final McpConnections mcp;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public RuntimeService(PlatformServices services, Settings settings) {
        this.services = services;
        this.settings = settings;
        var jdbc = services.db().jdbc();
        this.traces = new JdbcTraceStore(jdbc);
        this.runs = new RunStore(jdbc, services.clock());
        this.memory = new MemoryStore(jdbc, services.clock());
        this.alerts = new AlertStore(jdbc, services.clock());
        this.im = new ImChannel(jdbc, services.clock());
        this.mcp = new McpConnections(settings.connections());
    }

    public JdbcTraceStore traces() {
        return traces;
    }

    public RunStore runs() {
        return runs;
    }

    public MemoryStore memory() {
        return memory;
    }

    public AlertStore alerts() {
        return alerts;
    }

    public ImChannel im() {
        return im;
    }

    /** A2A 出站网关，由 A2aConfig 装配；没有装配时调用远程 Agent 明确失败 */
    private volatile Ports.RemoteAgents remoteAgents =
            (context, remoteRef, message) -> Ports.RemoteResult.failure("REMOTE_AGENT_DISABLED", "平台没有装配 A2A 网关");

    public void remoteAgents(Ports.RemoteAgents gateway) {
        this.remoteAgents = gateway;
    }

    public Ports.RemoteAgents remoteAgents() {
        return remoteAgents;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * 发起一次运行。
     *
     * @param userToken 代用户操作时的用户令牌，BO API 调用以它做令牌交换；受托运行时为 null
     * @param sessionId 会话编号，为空时新建
     */
    public record StartRequest(
            DemoUser user,
            String userToken,
            String agent,
            String message,
            String sessionId,
            String delegationId,
            Map<String, Object> routing,
            TestRun test) {

        public StartRequest(
                DemoUser user, String userToken, String agent, String message, String sessionId, String delegationId) {
            this(user, userToken, agent, message, sessionId, delegationId, null, null);
        }

        public StartRequest(
                DemoUser user,
                String userToken,
                String agent,
                String message,
                String sessionId,
                String delegationId,
                Map<String, Object> routing) {
            this(user, userToken, agent, message, sessionId, delegationId, routing, null);
        }
    }

    /**
     * Studio 的测试运行（私有环境）。
     *
     * @param startedBy 发起测试的开发者；测试运行以所选测试用户的身份执行，发起人也能查看它的追踪
     * @param overlayYaml 叠加的工作区草稿，恢复挂起的运行时据此重建
     */
    public record TestRun(String startedBy, List<String> overlayYaml) {}

    /** 运行的标识与结果 */
    public record RunOutcome(String runId, String traceId, String sessionId, AgentRuntime.RunResult result) {}

    public RunOutcome start(StartRequest request, RunEvents.Sink sink) {
        String runId = "run-" + UUID.randomUUID();
        String traceId = Tracer.newTraceId();
        String sessionId = request.sessionId() == null || request.sessionId().isBlank()
                ? "ses-" + UUID.randomUUID()
                : request.sessionId();
        String mode = request.delegationId() == null ? "on-behalf" : "delegated";
        String now = IsoTime.now(services.clock());
        Journal journal = new Journal();
        runs.insert(new RunRecord(
                runId,
                traceId,
                sessionId,
                request.user().tenantId(),
                request.user().id(),
                request.agent(),
                mode,
                request.delegationId(),
                request.message(),
                "running",
                Journal.toJson(journal.state()),
                null,
                now,
                now));
        if (request.test() != null)
            runs.markTest(runId, request.test().startedBy(), request.test().overlayYaml());
        return execute(runs.get(runId).orElseThrow(), request.userToken(), journal, sink, request.routing());
    }

    public static final class RunNotFoundException extends RuntimeException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * 用户对挂起运行的决定：记录到运行日志后从头重放，运行在确认点按决定继续。
     *
     * @param userToken 恢复时重新做令牌交换用的用户令牌（原令牌可能已经过期）
     */
    public RunOutcome confirm(String runId, DemoUser user, String userToken, boolean approved, RunEvents.Sink sink) {
        synchronized (locks.computeIfAbsent(runId, k -> new Object())) {
            RunRecord run = runs.get(runId).orElseThrow(() -> new RunNotFoundException("运行 " + runId + " 不存在"));
            if (!run.userId().equals(user.id())) throw new RunNotFoundException("运行 " + runId + " 不存在");
            if (!run.status().equals("suspended")) {
                throw new IllegalStateException("运行 " + runId + " 当前状态为 " + run.status() + "，不在等待确认");
            }
            Journal journal = new Journal(Journal.fromJson(run.journal()));
            journal.decide(approved);
            runs.update(runId, "running", Journal.toJson(journal.state()), null);
            return execute(run, userToken, journal, sink, null);
        }
    }

    private RunOutcome execute(
            RunRecord run, String userToken, Journal journal, RunEvents.Sink sink, Map<String, Object> routing) {
        DemoUser user = DemoDirectory.findUser(run.userId()).orElseThrow();
        RuntimeDeps deps = depsFor(run.tenantId(), runs.overlay(run.id()));
        AgentRuntime runtime = new AgentRuntime(deps);
        Tracer tracer = new Tracer(run.traceId(), traces, run.tenantId(), services.clock());
        var request = new AgentRuntime.RunRequest(
                run.id(),
                run.sessionId(),
                runUser(user),
                run.mode(),
                run.delegationId(),
                new RunContext.Subject(run.delegationId() == null ? userToken : null, run.delegationId(), null),
                run.agent(),
                run.utterance(),
                routing);
        AgentRuntime.RunResult result = runtime.run(request, journal, tracer, sink);
        runs.update(
                run.id(),
                result.status(),
                Journal.toJson(journal.state()),
                result.status().equals("suspended") ? null : Json.MAPPER.writeValueAsString(result));
        if (!result.status().equals("suspended")) exportOtlp(run.traceId());
        return new RunOutcome(run.id(), run.traceId(), run.sessionId(), result);
    }

    public static RunContext.User runUser(DemoUser user) {
        String tenantName = DemoDirectory.findTenant(user.tenantId())
                .map(DemoDirectory.Tenant::name)
                .orElse(user.tenantId());
        return new RunContext.User(
                user.id(),
                user.name(),
                user.tenantId(),
                tenantName,
                user.roles(),
                DemoDirectory.permittedOperations(user),
                user.dataScopeLabel());
    }

    /** 每次运行按租户取解析器：只读得到该租户可见的资产，读出的标品资产都验过签名 */
    RuntimeDeps depsFor(String tenantId) {
        return depsFor(tenantId, List.of());
    }

    /** overlay 是私有环境里叠加的工作区草稿（测试运行、评测），生产运行时为空 */
    RuntimeDeps depsFor(String tenantId, List<Asset> overlay) {
        ScopedResolver scoped = services.registry().resolverFor(Viewer.tenant(tenantId));
        AssetResolver resolver = overlay.isEmpty() ? scoped : new OverlayResolver(scoped, overlay);
        return new RuntimeDeps(
                settings.gateway(),
                resolver,
                AssetResolver.PUBLISHED,
                (agent, tenant) -> assemble(agent, tenant, overlay),
                (definition, tenant) -> agentToken(definition, tenant, resolver),
                this::callBo,
                policy(),
                mcp,
                this::callHttp,
                im,
                new KnowledgeDocuments(resolver),
                memory,
                alerts,
                this::metaObject,
                services.clock(),
                settings.maxTurns(),
                remoteAgents);
    }

    public EffectiveDefinition assemble(String agent, String tenantId) {
        return assemble(agent, tenantId, List.of());
    }

    public EffectiveDefinition assemble(String agent, String tenantId, List<Asset> overlay) {
        var outcome = EffectiveAssembly.assemble(
                services.registry(),
                Viewer.tenant(tenantId),
                EffectiveAssembly.Request.withOverlay(agent, tenantId, overlay));
        if (outcome instanceof EffectiveAssembly.Outcome.Ok ok) return ok.definition();
        String issues = String.join(
                "；",
                outcome.issues().stream().map(i -> i.code() + " " + i.message()).toList());
        throw new IllegalStateException("组装 " + agent + " 失败：" + issues);
    }

    /** Agent 令牌：scope 是 Agent 声明的 BO 操作（含技能、钩子工作流里用到的） */
    private String agentToken(EffectiveDefinition definition, String tenantId, AssetResolver resolver) {
        String ref = definition.provenance().agent();
        int at = ref.indexOf('@');
        return services.identity()
                .issueAgentToken(
                        ref.substring(0, at),
                        ref.substring(at + 1),
                        tenantId,
                        AgentScope.declaredBoOperations(definition, resolver));
    }

    private Ports.BoResponse callBo(Ports.BoCall call) {
        var client = new DelegatedClient(services.identity(), settings.erpUrl(), null);
        var subject = call.subject().delegationId() != null
                ? DelegatedClient.Subject.delegation(call.subject().delegationId())
                : call.subject().restrictedToken() != null
                        ? DelegatedClient.Subject.restricted(call.subject().restrictedToken())
                        : DelegatedClient.Subject.user(call.subject().userToken());
        var response = client.callBoApi(new DelegatedClient.BoCall(
                subject,
                call.agentToken(),
                call.operation(),
                call.method(),
                call.path(),
                call.query(),
                call.body(),
                call.idempotencyKey()));
        return new Ports.BoResponse(response.status(), Json.MAPPER.convertValue(response.body(), Object.class));
    }

    /** 鉴权点 2、3：策略引擎裁决，裁决写入审计 */
    private Ports.Policy policy() {
        return new Ports.Policy() {
            @Override
            public Decision evaluate(String checkpoint, Map<String, Object> facts, RunContext context, String target) {
                Decision decision = PolicyEngine.evaluate(
                        services.policyRules(),
                        checkpoint,
                        facts,
                        services.clock().instant());
                PolicyController.recordDecision(
                        services.audit(),
                        decision,
                        false,
                        context.user().id(),
                        context.tenantId(),
                        context.user().id(),
                        target,
                        agentLabel(context),
                        checkpoint.equals("tool") ? target : null,
                        context.mode());
                return decision;
            }

            @Override
            public boolean l3Enabled(String tenantId) {
                return services.tenantSettings().get(tenantId).l3Enabled();
            }

            @Override
            public List<String> delegationScope(String delegationId) {
                return services.delegations()
                        .get(delegationId)
                        .map(d -> d.scope())
                        .orElse(List.of());
            }
        };
    }

    private static String agentLabel(RunContext context) {
        return context.definition() == null
                ? context.agent()
                : context.definition().provenance().agent();
    }

    /** http 类工具来源：IM_WEBHOOK 连到模拟 IM 渠道，其他别名按部署配置的地址调用 */
    private Object callHttp(
            RunContext context, String connection, String method, String path, Map<String, Object> body) {
        if (ImChannel.CONNECTION.equals(connection)) {
            im.post(
                    context.tenantId(),
                    "im",
                    stringOrNull(body.get("to")),
                    String.valueOf(body.getOrDefault("message", "")),
                    context.runId());
            return Map.of("sent", true, "channel", "im");
        }
        String base = settings.connections().get(connection);
        if (base == null) throw new IllegalStateException("连接 " + connection + " 没有在本环境绑定");
        try {
            var request = HttpRequest.newBuilder(URI.create(base).resolve(path))
                    .timeout(Duration.ofSeconds(10))
                    .header("content-type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return Map.of("status", response.statusCode(), "body", response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("调用连接 " + connection + " 失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用连接 " + connection + " 被中断");
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 元数据服务（DemoERP 的 /meta/v1）：语义包投影段的定义实时从这里取 */
    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> metaObject(String ref) {
        if (settings.erpUrl() == null || settings.erpUrl().isBlank()) return Optional.empty();
        try {
            var request = HttpRequest.newBuilder(URI.create(settings.erpUrl())
                            .resolve("/meta/v1/objects/" + URLEncoder.encode(ref, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) return Optional.empty();
            return Optional.of(Json.MAPPER.readValue(response.body(), Map.class));
        } catch (java.io.IOException e) {
            LOG.warn("读取元数据 {} 失败：{}", ref, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** 统一入口路由器的身份：导航、澄清、能力缺口这类不交给 Agent 的结果，记在它名下 */
    public static final String ROUTER_AGENT = "platform/router";

    /**
     * 统一入口没有交给 Agent 的一次提问（导航、澄清、能力缺口）：同样记一次运行和一条追踪，路由说明与“本次链路”面板照常可看。
     *
     * @param kind navigation、clarify、gap，同时作为推送事件的类型
     */
    public RunOutcome recordRouterRun(
            DemoUser user,
            String sessionId,
            String message,
            Map<String, Object> routing,
            String kind,
            Map<String, Object> payload,
            RunEvents.Sink sink) {
        String runId = "run-" + UUID.randomUUID();
        String traceId = Tracer.newTraceId();
        String session = sessionId == null || sessionId.isBlank() ? "ses-" + UUID.randomUUID() : sessionId;
        String now = IsoTime.now(services.clock());
        Tracer tracer = new Tracer(traceId, traces, user.tenantId(), services.clock());
        var root = tracer.start("统一入口 · " + kind, SpanRecord.SpanKind.run, null, "run", Map.of());
        root.set("eab.run.id", runId)
                .set("eab.agent", ROUTER_AGENT)
                .set("eab.user.id", user.id())
                .set("eab.tenant.id", user.tenantId());
        sink.accept(RunEvents.RunEvent.of(
                "run_started", ROUTER_AGENT, "runId", runId, "traceId", traceId, "agent", "统一入口"));
        AgentRuntime.recordRouting(root, routing, sink, ROUTER_AGENT);
        root.set("eab.router.outcome", kind).end();
        Map<String, Object> event = new java.util.LinkedHashMap<>(payload);
        event.put("traceId", traceId);
        sink.accept(new RunEvents.RunEvent(kind, ROUTER_AGENT, event));
        String text = String.valueOf(payload.getOrDefault("message", ""));
        sink.accept(RunEvents.RunEvent.of("final", ROUTER_AGENT, "text", text, "traceId", traceId, "kind", kind));
        Journal journal = new Journal();
        var result = new AgentRuntime.RunResult("completed", text, payload, null, null, List.of(), 0);
        runs.insert(new RunRecord(
                runId,
                traceId,
                session,
                user.tenantId(),
                user.id(),
                ROUTER_AGENT,
                "on-behalf",
                null,
                message,
                "completed",
                Journal.toJson(journal.state()),
                Json.MAPPER.writeValueAsString(result),
                now,
                now));
        memory.appendSession(session, user.tenantId(), user.id(), ROUTER_AGENT, message, text);
        return new RunOutcome(runId, traceId, session, result);
    }

    // ── 平台对外的 MCP 端点 ─────────────────────────────

    static final String MCP_AGENT = "platform/mcp-endpoint";
    static final String MCP_VERSION = "1.0.0";
    static final String BO_SOURCE = "vendor.erp.tools/demo-erp-bo";

    /**
     * MCP 端点暴露的一个工具。
     *
     * @param mcpName MCP 客户端看到的名字，语义查询工具名里的点换成下划线
     * @param type api 或 semantic
     */
    public record McpTool(
            String name, String mcpName, String type, String source, String description, String inputSchema) {}

    /** 只读的 BO 操作（写级别 L0）和语义查询工具；BO 工具来源取已发布的最新版本 */
    public List<McpTool> mcpTools() {
        AssetResolver resolver = services.registry().resolverFor(new Viewer.Platform());
        List<McpTool> tools = new ArrayList<>();
        resolver.resolveOfKind(BO_SOURCE, ToolSourceAsset.class, AssetResolver.PUBLISHED)
                .ifPresent(source -> {
                    String ref = BO_SOURCE + "@" + source.metadata().version();
                    for (var tool : source.spec().tools()) {
                        if (!"L0".equals(tool.writeLevel())) continue;
                        String schema = tool.inputSchema() == null
                                ? "{\"type\":\"object\"}"
                                : Json.MAPPER.writeValueAsString(tool.inputSchema());
                        tools.add(new McpTool(tool.name(), tool.name(), "api", ref, tool.description(), schema));
                    }
                });
        for (var tool : BuiltinTools.SEMANTIC) {
            tools.add(new McpTool(
                    tool.name(),
                    tool.name().replace('.', '_'),
                    "semantic",
                    "platform/semantic",
                    tool.description(),
                    SemanticService.TOOL_SCHEMAS.get(tool.name())));
        }
        return tools;
    }

    /** MCP 端点的有效定义：只读工具，挂上全部已发布的标品语义包 */
    private EffectiveDefinition platformAgentDefinition(String agent) {
        List<EffectiveDefinition.EffectiveTool> tools = mcpTools().stream()
                .map(t -> new EffectiveDefinition.EffectiveTool(
                        t.name(), t.type(), Layers.Layer.platform, t.source(), t.description(), "L0", "query"))
                .toList();
        List<String> packs = services
                .registry()
                .latestPublished(new AssetFilter("SemanticPack", null, null, null, null), new Viewer.Platform())
                .assets()
                .stream()
                .map(a -> a.metadata().namespace() + "/" + a.metadata().name() + "@"
                        + a.metadata().version())
                .toList();
        return new EffectiveDefinition(
                agent,
                agent.equals(MCP_AGENT) ? "平台 MCP 端点" : "平台导航",
                "以平台身份使用只读的 ERP 工具",
                List.of(),
                tools,
                List.of(),
                List.of(),
                new EffectiveDefinition.EffectiveModel("fast", null, Layers.Layer.platform),
                packs,
                new AgentSpec.WritePolicy("L0"),
                null,
                null,
                null,
                new EffectiveDefinition.Provenance(
                        "agent:" + agent, null, agent + "@" + MCP_VERSION, null, List.of(), List.of(), packs));
    }

    /**
     * MCP 端点上的一次工具调用：以 agent:platform/mcp-endpoint 的身份经工具网关执行，鉴权点 3 与审计照常，
     * 并记一次运行，追踪可以在治理中心查看。
     */
    public ToolGateway.ToolOutcome callAsMcp(DemoUser user, String userToken, String tool, Map<String, Object> args) {
        return callPlatformTool(MCP_AGENT, user, new RunContext.Subject(userToken, null, null), null, tool, args);
    }

    /**
     * /mcp 的受限回调调用（ADR 0014）：外部 Agent 持受限只读令牌代表发起用户调用。工具网关、鉴权点 3 与审计照常，
     * 数据范围按 sub 用户；BO 委托令牌按受限声明直接签发（不做第二次令牌交换），调用本身按 act.sub 记一条审计。
     */
    public ToolGateway.ToolOutcome callAsMcpRestricted(
            TokenService.RestrictedClaims claims, String restrictedToken, String tool, Map<String, Object> args) {
        DemoUser user = DemoDirectory.findUser(claims.sub())
                .orElseThrow(() -> new ApiException(401, "INVALID_TOKEN", "受限令牌中的用户不存在"));
        return callPlatformTool(
                MCP_AGENT, user, new RunContext.Subject(null, null, restrictedToken), claims, tool, args);
    }

    /** 平台导航（统一入口的导航型交互）查找单据时使用的身份 */
    public static final String NAVIGATOR_AGENT = "platform/navigator";

    /**
     * 单独测试一个工具或技能的结果（P6 技能编辑器）。
     *
     * @param pending 需要确认时的确认点标题，单独测试不挂起、不写入
     */
    public record ToolTest(String runId, String traceId, ToolGateway.ToolOutcome outcome, String pending) {}

    /**
     * 在私有环境里以某个 Agent 的身份单独调用一个工具（ADR 0011）：agent 与它引用的草稿都在 overlay 里，令牌交换、鉴权点 3、
     * 守卫与审计和正式运行相同；遇到确认点时停在那里，返回确认点，不写入数据。运行记下发起测试的开发者。
     */
    public ToolTest testTool(
            DemoUser user,
            String userToken,
            String agent,
            List<Asset> overlay,
            List<String> overlayYaml,
            String tool,
            Map<String, Object> args,
            String startedBy) {
        String tenantId = user.tenantId();
        RuntimeDeps deps = depsFor(tenantId, overlay);
        EffectiveDefinition definition = assemble(agent, tenantId, overlay);
        String runId = "run-" + UUID.randomUUID();
        String traceId = Tracer.newTraceId();
        String now = IsoTime.now(services.clock());
        Journal journal = new Journal();
        Tracer tracer = new Tracer(traceId, traces, tenantId, services.clock());
        var root = tracer.start("单独测试 · " + tool, SpanRecord.SpanKind.run, null, "run", Map.of());
        root.set("eab.agent", agent).set("eab.user.id", user.id()).set("eab.tenant.id", tenantId);
        RunContext ctx = new RunContext(
                runId,
                "test-" + runId,
                runUser(user),
                "on-behalf",
                null,
                new RunContext.Subject(userToken, null, null),
                agent,
                definition,
                deps.identity().issueAgentToken(definition, tenantId),
                0);
        ToolGateway.ToolOutcome outcome;
        String pending = null;
        try {
            Exec exec = new Exec(ctx, journal, event -> {}, root, "run", false);
            outcome = new AgentRuntime(deps).tools().callTool(exec, "call", tool, args);
            root.end();
        } catch (RunEvents.Suspended e) {
            pending = e.pending() == null ? "需要确认" : e.pending().title();
            tracer.suspendOpenSpans();
            outcome = ToolGateway.ToolOutcome.failure("CONFIRMATION_REQUIRED", "需要确认：" + pending + "（单独测试不执行写入）");
        } catch (Exception e) {
            root.fail(e.getMessage());
            outcome = ToolGateway.ToolOutcome.failure("TOOL_FAILED", e.getMessage());
        }
        runs.insert(new RunRecord(
                runId,
                traceId,
                ctx.sessionId(),
                tenantId,
                user.id(),
                agent,
                "on-behalf",
                null,
                "单独测试 " + tool,
                "completed",
                Journal.toJson(journal.state()),
                Json.MAPPER.writeValueAsString(outcome),
                now,
                now));
        runs.markTest(runId, startedBy, overlayYaml);
        return new ToolTest(runId, traceId, outcome, pending);
    }

    /**
     * 以平台的某个只读身份（MCP 端点、导航）调用一个工具：令牌交换、鉴权点 3、审计与 Agent 的工具调用相同，并记一次运行。
     * restricted 非空时是 /mcp 的受限回调（ADR 0014）：主体是受限令牌，Agent 令牌的 scope 收窄到受限范围，
     * 调用按 act.sub 再记一条审计。
     */
    public ToolGateway.ToolOutcome callPlatformTool(
            String agent, DemoUser user, String userToken, String tool, Map<String, Object> args) {
        return callPlatformTool(agent, user, new RunContext.Subject(userToken, null, null), null, tool, args);
    }

    ToolGateway.ToolOutcome callPlatformTool(
            String agent,
            DemoUser user,
            RunContext.Subject subject,
            TokenService.RestrictedClaims restricted,
            String tool,
            Map<String, Object> args) {
        String tenantId = user.tenantId();
        RuntimeDeps deps = depsFor(tenantId);
        EffectiveDefinition definition = platformAgentDefinition(agent);
        List<String> readOps = new ArrayList<>(definition.tools().stream()
                .filter(t -> t.type().equals("api"))
                .map(EffectiveDefinition.EffectiveTool::name)
                .toList());
        if (restricted != null) readOps.retainAll(restricted.operations());
        String agentToken = services.identity().issueAgentToken(agent, MCP_VERSION, tenantId, readOps);
        String runId = "run-" + UUID.randomUUID();
        String traceId = Tracer.newTraceId();
        String now = IsoTime.now(services.clock());
        Journal journal = new Journal();
        Tracer tracer = new Tracer(traceId, traces, tenantId, services.clock());
        var root = tracer.start("平台工具调用 · " + tool, SpanRecord.SpanKind.run, null, "run", Map.of());
        root.set("eab.agent", agent).set("eab.user.id", user.id()).set("eab.tenant.id", tenantId);
        if (restricted != null) root.set("eab.mcp.restricted_agent", restricted.actAgent());
        RunContext ctx = new RunContext(
                runId,
                agent.substring(agent.indexOf('/') + 1) + "-" + runId,
                runUser(user),
                "on-behalf",
                null,
                subject,
                agent,
                definition,
                agentToken,
                0);
        ToolGateway.ToolOutcome outcome;
        try {
            Exec exec = new Exec(ctx, journal, event -> {}, root, "run", false);
            outcome = new AgentRuntime(deps).tools().callTool(exec, "call", tool, args);
            root.end();
        } catch (Exception e) {
            root.fail(e.getMessage());
            outcome = ToolGateway.ToolOutcome.failure("TOOL_FAILED", e.getMessage());
        }
        runs.insert(new RunRecord(
                runId,
                traceId,
                ctx.sessionId(),
                tenantId,
                user.id(),
                agent,
                "on-behalf",
                null,
                "平台工具调用 " + tool,
                "completed",
                Journal.toJson(journal.state()),
                Json.MAPPER.writeValueAsString(outcome),
                now,
                now));
        if (restricted != null) {
            services.audit()
                    .append(NewAuditEvent.of(
                                    restricted.actAgent(),
                                    "mcp.restricted-call",
                                    tool,
                                    outcome.ok() ? "allow" : "deny",
                                    "外部 Agent 以受限只读令牌代表 " + user.id() + " 调用 " + tool)
                            .tenantId(tenantId)
                            .userId(user.id())
                            .agent(restricted.actAgent())
                            .tool(tool)
                            .mode("restricted"));
        }
        return outcome;
    }

    /** 运行结束时导出到 OTLP/HTTP 采集端，失败只记日志，不影响运行结果 */
    private void exportOtlp(String traceId) {
        if (settings.otlpEndpoint() == null || settings.otlpEndpoint().isBlank()) return;
        var body = Otlp.export(traces.list(traceId), "eab-platform", Json.MAPPER::writeValueAsString);
        var request = HttpRequest.newBuilder(URI.create(settings.otlpEndpoint()).resolve("/v1/traces"))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> {
            LOG.warn("追踪导出到 OTLP 失败：{}", error.getMessage());
            return null;
        });
    }
}

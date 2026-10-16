package dev.eab.platform.runtime;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.identity.AgentScope;
import dev.eab.platform.identity.DelegatedClient;
import dev.eab.platform.policy.PolicyController;
import dev.eab.platform.registry.EffectiveAssembly;
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
import dev.eab.runtime.Journal;
import dev.eab.runtime.Ports;
import dev.eab.runtime.RunContext;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RuntimeDeps;
import dev.eab.runtime.model.ModelGateway;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.tracing.Otlp;
import dev.eab.tracing.Tracer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
            DemoUser user, String userToken, String agent, String message, String sessionId, String delegationId) {}

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
                runId, traceId, sessionId, request.user().tenantId(), request.user().id(), request.agent(), mode,
                request.delegationId(), request.message(), "running", Journal.toJson(journal.state()), null, now, now));
        return execute(runs.get(runId).orElseThrow(), request.userToken(), journal, sink);
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
            return execute(run, userToken, journal, sink);
        }
    }

    private RunOutcome execute(RunRecord run, String userToken, Journal journal, RunEvents.Sink sink) {
        DemoUser user = DemoDirectory.findUser(run.userId()).orElseThrow();
        RuntimeDeps deps = depsFor(run.tenantId());
        AgentRuntime runtime = new AgentRuntime(deps);
        Tracer tracer = new Tracer(run.traceId(), traces, run.tenantId(), services.clock());
        var request = new AgentRuntime.RunRequest(
                run.id(),
                run.sessionId(),
                runUser(user),
                run.mode(),
                run.delegationId(),
                new RunContext.Subject(run.delegationId() == null ? userToken : null, run.delegationId()),
                run.agent(),
                run.utterance());
        AgentRuntime.RunResult result = runtime.run(request, journal, tracer, sink);
        runs.update(
                run.id(),
                result.status(),
                Journal.toJson(journal.state()),
                result.status().equals("suspended") ? null : Json.MAPPER.writeValueAsString(result));
        if (!result.status().equals("suspended")) exportOtlp(run.traceId());
        return new RunOutcome(run.id(), run.traceId(), run.sessionId(), result);
    }

    static RunContext.User runUser(DemoUser user) {
        String tenantName = DemoDirectory.findTenant(user.tenantId()).map(DemoDirectory.Tenant::name).orElse(user.tenantId());
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
        ScopedResolver resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        return new RuntimeDeps(
                settings.gateway(),
                resolver,
                AssetResolver.PUBLISHED,
                this::assemble,
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
                settings.maxTurns());
    }

    EffectiveDefinition assemble(String agent, String tenantId) {
        var outcome = EffectiveAssembly.assemble(
                services.registry(), Viewer.tenant(tenantId), EffectiveAssembly.Request.of(agent, tenantId));
        if (outcome instanceof EffectiveAssembly.Outcome.Ok ok) return ok.definition();
        String issues = String.join("；", outcome.issues().stream().map(i -> i.code() + " " + i.message()).toList());
        throw new IllegalStateException("组装 " + agent + " 失败：" + issues);
    }

    /** Agent 令牌：scope 是 Agent 声明的 BO 操作（含技能、钩子工作流里用到的） */
    private String agentToken(EffectiveDefinition definition, String tenantId, AssetResolver resolver) {
        String ref = definition.provenance().agent();
        int at = ref.indexOf('@');
        return services.identity()
                .issueAgentToken(
                        ref.substring(0, at), ref.substring(at + 1), tenantId, AgentScope.declaredBoOperations(definition, resolver));
    }

    private Ports.BoResponse callBo(Ports.BoCall call) {
        var client = new DelegatedClient(services.identity(), settings.erpUrl(), null);
        var subject = call.subject().delegationId() != null
                ? DelegatedClient.Subject.delegation(call.subject().delegationId())
                : DelegatedClient.Subject.user(call.subject().userToken());
        var response = client.callBoApi(new DelegatedClient.BoCall(
                subject, call.agentToken(), call.operation(), call.method(), call.path(), call.query(), call.body(),
                call.idempotencyKey()));
        return new Ports.BoResponse(response.status(), Json.MAPPER.convertValue(response.body(), Object.class));
    }

    /** 鉴权点 2、3：策略引擎裁决，裁决写入审计 */
    private Ports.Policy policy() {
        return new Ports.Policy() {
            @Override
            public Decision evaluate(String checkpoint, Map<String, Object> facts, RunContext context, String target) {
                Decision decision = PolicyEngine.evaluate(services.policyRules(), checkpoint, facts, services.clock().instant());
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
                return services.delegations().get(delegationId).map(d -> d.scope()).orElse(List.of());
            }
        };
    }

    private static String agentLabel(RunContext context) {
        return context.definition() == null ? context.agent() : context.definition().provenance().agent();
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

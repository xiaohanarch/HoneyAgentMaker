package dev.eab.platform.procode;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.extend.ExtendService;
import dev.eab.platform.extend.ExtensionReleases;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.Principals;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.registry.OverlayResolver;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.skills.SkillStudio;
import dev.eab.platform.studio.StudioReleases;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.runtime.semantic.SemanticService;
import dev.eab.spec.AssetParser;
import dev.eab.spec.BuiltinTools;
import dev.eab.spec.Json;
import dev.eab.spec.Lint;
import dev.eab.spec.Viewer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.type.TypeReference;

/**
 * Studio 的 MCP Server 端点（ADR 0014，主提示词 10.10）：给编码 Agent（Claude Code 等）拿业务上下文并直接操作平台。
 * 工具是固定清单：语义查询（与 /mcp 相同，经工具网关按当前用户权限执行）、能力目录、扩展点目录、草稿、资产校验、
 * 评测与部署。每次调用校验 Bearer 用户令牌，只对租户开发者与租户管理员开放；评测与部署类工具按用户限流。
 *
 * <p>传输与 /mcp 同栈：MCP 官方 Java SDK 的无状态 Streamable HTTP（Spring WebMVC），路径 /studio-mcp。
 */
public final class StudioMcpServer {

    public static final String ENDPOINT = "/studio-mcp";
    static final String AUTHORIZATION = "authorization";
    static final List<String> ROLES = List.of("tenant-developer", "tenant-admin");

    private final PlatformServices services;
    private final Principals principals;
    private final RuntimeService runtime;
    private final StudioService studio;
    private final StudioReleases releases;
    private final ExtendService extend;
    private final ExtensionReleases extensionReleases;
    private final SkillStudio skills;
    private final RateLimiter limiter;
    private final WebMvcStatelessServerTransport transport;
    private final McpStatelessSyncServer server;

    public StudioMcpServer(
            PlatformServices services,
            Principals principals,
            RuntimeService runtime,
            StudioService studio,
            StudioReleases releases,
            ExtendService extend,
            ExtensionReleases extensionReleases,
            SkillStudio skills) {
        this.services = services;
        this.principals = principals;
        this.runtime = runtime;
        this.studio = studio;
        this.releases = releases;
        this.extend = extend;
        this.extensionReleases = extensionReleases;
        this.skills = skills;
        this.limiter = new RateLimiter(30, 60_000, services.clock());
        this.transport = WebMvcStatelessServerTransport.builder()
                .messageEndpoint(ENDPOINT)
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        AUTHORIZATION,
                        request.headers().firstHeader("Authorization") == null
                                ? ""
                                : request.headers().firstHeader("Authorization"))))
                .build();
        this.server = McpServer.sync(transport)
                .serverInfo("eab-studio", "0.1.0")
                .instructions(
                        "ERP Agent Builder Studio 的开发工具：语义查询、能力目录、扩展点目录、草稿、资产校验、" + "评测与部署。语义查询按当前用户的业务权限与数据权限执行。")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
        for (SyncToolSpecification spec : tools()) server.addTool(spec);
    }

    public List<String> toolNames() {
        return server.listTools().stream().map(McpSchema.Tool::name).toList();
    }

    public RouterFunction<ServerResponse> router() {
        return transport.getRouterFunction();
    }

    public void close() {
        server.close();
    }

    @FunctionalInterface
    interface ToolCall {
        McpSchema.CallToolResult run(DemoUser user, String token, Map<String, Object> args);
    }

    private List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> specs = new ArrayList<>();
        for (var tool : BuiltinTools.SEMANTIC) {
            specs.add(tool(
                    tool.name().replace('.', '_'),
                    tool.description() + "（与 /mcp 端点相同，按当前用户的权限执行）",
                    SemanticService.TOOL_SCHEMAS.get(tool.name()),
                    false,
                    (user, token, args) -> {
                        ToolOutcome outcome = runtime.callAsMcp(user, token, tool.name(), args);
                        return new McpSchema.CallToolResult(
                                List.of(new McpSchema.TextContent(outcome.content())), !outcome.ok(), null, null);
                    }));
        }
        specs.add(tool(
                "studio_list_capabilities",
                "能力目录：流程、Agent、语义包、知识、技能、工具与模型档位",
                "{\"type\":\"object\"}",
                false,
                (user, token, args) -> result(studio.catalog(user.tenantId()))));
        specs.add(tool(
                "studio_list_vendor_agents",
                "可扩展的标品 Agent 清单与各自的扩展点",
                "{\"type\":\"object\"}",
                false,
                (user, token, args) -> result(extend.vendorAgents(user.tenantId()))));
        specs.add(tool(
                "studio_get_vendor_agent",
                "一个标品 Agent 的详情与扩展点",
                "{\"type\":\"object\",\"required\":[\"namespace\",\"name\"],\"properties\":{"
                        + "\"namespace\":{\"type\":\"string\",\"description\":\"命名空间，例如 vendor.pur\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"资产名\"}}}",
                false,
                (user, token, args) ->
                        result(extend.vendorAgent(user.tenantId(), arg(args, "namespace"), arg(args, "name")))));
        specs.add(tool(
                "studio_list_drafts",
                "本租户的草稿列表（id、类型、资产引用、意图与步骤）",
                "{\"type\":\"object\",\"properties\":{\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}}}",
                false,
                (user, token, args) -> result(draftList(user, args))));
        specs.add(tool(
                "studio_get_draft",
                "草稿详情：YAML、评测集与静态检查结果",
                "{\"type\":\"object\",\"required\":[\"draftId\"],\"properties\":"
                        + "{\"draftId\":{\"type\":\"string\",\"description\":\"草稿 id\"}}}",
                false,
                (user, token, args) -> result(studio.view(studio.draft(user, arg(args, "draftId"))))));
        specs.add(tool(
                "studio_validate_asset",
                "校验资产 YAML（schema 与静态检查），不写入平台；扩展可以带评测集 YAML 一起校验",
                "{\"type\":\"object\",\"required\":[\"yaml\"],\"properties\":{"
                        + "\"yaml\":{\"type\":\"string\",\"description\":\"完整的资产 YAML\"},"
                        + "\"evalYaml\":{\"type\":\"string\",\"description\":\"扩展的评测集 YAML，可选，随扩展一起校验\"}}}",
                false,
                (user, token, args) -> result(validate(user, args))));
        specs.add(tool(
                "studio_generate_evals",
                "为草稿生成评测集初稿（技能草稿不需要评测集）",
                "{\"type\":\"object\",\"required\":[\"draftId\"],\"properties\":{"
                        + "\"draftId\":{\"type\":\"string\"},"
                        + "\"testUser\":{\"type\":\"string\",\"description\":\"评测里模拟的提问用户，默认 zhangmin\"}}}",
                true,
                (user, token, args) -> result(generateEvals(user, args))));
        specs.add(tool(
                "studio_run_evals",
                "运行草稿的评测集（评测里确认点一律自动拒绝）",
                "{\"type\":\"object\",\"required\":[\"draftId\"],\"properties\":"
                        + "{\"draftId\":{\"type\":\"string\"}}}",
                true,
                (user, token, args) -> result(runEvals(user, args))));
        specs.add(tool(
                "studio_deploy_draft",
                "发布草稿：Agent 发布到 dev 环境；扩展与技能发布后对本租户即时生效",
                "{\"type\":\"object\",\"required\":[\"draftId\"],\"properties\":{"
                        + "\"draftId\":{\"type\":\"string\"},"
                        + "\"note\":{\"type\":\"string\",\"description\":\"变更说明，可选\"}}}",
                true,
                (user, token, args) -> result(deploy(user, args))));
        specs.add(tool(
                "studio_promote",
                "把租户 Agent 晋级到 test 或 prod 环境（门禁不过时报错并列出门禁）",
                "{\"type\":\"object\",\"required\":[\"ref\",\"version\",\"environment\"],\"properties\":{"
                        + "\"ref\":{\"type\":\"string\",\"description\":\"例如 tenant.t001.agents/credit-clerk\"},"
                        + "\"version\":{\"type\":\"string\",\"description\":\"语义化版本 x.y.z\"},"
                        + "\"environment\":{\"type\":\"string\",\"enum\":[\"test\",\"prod\"]}}}",
                true,
                (user, token, args) -> result(
                        releases.promote(user, arg(args, "ref"), arg(args, "version"), arg(args, "environment")))));
        return specs;
    }

    /** 每次调用都校验令牌与角色；评测与部署类工具按用户限流；服务层错误转成 isError 的工具结果 */
    private McpSchema.CallToolResult guarded(
            McpTransportContext context, McpSchema.CallToolRequest request, boolean limited, ToolCall call) {
        DemoUser user;
        String token;
        try {
            String header = String.valueOf(context.get(AUTHORIZATION));
            var principal = principals.verify(header);
            if (!(principal.viewer() instanceof Viewer.Tenant)
                    || principal.user().roles().stream().noneMatch(ROLES::contains)) {
                return error("Studio MCP 只对租户开发者和租户管理员开放");
            }
            user = principal.user();
            token = principal.token();
        } catch (ApiException e) {
            return error("缺少或无效的用户令牌：" + e.getMessage());
        }
        try {
            if (limited) limiter.check("studio-mcp:" + user.id());
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            return call.run(user, token, args);
        } catch (StudioException e) {
            String details = e.details() == null ? "" : " " + Json.MAPPER.writeValueAsString(e.details());
            return error(e.code() + "：" + e.getMessage() + details);
        } catch (ApiException e) {
            return error(e.getMessage());
        }
    }

    private static McpSchema.CallToolResult result(Object value) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(Json.MAPPER.writeValueAsString(value))), false, null, null);
    }

    private static McpSchema.CallToolResult error(String message) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(message)), true, null, null);
    }

    private SyncToolSpecification tool(String name, String description, String schema, boolean limited, ToolCall call) {
        var definition = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(Json.MAPPER.readValue(schema, new TypeReference<Map<String, Object>>() {}))
                .build();
        return new SyncToolSpecification(definition, (context, request) -> guarded(context, request, limited, call));
    }

    /** 必填字符串参数 */
    private static String arg(Map<String, Object> args, String name) {
        if (args.get(name) instanceof String value && !value.isBlank()) return value;
        throw new StudioException(400, "BAD_REQUEST", "缺少参数：" + name);
    }

    /** 可选字符串参数 */
    private static String argOrNull(Map<String, Object> args, String name) {
        return args.get(name) instanceof String value && !value.isBlank() ? value : null;
    }

    private List<Map<String, Object>> draftList(DemoUser user, Map<String, Object> args) {
        int limit = args.get("limit") instanceof Number value ? value.intValue() : 30;
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (StudioStores.Draft draft : studio.drafts().listByTenant(user.tenantId(), limit)) {
            drafts.add(Map.of(
                    "id",
                    draft.id(),
                    "kind",
                    draft.kind(),
                    "ref",
                    draft.ref(),
                    "intent",
                    draft.intent() == null ? "" : draft.intent(),
                    "step",
                    draft.step(),
                    "updatedAt",
                    draft.updatedAt()));
        }
        return drafts;
    }

    /**
     * 镜像 POST /registry/validate：schema 校验加静态检查，引用只按当前身份可见的范围解析。
     * 扩展可以带上评测集 YAML 一起校验：草稿的静态检查把扩展与评测集当同一批资产（OverlayResolver），
     * 这样扩展里对评测集的引用在发布前就能解析。
     */
    private Object validate(DemoUser user, Map<String, Object> args) {
        AssetParser.ParseResult parsed = AssetParser.parseYaml(arg(args, "yaml"));
        if (!parsed.ok()) return Map.of("valid", false, "issues", parsed.issues(), "lint", List.of());
        AssetParser.ParseResult evalParsed = null;
        if (args.get("evalYaml") instanceof String evalYaml && !evalYaml.isBlank()) {
            evalParsed = AssetParser.parseYaml(evalYaml);
            if (!evalParsed.ok()) return Map.of("valid", false, "issues", evalParsed.issues(), "lint", List.of());
        }
        var base = services.registry().resolverFor(principals.viewerOf(user));
        var resolver =
                evalParsed == null ? base : new OverlayResolver(base, List.of(parsed.asset(), evalParsed.asset()));
        List<Lint.LintIssue> lint = Lint.lint(parsed.asset(), resolver);
        return Map.of(
                "valid", lint.stream().noneMatch(issue -> issue.severity().equals("error")),
                "issues", List.of(),
                "lint", lint);
    }

    private Object generateEvals(DemoUser user, Map<String, Object> args) {
        String id = arg(args, "draftId");
        String testUser = argOrNull(args, "testUser");
        StudioStores.Draft draft = studio.draft(user, id);
        return switch (draft.kind()) {
            case "Agent" -> studio.generateEvals(user, draft, testUser == null ? "zhangmin" : testUser);
            case "AgentExtension" ->
                extensionReleases.generateEvals(user, id, testUser == null ? "zhangmin" : testUser);
            default -> throw new StudioException(400, "BAD_REQUEST", "技能草稿不需要评测集，直接发布即可");
        };
    }

    private Object runEvals(DemoUser user, Map<String, Object> args) {
        String id = arg(args, "draftId");
        StudioStores.Draft draft = studio.draft(user, id);
        return switch (draft.kind()) {
            case "Agent" -> studio.runEvals(user, draft);
            case "AgentExtension" -> extensionReleases.runEvals(user, id);
            default -> throw new StudioException(400, "BAD_REQUEST", "技能草稿没有评测集");
        };
    }

    private Object deploy(DemoUser user, Map<String, Object> args) {
        String id = arg(args, "draftId");
        String note = argOrNull(args, "note");
        StudioStores.Draft draft = studio.draft(user, id);
        return switch (draft.kind()) {
            case "Agent" -> releases.publish(user, draft, note);
            case "AgentExtension" -> extensionReleases.publish(user, id, note);
            case "Skill" -> skills.publish(user, id);
            default -> throw new StudioException(400, "BAD_REQUEST", "不支持发布 " + draft.kind() + " 类型的草稿");
        };
    }
}

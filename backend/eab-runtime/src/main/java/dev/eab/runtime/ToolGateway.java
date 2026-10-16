package dev.eab.runtime;

import dev.eab.assembler.Conditions;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.assembler.EffectiveDefinition.EffectiveTool;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.policy.PolicyInputs;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.semantic.SemanticService;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.ToolSourceSpec.ToolDefinition;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer.Span;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/**
 * 工具网关（主提示词 8.3 节）：平台暴露给 Agent 的所有工具都经过这里。每次调用依次经过：
 *
 * <ol>
 *   <li>鉴权点 3：策略引擎裁决（是否声明、调用模式、写级别、委托范围、确认与审批），裁决写审计；
 *   <li>监督层守卫：写级别不超过 Agent 声明的上限；
 *   <li>人工确认：需要确认时挂起运行；需要审批的写操作在审批收件箱上线（P8）之前一律拒绝；
 *   <li>按工具类型执行：BO API、语义查询、内置工具、技能、子 Agent、外部 MCP Server、http、文档检索。
 * </ol>
 *
 * 工具结果写入运行日志，运行恢复时直接重放，不会重复写入 ERP。
 */
public final class ToolGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 平台自带、只读、不访问 ERP 的工具，受托运行时视为在委托范围内 */
    static final Set<String> PLATFORM_READ_TYPES = Set.of("semantic", "builtin", "documents");

    private final RuntimeDeps deps;
    private final SkillEngine skills;
    private final SubAgents subAgents;

    /** 子 Agent 调用由 Agent 循环实现，这里只依赖接口，避免循环依赖 */
    public interface SubAgents {
        ToolOutcome call(Exec exec, EffectiveTool tool, Map<String, Object> args);
    }

    public ToolGateway(RuntimeDeps deps, SubAgents subAgents) {
        this.deps = deps;
        this.subAgents = subAgents;
        this.skills = new SkillEngine(deps, this);
    }

    public SkillEngine skills() {
        return skills;
    }

    /** 工作流里的 agent 步骤：直接调用子 Agent（鉴权点 2 在子 Agent 调用里裁决） */
    ToolOutcome subAgent(Exec exec, EffectiveTool tool, Map<String, Object> args) {
        return subAgents.call(exec, tool, args);
    }

    /**
     * 工具结果。
     *
     * @param data 成功时的结果（JSON 能表示的值）
     * @param error 失败或被拒绝时的结构化原因 {code, reason, ...}，模型据此告诉用户
     */
    public record ToolOutcome(boolean ok, Object data, Map<String, Object> error) {

        public static ToolOutcome success(Object data) {
            return new ToolOutcome(true, data, null);
        }

        public static ToolOutcome failure(String code, String reason) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("reason", reason);
            return new ToolOutcome(false, null, error);
        }

        /** 交给模型的内容 */
        public String content() {
            return JSON.writeValueAsString(ok ? data : Map.of("error", error));
        }
    }

    /** Agent 层的工具调用：模型请求的工具必须在有效定义里声明 */
    public ToolOutcome callTool(Exec exec, String callId, String name, Map<String, Object> args) throws Exception {
        String key = exec.key() + "/tool:" + callId;
        if (exec.journal().has(key)) return exec.journal().record(key, ToolOutcome.class, () -> null);
        EffectiveTool tool = exec.ctx().definition().tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
        return run(exec, "tool:" + callId, name, tool, exec.ctx().definition(), args, true);
    }

    /**
     * 技能、工作流里的工具步骤：调用的是工具来源里的工具（例如 rfq_list），不在 Agent 的有效定义里。鉴权时把它作为“经技能声明”的工具，
     * 写级别取工具来源里的定义，其余规则与 Agent 层相同。
     */
    public ToolOutcome callSourceTool(Exec exec, String segment, String source, String name, Map<String, Object> args)
            throws Exception {
        String key = exec.key() + "/" + segment;
        if (exec.journal().has(key)) return exec.journal().record(key, ToolOutcome.class, () -> null);
        ToolSourceAsset toolSource = deps.resolver()
                .resolveOfKind(source, ToolSourceAsset.class, deps.statuses())
                .orElse(null);
        if (toolSource == null) return ToolOutcome.failure("TOOL_SOURCE_NOT_FOUND", "找不到工具来源 " + source);
        ToolDefinition definition = toolSource.spec().tools().stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElse(null);
        if (definition == null) return ToolOutcome.failure("TOOL_NOT_FOUND", source + " 里没有工具 " + name);
        String sourceRef = toolSource.metadata().namespace() + "/"
                + toolSource.metadata().name() + "@" + toolSource.metadata().version();
        EffectiveTool tool = new EffectiveTool(
                name,
                "api",
                Layer.vendor,
                sourceRef,
                definition.description(),
                definition.writeLevel(),
                definition.category());
        EffectiveDefinition base = exec.ctx().definition();
        List<EffectiveTool> tools = new ArrayList<>(base.tools());
        tools.removeIf(t -> t.name().equals(name));
        tools.add(tool);
        EffectiveDefinition widened = new EffectiveDefinition(
                base.name(),
                base.displayName(),
                base.expertise(),
                base.segments(),
                tools,
                base.approvals(),
                base.hooks(),
                base.model(),
                base.semanticPacks(),
                base.writePolicy(),
                base.executionSteps(),
                base.output(),
                base.routing(),
                base.provenance());
        return run(exec, segment, name, tool, widened, args, false);
    }

    private ToolOutcome run(
            Exec exec,
            String segment,
            String name,
            EffectiveTool tool,
            EffectiveDefinition definition,
            Map<String, Object> args,
            boolean emitEvents)
            throws Exception {
        String key = exec.key() + "/" + segment;
        Span span = exec.span(
                "工具 · " + name, tool != null && tool.type().equals("skill") ? SpanKind.skill : SpanKind.tool, segment);
        span.set("eab.tool.name", name)
                .set("eab.tool.type", tool == null ? null : tool.type())
                .set("eab.tool.source", tool == null ? null : tool.source())
                .set("eab.tool.write_level", tool == null ? null : tool.writeLevel())
                .set("eab.tool.arguments", JSON.writeValueAsString(args));
        if (emitEvents) {
            exec.emit(
                    key,
                    RunEvent.of("tool_call", exec.ctx().agent(), "callId", segment, "tool", name, "arguments", args));
        }
        Exec inner = exec.under(span, segment);
        try {
            ToolOutcome outcome = exec.journal()
                    .record(key, ToolOutcome.class, () -> guardedRun(inner, name, tool, definition, args));
            span.set("eab.tool.ok", outcome.ok());
            if (!outcome.ok())
                span.set("eab.tool.error", String.valueOf(outcome.error().get("code")));
            span.set("eab.tool.result", truncate(outcome.content())).end();
            if (emitEvents) {
                sinkResult(exec, segment, name, outcome);
            }
            return outcome;
        } catch (RunEvents.Suspended e) {
            throw e;
        } catch (Exception e) {
            span.fail(e.getMessage());
            throw e;
        }
    }

    private static void sinkResult(Exec exec, String segment, String name, ToolOutcome outcome) {
        exec.emit(RunEvent.of(
                "tool_result",
                exec.ctx().agent(),
                "callId",
                segment,
                "tool",
                name,
                "ok",
                outcome.ok(),
                "summary",
                summarize(outcome)));
    }

    static String truncate(String text) {
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "…";
    }

    /** 工具结果卡片上的一句话：列表给条数，错误给原因 */
    static String summarize(ToolOutcome outcome) {
        if (!outcome.ok()) return String.valueOf(outcome.error().get("reason"));
        if (outcome.data() instanceof Map<?, ?> map) {
            if (map.get("total") instanceof Number n) return "返回 " + n + " 条";
            if (map.get("items") instanceof List<?> list) return "返回 " + list.size() + " 条";
        }
        return "完成";
    }

    private ToolOutcome guardedRun(
            Exec exec, String name, EffectiveTool tool, EffectiveDefinition definition, Map<String, Object> args)
            throws Exception {
        RunContext ctx = exec.ctx();
        Decision decision = authorize(exec, name, tool, definition, args);
        if (decision.outcome().equals("deny")) {
            return ToolOutcome.failure("POLICY_DENIED", decision.reason() + "（规则 " + decision.ruleId() + "）");
        }
        String overLevel = tool == null
                ? null
                : Supervisor.checkWriteLevel(
                        ctx.agent(),
                        definition.writePolicy() == null
                                ? null
                                : definition.writePolicy().maxLevel(),
                        name,
                        tool.writeLevel());
        var guard = exec.span("守卫 · 写级别", SpanKind.guard, "guard.write-level");
        guard.set("eab.guard.result", overLevel == null ? "pass" : "block").end();
        if (overLevel != null) return ToolOutcome.failure("WRITE_LEVEL_EXCEEDED", overLevel);

        boolean confirmed = exec.confirmed();
        if (decision.outcome().equals("approve")) {
            // 审批收件箱在 P8 上线，在那之前需要审批的写操作一律拒绝（失败即拒绝）
            return ToolOutcome.failure("APPROVAL_REQUIRED", decision.reason() + "；审批流程尚未开通，本次不执行");
        }
        if (decision.outcome().equals("confirm") && !confirmed) {
            boolean approved = Confirmations.require(
                    exec, "confirm", "确认执行 " + name, preview(name, tool, args, decision.reason()));
            if (!approved) return ToolOutcome.failure("USER_REJECTED", "用户拒绝执行 " + name + "，没有写入任何数据");
            confirmed = true;
        }
        Exec execution = exec.withConfirmed(confirmed);
        ToolOutcome outcome = execute(execution, name, tool, args);
        if (tool != null && Supervisor.isWrite(tool.writeLevel()) && outcome.ok()) watchWrites(execution, name);
        if (outcome.ok()) watchScope(execution, outcome.data());
        return outcome;
    }

    /** 确认卡片上的内容：工具、写级别、参数和需要确认的原因 */
    static Map<String, Object> preview(String name, EffectiveTool tool, Map<String, Object> args, String reason) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("tool", name);
        if (tool != null && tool.writeLevel() != null) preview.put("writeLevel", tool.writeLevel());
        preview.put("arguments", args);
        preview.put("reason", reason);
        return preview;
    }

    private Decision authorize(
            Exec exec, String name, EffectiveTool tool, EffectiveDefinition definition, Map<String, Object> args)
            throws Exception {
        RunContext ctx = exec.ctx();
        return exec.journal().record(exec.key() + "/policy", Decision.class, () -> {
            PolicyInputs.Delegation delegation = null;
            if (ctx.delegated()) {
                List<String> scope = new ArrayList<>(deps.policy().delegationScope(ctx.delegationId()));
                for (EffectiveTool t : definition.tools()) {
                    if (PLATFORM_READ_TYPES.contains(t.type())) scope.add(t.name());
                }
                delegation = new PolicyInputs.Delegation(ctx.delegationId(), scope);
            }
            Map<String, Object> facts = PolicyInputs.tool(new PolicyInputs.ToolContext(
                    ctx.user().facts(),
                    ctx.mode(),
                    definition,
                    name,
                    args,
                    deps.policy().l3Enabled(ctx.tenantId()),
                    delegation,
                    null));
            Decision decision = deps.policy().evaluate("tool", facts, ctx, name);
            var span = exec.span("鉴权点 3 · " + decision.outcome(), SpanKind.policy, "policy");
            span.set("eab.policy.checkpoint", "tool")
                    .set("eab.policy.outcome", decision.outcome())
                    .set("eab.policy.rule", decision.ruleId())
                    .set("eab.policy.reason", decision.reason())
                    .set("eab.policy.rules_version", decision.rulesVersion())
                    .end();
            return decision;
        });
    }

    private void watchWrites(Exec exec, String name) {
        RunContext ctx = exec.ctx();
        deps.alerts().recordWrite(ctx.sessionId(), name);
        int recent = deps.alerts().recentWrites(ctx.sessionId(), Supervisor.WRITE_WINDOW_SECONDS);
        var span = exec.span("守卫 · 行为异常", SpanKind.guard, "guard.anomaly");
        if (recent > Supervisor.WRITE_BURST_LIMIT) {
            String message = "会话 " + ctx.sessionId() + " 在 10 分钟内执行了 " + recent + " 次写操作";
            deps.alerts().raise(ctx, "write_burst", "high", message, Map.of("tool", name, "count", recent));
            span.set("eab.guard.result", "alert").set("eab.guard.message", message);
        } else {
            span.set("eab.guard.result", "pass");
        }
        span.end();
    }

    /** 每次运行记录看到的可见范围，范围突然扩大时告警 */
    private final Map<String, Set<String>> scopes = new ConcurrentHashMap<>();

    private void watchScope(Exec exec, Object data) {
        List<String> visible = Supervisor.visibleScope(data);
        if (visible == null) return;
        Set<String> seen = scopes.computeIfAbsent(exec.ctx().runId(), k -> new LinkedHashSet<>());
        List<String> added;
        synchronized (seen) {
            added = Supervisor.expansion(seen, visible);
            seen.addAll(visible);
        }
        if (!added.isEmpty()) {
            deps.alerts()
                    .raise(
                            exec.ctx(),
                            "scope_expansion",
                            "medium",
                            "运行中访问的数据范围扩大到 " + String.join("、", added),
                            Map.of("added", added));
        }
    }

    /** 运行结束时清理本次运行的状态 */
    void forget(String runId) {
        scopes.remove(runId);
    }

    private ToolOutcome execute(Exec exec, String name, EffectiveTool tool, Map<String, Object> args) throws Exception {
        if (tool == null) return ToolOutcome.failure("TOOL_NOT_DECLARED", "工具 " + name + " 没有声明");
        return switch (tool.type()) {
            case "api", "mcp" -> sourceTool(exec, tool, args);
            case "semantic" -> semantic(exec, name, args);
            case "builtin" -> builtin(name, args);
            case "skill" -> skills.runSkill(exec, tool.source(), args);
            case "agent" -> subAgents.call(exec, tool, args);
            case "documents" ->
                ToolOutcome.success(Map.of(
                        "items",
                        deps.documents().search(tool.source(), String.valueOf(args.getOrDefault("query", "")), 3)));
            case "remoteAgent" -> remoteAgent(exec, tool, args);
            default -> ToolOutcome.failure("UNSUPPORTED_TOOL", "不支持的工具类型 " + tool.type());
        };
    }

    /** 远程 Agent：经平台的 A2A 网关调用，A2A 请求与响应原文写进 span，对方不可用时给出降级说明 */
    private ToolOutcome remoteAgent(Exec exec, EffectiveTool tool, Map<String, Object> args) {
        var span = exec.span("A2A · " + tool.name(), SpanKind.agent, "a2a");
        String message = String.valueOf(args.getOrDefault("message", ""));
        Ports.RemoteResult result = deps.remoteAgents().call(exec.ctx(), tool.source(), message);
        span.set("eab.a2a.remote", tool.source())
                .set("eab.a2a.state", result.state())
                .set("eab.a2a.task_id", result.taskId())
                .set("eab.a2a.context_id", result.contextId())
                .set("eab.a2a.exchanges", JSON.writeValueAsString(result.exchanges()));
        if (!result.ok()) {
            span.fail(result.error());
            return ToolOutcome.failure(result.errorCode(), result.error());
        }
        span.end();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("agent", result.agentName() == null ? tool.name() : result.agentName());
        data.put("state", result.state());
        data.put("text", result.text() == null ? "" : result.text());
        if (result.taskId() != null) data.put("taskId", result.taskId());
        return ToolOutcome.success(data);
    }

    private ToolOutcome semantic(Exec exec, String name, Map<String, Object> args) {
        try {
            return ToolOutcome.success(semanticService(exec.ctx().definition()).call(name, args));
        } catch (SemanticService.SemanticException e) {
            return ToolOutcome.failure(e.code(), e.getMessage());
        }
    }

    /** 本次 Agent 挂载的语义包，只在这个范围内查询 */
    SemanticService semanticService(EffectiveDefinition definition) {
        List<SemanticPackAsset> packs = new ArrayList<>();
        for (String ref : definition.provenance().semanticPacks()) {
            deps.resolver()
                    .resolveOfKind(ref, SemanticPackAsset.class, deps.statuses())
                    .ifPresent(packs::add);
        }
        return new SemanticService(packs, deps.meta());
    }

    private static ToolOutcome builtin(String name, Map<String, Object> args) {
        if (!name.equals("calculator")) return ToolOutcome.failure("UNSUPPORTED_TOOL", "没有内置工具 " + name);
        Object expression = args.get("expression");
        if (!(expression instanceof String text) || text.isBlank()) {
            return ToolOutcome.failure("BAD_INPUT", "calculator 需要 expression，例如 (3 / 12) * 100");
        }
        try {
            // 用 CEL 计算算术表达式：没有变量、没有函数副作用，不存在执行任意代码的风险
            // CEL 不允许整数与小数直接运算，整数一律写成小数
            Object value = Conditions.evaluate(text.replaceAll("(?<![\\d.])(\\d+)(?![\\d.])", "$1.0"), Map.of());
            // 整数结果按整数输出：2144500 而不是 2144500.0
            if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) value = d.longValue();
            return ToolOutcome.success(Map.of("expression", text, "value", value));
        } catch (RuntimeException e) {
            return ToolOutcome.failure("BAD_EXPRESSION", "表达式无法计算：" + e.getMessage());
        }
    }

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    /** 工具来源里的工具：按来源类型分发到 BO API、http、外部 MCP Server */
    private ToolOutcome sourceTool(Exec exec, EffectiveTool tool, Map<String, Object> args) {
        ToolSourceAsset source = deps.resolver()
                .resolveOfKind(tool.source(), ToolSourceAsset.class, deps.statuses())
                .orElse(null);
        if (source == null) return ToolOutcome.failure("TOOL_SOURCE_NOT_FOUND", "找不到工具来源 " + tool.source());
        ToolDefinition definition = source.spec().tools().stream()
                .filter(t -> t.name().equals(tool.name()))
                .findFirst()
                .orElse(null);
        if (definition == null) return ToolOutcome.failure("TOOL_NOT_FOUND", tool.source() + " 里没有工具 " + tool.name());
        String type = source.spec().type();
        try {
            return switch (type) {
                case "bo-api" -> boApi(exec, definition, args);
                case "mcp" -> ToolOutcome.success(deps.mcp().call(source.spec().connection(), tool.name(), args));
                case "http" ->
                    ToolOutcome.success(deps.http()
                            .call(
                                    exec.ctx(),
                                    source.spec().connection(),
                                    definition.operation().method(),
                                    definition.operation().path(),
                                    args));
                default -> ToolOutcome.failure("UNSUPPORTED_TOOL", "不支持的工具来源类型 " + type);
            };
        } catch (RunEvents.Suspended e) {
            throw e;
        } catch (RuntimeException e) {
            return ToolOutcome.failure("TOOL_FAILED", e.getMessage());
        }
    }

    /**
     * BO API：按工具定义的 method 和 path 构造请求，路径参数从调用参数里取，GET 的其余参数放进查询字符串。
     * 统一经平台的令牌交换调用；写操作带幂等键，键由运行编号和调用位置派生，重放时不会重复写入。
     */
    private ToolOutcome boApi(Exec exec, ToolDefinition definition, Map<String, Object> args) {
        String method = definition.operation().method().toUpperCase();
        Map<String, Object> rest = new LinkedHashMap<>(args);
        Matcher m = PATH_PARAM.matcher(definition.operation().path());
        StringBuilder path = new StringBuilder();
        while (m.find()) {
            Object value = rest.remove(m.group(1));
            if (value == null || String.valueOf(value).isBlank()) {
                return ToolOutcome.failure("BAD_INPUT", "缺少参数 " + m.group(1));
            }
            m.appendReplacement(
                    path,
                    Matcher.quoteReplacement(java.net.URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)
                            .replace("+", "%20")));
        }
        m.appendTail(path);
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, Object> body = null;
        if (method.equals("GET")) {
            rest.forEach((k, v) -> {
                if (v != null)
                    query.put(
                            k,
                            v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                                    ? String.valueOf(n.longValue())
                                    : String.valueOf(v));
            });
        } else {
            body = rest;
        }
        RunContext ctx = exec.ctx();
        String idempotencyKey =
                Supervisor.isWrite(definition.writeLevel()) ? idempotencyKey(ctx.runId(), exec.key()) : null;
        Ports.BoResponse response = deps.bo()
                .call(new Ports.BoCall(
                        ctx.subject(),
                        ctx.agentToken(),
                        definition.name(),
                        method,
                        path.toString(),
                        query,
                        body,
                        idempotencyKey));
        if (response.status() >= 400) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", response.status());
            if (response.body() instanceof Map<?, ?> map && map.get("error") instanceof Map<?, ?> inner) {
                inner.forEach((k, v) -> error.put(String.valueOf(k), v));
            } else {
                error.put("code", "BO_ERROR");
                error.put("reason", "BO API 返回 " + response.status());
            }
            return new ToolOutcome(false, null, error);
        }
        return ToolOutcome.success(response.body());
    }

    static String idempotencyKey(String runId, String key) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256").digest((runId + "|" + key).getBytes(StandardCharsets.UTF_8));
            return "eab-" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 工具的入参 JSON Schema，交给模型：技能用 inputSchema，工具来源用工具定义里的 inputSchema，语义工具用固定 schema */
    public String inputSchema(EffectiveTool tool) {
        String fallback = "{\"type\":\"object\"}";
        return switch (tool.type()) {
            case "semantic" -> SemanticService.TOOL_SCHEMAS.getOrDefault(tool.name(), fallback);
            case "builtin" ->
                tool.name().equals("calculator")
                        ? "{\"type\":\"object\",\"required\":[\"expression\"],\"properties\":{\"expression\":{\"type\":\"string\","
                                + "\"description\":\"四则运算表达式，例如 (3 / 12) * 100\"}}}"
                        : fallback;
            case "skill" ->
                deps.resolver()
                        .resolveOfKind(tool.source(), SkillAsset.class, deps.statuses())
                        .map(s -> s.spec().inputSchema() == null
                                ? fallback
                                : JSON.writeValueAsString(s.spec().inputSchema()))
                        .orElse(fallback);
            case "api", "mcp" ->
                deps.resolver()
                        .resolveOfKind(tool.source(), ToolSourceAsset.class, deps.statuses())
                        .flatMap(s -> s.spec().tools().stream()
                                .filter(t -> t.name().equals(tool.name()))
                                .findFirst())
                        .map(t -> t.inputSchema() == null ? fallback : JSON.writeValueAsString(t.inputSchema()))
                        .orElse(fallback);
            case "agent" ->
                "{\"type\":\"object\",\"required\":[\"message\"],\"properties\":{\"message\":{\"type\":\"string\","
                        + "\"description\":\"交给子 Agent 的任务\"}}}";
            case "remoteAgent" ->
                "{\"type\":\"object\",\"required\":[\"message\"],\"properties\":{\"message\":{\"type\":\"string\","
                        + "\"description\":\"交给外部 Agent 的消息\"}}}";
            case "documents" ->
                "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"}}}";
            default -> fallback;
        };
    }
}

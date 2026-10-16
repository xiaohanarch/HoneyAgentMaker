package dev.eab.runtime;

import dev.eab.assembler.Conditions;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.assembler.EffectiveDefinition.EffectiveHook;
import dev.eab.assembler.EffectiveDefinition.EffectiveTool;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.policy.PolicyInputs;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.RunEvents.Suspended;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.runtime.model.ModelGateway;
import dev.eab.runtime.model.ModelTypes.AssistantTurn;
import dev.eab.runtime.model.ModelTypes.CallContext;
import dev.eab.runtime.model.ModelTypes.ModelException;
import dev.eab.runtime.model.ModelTypes.ModelResult;
import dev.eab.runtime.model.ModelTypes.ToolCall;
import dev.eab.runtime.model.ModelTypes.ToolSpec;
import dev.eab.runtime.model.ModelTypes.ToolTurn;
import dev.eab.runtime.model.ModelTypes.Turn;
import dev.eab.runtime.model.ModelTypes.UserTurn;
import dev.eab.runtime.model.PromptTemplate;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer;
import dev.eab.tracing.Tracer.Span;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 循环（主提示词 8.1.1 节）：自研的轻量循环，保持源码可读。一次运行的流程：
 *
 * <ol>
 *   <li>组装有效定义，签发本次运行的 Agent 令牌；
 *   <li>读取记忆，执行前置步骤；
 *   <li>最多 maxTurns 轮“模型调用 → 工具调用”，每个工具调用都经过工具网关（鉴权点 3、守卫、确认）；
 *   <li>后置步骤：输出守卫，以及 onResult 钩子（用 CEL 判断条件，租户钩子失败被隔离）；
 *   <li>写会话记忆，产出最终结果。
 * </ol>
 *
 * 需要人工确认时运行挂起，运行日志就是保存的状态；用户决定后从头重放（见 {@link Journal}）。子 Agent 调用先过鉴权点 2，
 * 再递归执行，事件和 span 挂在父运行下面。
 */
public final class AgentRuntime {

    private final RuntimeDeps deps;
    private final ToolGateway tools;

    public AgentRuntime(RuntimeDeps deps) {
        this.deps = deps;
        this.tools = new ToolGateway(deps, this::callSubAgent);
    }

    public ToolGateway tools() {
        return tools;
    }

    /**
     * 一次运行的请求。
     *
     * @param agent namespace/name
     * @param mode on-behalf（代用户操作）或 delegated（受托自主运行）
     */
    public record RunRequest(
            String runId,
            String sessionId,
            RunContext.User user,
            String mode,
            String delegationId,
            RunContext.Subject subject,
            String agent,
            String utterance,
            Map<String, Object> routing) {

        public RunRequest(
                String runId,
                String sessionId,
                RunContext.User user,
                String mode,
                String delegationId,
                RunContext.Subject subject,
                String agent,
                String utterance) {
            this(runId, sessionId, user, mode, delegationId, subject, agent, utterance, null);
        }
    }

    /**
     * 运行结果。
     *
     * @param status completed、suspended、failed
     * @param pending 挂起时等待的确认
     * @param unverified 输出守卫没能在工具结果里找到的单号和金额
     */
    public record RunResult(
            String status,
            String text,
            Map<String, Object> output,
            Journal.Pending pending,
            String error,
            List<String> unverified,
            double costUsd) {}

    /** Agent 循环在某个 Agent 上的结果 */
    record AgentOutcome(String text, Map<String, Object> output, List<String> unverified, double costUsd) {}

    public RunResult run(RunRequest request, Journal journal, Tracer tracer, RunEvents.Sink sink) {
        Span root = tracer.start("运行 · " + request.agent(), SpanKind.run, null, "run", Map.of());
        root.set("eab.run.id", request.runId())
                .set("eab.agent", request.agent())
                .set("eab.user.id", request.user().id())
                .set("eab.tenant.id", request.user().tenantId())
                .set("eab.mode", request.mode())
                .set("eab.delegation.id", request.delegationId())
                .set("gen_ai.system", deps.gateway().providerName());
        try {
            // 恢复运行时 routing 为 null：路由 span 在第一次执行时已经写入同一条追踪
            if (request.routing() != null) recordRouting(root, request.routing(), sink, request.agent());
            EffectiveDefinition definition = assemble(
                    root, "run/assemble", request.agent(), request.user().tenantId());
            String token =
                    deps.identity().issueAgentToken(definition, request.user().tenantId());
            RunContext ctx = new RunContext(
                    request.runId(),
                    request.sessionId(),
                    request.user(),
                    request.mode(),
                    request.delegationId(),
                    request.subject(),
                    request.agent(),
                    definition,
                    token,
                    0);
            sink.accept(RunEvent.of(
                    "run_started",
                    request.agent(),
                    "runId",
                    request.runId(),
                    "traceId",
                    tracer.traceId(),
                    "agent",
                    definition.displayName()));
            Exec exec = new Exec(ctx, journal, sink, root, "run", false);
            AgentOutcome outcome = runAgent(exec, request.utterance(), true);
            deps.memory()
                    .appendSession(
                            request.sessionId(),
                            request.user().tenantId(),
                            request.user().id(),
                            request.agent(),
                            request.utterance(),
                            outcome.text());
            streamText(sink, request.agent(), outcome.text());
            sink.accept(RunEvent.of(
                    "final",
                    request.agent(),
                    "text",
                    outcome.text(),
                    "output",
                    outcome.output(),
                    "traceId",
                    tracer.traceId(),
                    "unverified",
                    outcome.unverified(),
                    "costUsd",
                    outcome.costUsd()));
            root.set("eab.run.status", "completed")
                    .set("eab.cost.usd", outcome.costUsd())
                    .end();
            return new RunResult(
                    "completed", outcome.text(), outcome.output(), null, null, outcome.unverified(), outcome.costUsd());
        } catch (Suspended s) {
            root.set("eab.run.status", "suspended");
            tracer.suspendOpenSpans();
            sink.accept(RunEvent.of(
                    "suspended",
                    request.agent(),
                    "key",
                    s.pending().key(),
                    "title",
                    s.pending().title()));
            return new RunResult("suspended", null, null, s.pending(), null, List.of(), 0);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            root.set("eab.run.status", "failed").fail(message);
            sink.accept(RunEvent.of(
                    "error",
                    request.agent(),
                    "code",
                    e instanceof ModelException m ? m.code() : "RUN_FAILED",
                    "message",
                    message,
                    "traceId",
                    tracer.traceId()));
            return new RunResult("failed", null, null, null, message, List.of(), 0);
        } finally {
            tools.forget(request.runId());
        }
    }

    /** 路由记录里写进 span 属性的键 */
    public static final List<String> ROUTING_KEYS =
            List.of("query", "levels", "target", "assistant", "confidence", "reason", "decidedBy");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 路由决策（主提示词 9.2 节第 4 步）：候选、分数、过滤原因、选择与理由写进同一条追踪，并作为第一个事件推给调用方。
     */
    public static void recordRouting(Span root, Map<String, Object> routing, RunEvents.Sink sink, String agent) {
        Span span = root.tracer()
                .start(
                        "路由 · " + routing.getOrDefault("target", agent),
                        SpanKind.routing,
                        root,
                        "run/routing",
                        Map.of());
        for (String key : ROUTING_KEYS) {
            Object value = routing.get(key);
            if (value != null)
                span.set("eab.routing." + key, value instanceof String ? value : JSON.writeValueAsString(value));
        }
        span.end();
        if (sink != null) sink.accept(new RunEvent("routing", agent, routing));
    }

    private EffectiveDefinition assemble(Span parent, String key, String agent, String tenantId) {
        Span span = parent.tracer().start("组装 · " + agent, SpanKind.assemble, parent, key, Map.of());
        try {
            EffectiveDefinition definition = deps.assembly().assemble(agent, tenantId);
            span.set("eab.assemble.agent", definition.provenance().agent())
                    .set(
                            "eab.assemble.extensions",
                            String.join(",", definition.provenance().extensions()))
                    .set("eab.assemble.segments", definition.segments().size())
                    .set(
                            "eab.assemble.tools",
                            String.join(
                                    ",",
                                    definition.tools().stream()
                                            .map(EffectiveTool::name)
                                            .toList()))
                    .end();
            return definition;
        } catch (RuntimeException e) {
            span.fail(e.getMessage());
            throw e;
        }
    }

    /** 最终回复分段推送，前端可以逐段显示 */
    static void streamText(RunEvents.Sink sink, String agent, String text) {
        if (text == null || text.isEmpty()) return;
        int chunk = 24;
        for (int i = 0; i < text.length(); i += chunk) {
            sink.accept(
                    RunEvent.of("text_delta", agent, "delta", text.substring(i, Math.min(text.length(), i + chunk))));
        }
    }

    /**
     * 在一个 Agent 上执行循环。exec.span 是这个 Agent 的父 span，exec.key 是它的路径。
     *
     * @param withSession 入口 Agent 带上会话记忆，子 Agent 不带
     */
    AgentOutcome runAgent(Exec exec, String utterance, boolean withSession) throws Exception {
        RunContext ctx = exec.ctx();
        EffectiveDefinition definition = ctx.definition();

        Span memorySpan = exec.span("记忆", SpanKind.memory, "memory");
        List<String> longTerm =
                deps.memory().longTerm(ctx.tenantId(), ctx.user().id(), ctx.agent());
        List<Turn> turns = new ArrayList<>();
        if (withSession) {
            for (List<String> pair : deps.memory()
                    .session(ctx.sessionId(), ctx.tenantId(), ctx.user().id())) {
                turns.add(new UserTurn(pair.get(0)));
                turns.add(new AssistantTurn(pair.get(1), List.of()));
            }
        }
        memorySpan
                .set("eab.memory.long_term", longTerm.size())
                .set("eab.memory.session_turns", turns.size() / 2)
                .end();
        turns.add(new UserTurn(utterance));

        runPreSteps(exec, definition, utterance);

        List<ToolSpec> toolSpecs = new ArrayList<>();
        for (EffectiveTool tool : definition.tools()) {
            toolSpecs.add(new ToolSpec(
                    tool.name(),
                    tool.description() == null ? tool.name() : tool.description(),
                    tools.inputSchema(tool)));
        }
        var semantic = tools.semanticService(definition);
        ConstraintGuard constraints = ConstraintGuard.of(definition, semantic.rules());
        // 运行可能锁定了版本（例如 prod 部署的 ns/name@1.0.0），交给模型网关的身份不带版本，剧本按 Agent 匹配
        String agentId = ctx.agent().contains("@") ? ctx.agent().substring(0, ctx.agent().indexOf('@')) : ctx.agent();
        CallContext callContext = new CallContext(
                agentId,
                definition.displayName(),
                ctx.user().id(),
                definition.routing() == null || definition.routing().examples() == null
                        ? List.of()
                        : definition.routing().examples());
        PromptTemplate.Input prompt =
                new PromptTemplate.Input(definition.segments(), userContext(ctx), longTerm, outputFields(definition));

        double cost = 0;
        String text = null;
        Map<String, Object> output = null;
        List<String> leaked = new ArrayList<>();
        StringBuilder evidence = new StringBuilder(utterance);
        for (int turn = 1; turn <= deps.maxTurns(); turn++) {
            String modelSegment = "turn:" + turn + "/model";
            String modelKey = exec.key() + "/" + modelSegment;
            boolean replay = exec.journal().has(modelKey);
            List<Turn> snapshot = List.copyOf(turns);
            String profile = definition.model() == null
                    ? "reasoning"
                    : definition.model().profile();
            Double temperature =
                    definition.model() == null ? null : definition.model().temperature();
            ModelResult result = exec.journal()
                    .record(
                            modelKey,
                            ModelResult.class,
                            () -> deps.gateway()
                                    .complete(
                                            new ModelGateway.Request(
                                                    profile,
                                                    temperature,
                                                    prompt,
                                                    snapshot,
                                                    toolSpecs,
                                                    callContext,
                                                    exec.journal().masker(),
                                                    q -> semantic.retrieve(q, 4)),
                                            // exec 的路径与它的 span 路径一致，模型 span 的路径就是 key/turn:n/model
                                            exec.span(),
                                            modelSegment));
            cost += result.costUsd();
            leaked.addAll(result.leakedSegments());
            if (result.toolCalls().isEmpty()) {
                if (result.text() == null || result.text().isBlank()) {
                    // 带推理过程的模型可能把输出上限全用在推理上，正文为空；明确失败，不给用户一个空回复
                    throw new ModelException(
                            "MODEL_EMPTY_REPLY",
                            "模型没有给出回复（结束原因 " + result.stopReason() + "）。如果结束原因是输出长度，可以调大 EAB_MODEL_MAX_TOKENS",
                            null);
                }
                text = result.text();
                output = result.output();
                break;
            }
            if (!replay && result.text() != null && !result.text().isBlank()) {
                exec.emit(RunEvent.of("step", ctx.agent(), "text", result.text()));
            }
            turns.add(new AssistantTurn(result.text(), result.toolCalls()));
            Exec turnExec = new Exec(
                    ctx, exec.journal(), exec.sink(), exec.span(), exec.key() + "/turn:" + turn, exec.confirmed());
            String nextModelKey = exec.key() + "/turn:" + (turn + 1) + "/model";
            for (ToolCall call : result.toolCalls()) {
                ToolOutcome outcome = tools.callTool(turnExec, call.id(), call.name(), call.arguments());
                var triggered = constraints.after(turnExec, call.name(), outcome, nextModelKey);
                String content =
                        triggered == null ? outcome.content() : ConstraintGuard.annotate(outcome.content(), triggered);
                turns.add(new ToolTurn(call.id(), call.name(), content));
                evidence.append('\n').append(outcome.content());
            }
        }
        if (text == null) {
            text = "已达到最多 " + deps.maxTurns() + " 轮工具调用，先给出目前掌握的情况：请缩小问题范围后重试。";
        }

        Supervisor.ReferenceCheck check = outputGuard(exec, text, evidence.toString());
        text = check.text();
        if (!leaked.isEmpty()) {
            var span = exec.span("守卫 · 指令泄露", SpanKind.guard, "guard.leak");
            deps.alerts()
                    .raise(
                            ctx,
                            "instruction_leak",
                            "high",
                            ctx.agent() + " 的回复复述了内部指令，已遮蔽",
                            Map.of("segments", leaked));
            span.set("eab.guard.result", "masked")
                    .set("eab.guard.segments", String.join(",", leaked))
                    .end();
        }
        String hookNote = runHooks(exec, definition, output);
        if (hookNote != null) text = text + "\n\n" + hookNote;
        return new AgentOutcome(text, output, check.unverified(), cost);
    }

    private static PromptTemplate.UserContext userContext(RunContext ctx) {
        String delegation = ctx.delegated()
                ? "委托编号 " + ctx.delegationId() + "，委托人 " + ctx.user().name() + "，只做只读分析"
                : null;
        return new PromptTemplate.UserContext(
                ctx.user().id(),
                ctx.user().name(),
                ctx.tenantId(),
                ctx.user().tenantName(),
                ctx.user().roles(),
                ctx.user().dataScopeLabel(),
                LocalDate.now(ZoneOffset.UTC).toString(),
                delegation);
    }

    private static List<PromptTemplate.OutputField> outputFields(EffectiveDefinition definition) {
        List<PromptTemplate.OutputField> fields = new ArrayList<>();
        if (definition.output() == null || definition.output().schema() == null) return fields;
        definition
                .output()
                .schema()
                .forEach((name, field) -> fields.add(
                        new PromptTemplate.OutputField(name, field.type(), field.description(), field.enumValues())));
        return fields;
    }

    /** 前置步骤：技能作为确定性前置处理执行；守卫目前都在后置 */
    private void runPreSteps(Exec exec, EffectiveDefinition definition, String utterance) {
        if (definition.executionSteps() == null || definition.executionSteps().pre() == null) return;
        int i = 0;
        for (var step : definition.executionSteps().pre()) {
            i++;
            if (step.skill() == null) continue;
            Span span = exec.span("前置技能 · " + step.skill(), SpanKind.skill, "pre:" + i);
            ToolOutcome outcome =
                    tools.skills().runSkill(exec.under(span, "pre:" + i), step.skill(), Map.of("utterance", utterance));
            span.set("eab.skill.ok", outcome.ok()).end();
        }
    }

    /** 输出守卫：结论里引用的单号、金额必须能在本次运行的工具结果里找到，找不到则标注“未经核实” */
    private Supervisor.ReferenceCheck outputGuard(Exec exec, String text, String evidence) {
        Span span = exec.span("守卫 · 输出引用核对", SpanKind.guard, "guard.output");
        Supervisor.ReferenceCheck check = Supervisor.checkReferences(text, evidence);
        span.set("eab.guard.result", check.unverified().isEmpty() ? "pass" : "flagged")
                .set("eab.guard.checked", check.checked())
                .set("eab.guard.unverified", String.join(",", check.unverified()))
                .end();
        return check;
    }

    /**
     * onResult 钩子：标品钩子失败中止主流程，租户钩子失败只记录并在回复里提示。钩子里的工作流可以带确认步骤，需要确认时同样挂起。
     *
     * @return 需要在回复末尾提示的内容，没有时为 null
     */
    private String runHooks(Exec exec, EffectiveDefinition definition, Map<String, Object> output) throws Exception {
        List<EffectiveHook> hooks = definition.hooks().stream()
                .filter(h -> h.event().equals("onResult"))
                .toList();
        if (hooks.isEmpty() || output == null) return null;
        Map<String, Object> input = Map.of("result", output);
        List<String> notes = new ArrayList<>();
        int index = 0;
        for (EffectiveHook hook : hooks) {
            index++;
            String target = hook.workflow() != null ? hook.workflow() : hook.skill();
            if (hook.when() != null && !Conditions.test(hook.when(), input)) continue;
            String segment = "hook:onResult:" + index;
            Span span = exec.span("钩子 · " + target, SpanKind.hook, segment);
            span.set("eab.hook.source", hook.source())
                    .set(
                            "eab.hook.layer",
                            hook.layer() == null ? null : hook.layer().name());
            Exec inner = exec.under(span, segment);
            String failure;
            try {
                ToolOutcome outcome = hook.workflow() != null
                        ? tools.skills().runWorkflow(inner, hook.workflow(), input)
                        : tools.skills().runSkill(inner, hook.skill(), input);
                failure = outcome.ok() ? null : String.valueOf(outcome.error().get("reason"));
            } catch (Suspended e) {
                // 钩子里的确认、等待事件同样挂起整个运行，不算钩子失败
                throw e;
            } catch (RuntimeException e) {
                failure = e.getMessage();
            }
            if (failure == null) {
                span.end();
                notes.add("已执行后续流程：" + target);
                continue;
            }
            span.fail(failure);
            // 标品钩子失败中止主流程；租户钩子失败只记录，主流程继续（主提示词 5.4 节第 4 条）
            if ("propagate".equals(hook.failurePolicy())) {
                throw new IllegalStateException("后续流程 " + target + " 失败：" + failure);
            }
            notes.add("租户扩展的后续流程没有完成（" + hook.source() + "）：" + failure);
        }
        return notes.isEmpty() ? null : String.join("\n", notes);
    }

    /** 子 Agent 调用：先过鉴权点 2，再组装子 Agent 并递归执行，深度加一 */
    ToolOutcome callSubAgent(Exec exec, EffectiveTool tool, Map<String, Object> args) {
        RunContext ctx = exec.ctx();
        String ref = tool.source() == null ? tool.name() : tool.source();
        String agent = ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
        Map<String, Object> facts = PolicyInputs.handoff(
                ctx.user().facts(),
                new PolicyInputs.Caller("agent", ctx.agent(), ctx.depth() + 1),
                new PolicyInputs.HandoffTarget(agent, "Agent", true, List.of()));
        Decision decision = deps.policy().evaluate("handoff", facts, ctx, agent);
        Span policy = exec.span("鉴权点 2 · " + decision.outcome(), SpanKind.policy, "handoff");
        policy.set("eab.policy.checkpoint", "handoff")
                .set("eab.policy.outcome", decision.outcome())
                .set("eab.policy.rule", decision.ruleId())
                .set("eab.policy.reason", decision.reason())
                .end();
        if (decision.outcome().equals("deny")) {
            return ToolOutcome.failure("POLICY_DENIED", decision.reason() + "（规则 " + decision.ruleId() + "）");
        }
        Span span = exec.span("子 Agent · " + agent, SpanKind.agent, "agent");
        try {
            EffectiveDefinition definition = assemble(span, exec.key() + "/agent/assemble", agent, ctx.tenantId());
            String token = deps.identity().issueAgentToken(definition, ctx.tenantId());
            Exec inner = exec.under(span, "agent")
                    .withContext(ctx.forSubAgent(agent, definition, token))
                    .withConfirmed(false);
            String message = String.valueOf(args.getOrDefault("message", ""));
            AgentOutcome outcome = runAgent(inner, message, false);
            span.end();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("agent", agent);
            data.put("text", outcome.text());
            if (outcome.output() != null) data.put("output", outcome.output());
            return ToolOutcome.success(data);
        } catch (Suspended e) {
            throw e;
        } catch (Exception e) {
            span.fail(e.getMessage());
            return ToolOutcome.failure("SUB_AGENT_FAILED", agent + " 运行失败：" + e.getMessage());
        }
    }
}

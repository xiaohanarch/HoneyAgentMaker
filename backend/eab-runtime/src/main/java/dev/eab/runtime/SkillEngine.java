package dev.eab.runtime;

import dev.eab.assembler.Conditions;
import dev.eab.runtime.RunEvents.Suspended;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.runtime.model.Scripts;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.spec.model.Asset.WorkflowAsset;
import dev.eab.spec.model.StepSpecs.Step;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer.Span;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 技能执行器与精简工作流引擎（主提示词 8.1、8.7 节）：确定性执行，不调用大模型。
 *
 * <p>步骤里 with、transform、condition 的值都是 CEL 表达式，执行环境里可以引用 input、input 的各字段、前面步骤的输出；
 * confirm、notify 步骤的消息用 mustache 渲染。技能里的 tool 步骤同样走工具网关，同样要过鉴权。工作流在技能步骤之外，
 * 还支持 agent、parallel、waitEvent 三类步骤；waitEvent 在 P3 只做挂起，P4 接上事件触发后再恢复。
 */
public final class SkillEngine {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RuntimeDeps deps;
    private final ToolGateway tools;

    SkillEngine(RuntimeDeps deps, ToolGateway tools) {
        this.deps = deps;
        this.tools = tools;
    }

    /** 某一步执行失败（工具报错、用户拒绝），技能就此结束，失败原因作为技能结果 */
    static final class StepFailure extends RuntimeException {
        final ToolOutcome outcome;

        StepFailure(ToolOutcome outcome) {
            super(String.valueOf(outcome.error().get("reason")));
            this.outcome = outcome;
        }
    }

    /** 执行技能。技能的结果是最后执行的一步的结果 */
    public ToolOutcome runSkill(Exec exec, String ref, Map<String, Object> input) {
        SkillAsset skill = deps.resolver()
                .resolveOfKind(ref, SkillAsset.class, deps.statuses())
                .orElse(null);
        if (skill == null) return ToolOutcome.failure("SKILL_NOT_FOUND", "找不到技能 " + ref);
        exec.span().set("eab.skill.ref", ref);
        return runSteps(exec, skill.spec().steps(), input);
    }

    /** 执行工作流（钩子、事件触发），input 的各字段在表达式里可以直接引用，例如 result.severity */
    public ToolOutcome runWorkflow(Exec exec, String ref, Map<String, Object> input) {
        WorkflowAsset workflow = deps.resolver()
                .resolveOfKind(ref, WorkflowAsset.class, deps.statuses())
                .orElse(null);
        if (workflow == null) return ToolOutcome.failure("WORKFLOW_NOT_FOUND", "找不到工作流 " + ref);
        return runSteps(exec, workflow.spec().steps(), input);
    }

    private ToolOutcome runSteps(Exec exec, List<Step> steps, Map<String, Object> input) {
        Map<String, Object> vars = new LinkedHashMap<>(input);
        vars.put("input", input);
        try {
            Object last = executeAll(exec, steps, vars);
            return ToolOutcome.success(last);
        } catch (StepFailure failure) {
            return failure.outcome;
        }
    }

    /** 顺序执行，返回最后一步的结果 */
    private Object executeAll(Exec exec, List<Step> steps, Map<String, Object> vars) {
        Object last = null;
        Exec current = exec;
        for (Step step : steps) {
            last = execute(current, step, vars);
            // 用户在确认步骤同意之后，本技能后面的写操作不再逐个询问
            if (step.type().equals("confirm")) current = current.withConfirmed(true);
        }
        return last;
    }

    private Object execute(Exec exec, Step step, Map<String, Object> vars) {
        String segment = "step:" + step.id();
        Span span = exec.span("步骤 · " + (step.name() == null ? step.id() : step.name()), SpanKind.workflow, segment);
        span.set("eab.step.type", step.type()).set("eab.step.id", step.id());
        Exec inner = exec.under(span, segment);
        try {
            Object value = switch (step.type()) {
                case "tool" -> tool(inner, step, vars);
                case "skill" -> skill(inner, step, vars);
                case "agent" -> agent(inner, step, vars);
                case "transform" -> Conditions.evaluate(step.expression(), vars);
                case "condition" -> {
                    boolean holds = Conditions.test(step.ifExpression(), vars);
                    span.set("eab.step.branch", holds ? "then" : "else");
                    List<Step> branch = holds ? step.thenSteps() : step.elseSteps();
                    yield branch == null ? null : executeAll(inner, branch, vars);
                }
                case "parallel" -> parallel(inner, step, vars);
                case "confirm" -> confirm(inner, step, vars);
                case "notify" -> notify(inner, step, vars);
                case "waitEvent" -> throw waitEvent(inner, step);
                default -> throw new StepFailure(ToolOutcome.failure("UNSUPPORTED_STEP", "不支持的步骤类型 " + step.type()));
            };
            if (!step.type().equals("condition") || value != null) {
                vars.put(step.output() == null ? step.id() : step.output(), value);
            }
            span.end();
            return value;
        } catch (Suspended e) {
            throw e;
        } catch (StepFailure e) {
            span.fail(e.getMessage());
            throw e;
        } catch (Conditions.EvaluationException e) {
            span.fail(e.getMessage());
            throw new StepFailure(
                    ToolOutcome.failure("EXPRESSION_FAILED", "步骤 " + step.id() + " 的表达式出错：" + e.getMessage()));
        }
    }

    /**
     * with 的值：字符串按 CEL 求值，其余（数字、布尔、对象）原样作为常量。引用了没有提供的可选参数时视为不传，例如 input.dueDate。
     */
    Map<String, Object> arguments(Map<String, JsonNode> with, Map<String, Object> vars) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (with == null) return args;
        with.forEach((name, node) -> {
            Object value;
            if (node.isString()) {
                try {
                    value = Conditions.evaluate(node.asString(), vars);
                } catch (Conditions.EvaluationException e) {
                    if (!missingKey(e)) throw e;
                    value = null;
                }
            } else {
                value = JSON.convertValue(node, Object.class);
            }
            if (value != null) args.put(name, value);
        });
        return args;
    }

    /** cel-java 对不存在的 Map 键报 “key 'x' is not present in map”，也兼容 “no such key” 的写法 */
    static boolean missingKey(Conditions.EvaluationException e) {
        String message = String.valueOf(e.getMessage());
        return message.contains("is not present in map") || message.contains("no such key");
    }

    private Object tool(Exec exec, Step step, Map<String, Object> vars) {
        Map<String, Object> args = arguments(step.with(), vars);
        ToolOutcome outcome;
        try {
            outcome = step.source() == null
                    ? tools.callTool(exec, step.id(), step.tool(), args)
                    : tools.callSourceTool(exec, "call", step.source(), step.tool(), args);
        } catch (Suspended e) {
            throw e;
        } catch (Exception e) {
            throw new StepFailure(ToolOutcome.failure("TOOL_FAILED", e.getMessage()));
        }
        if (!outcome.ok()) throw new StepFailure(outcome);
        return outcome.data();
    }

    private Object skill(Exec exec, Step step, Map<String, Object> vars) {
        ToolOutcome outcome = runSkill(exec, step.skill(), arguments(step.with(), vars));
        if (!outcome.ok()) throw new StepFailure(outcome);
        return outcome.data();
    }

    private Object agent(Exec exec, Step step, Map<String, Object> vars) {
        var tool = new dev.eab.assembler.EffectiveDefinition.EffectiveTool(
                step.agent(), "agent", null, step.agent(), null, null, null);
        ToolOutcome outcome = tools.subAgent(exec, tool, arguments(step.with(), vars));
        if (!outcome.ok()) throw new StepFailure(outcome);
        return outcome.data();
    }

    /** 并行分发：各分支在虚拟线程上执行，全部完成后按分支顺序返回结果 */
    private Object parallel(Exec exec, Step step, Map<String, Object> vars) {
        List<List<Step>> branches = step.branches() == null ? List.of() : step.branches();
        List<Object> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < branches.size(); i++) {
                List<Step> branch = branches.get(i);
                Span span = exec.span("分支 " + (i + 1), SpanKind.workflow, "branch:" + i);
                Exec inner = exec.under(span, "branch:" + i);
                Map<String, Object> branchVars = new LinkedHashMap<>(vars);
                futures.add(pool.submit(() -> {
                    try {
                        Object value = executeAll(inner, branch, branchVars);
                        span.end();
                        return value;
                    } catch (RuntimeException e) {
                        if (!(e instanceof Suspended)) span.fail(e.getMessage());
                        throw e;
                    }
                }));
            }
            for (Future<Object> future : futures) results.add(future.get());
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new StepFailure(
                    ToolOutcome.failure("BRANCH_FAILED", e.getCause().getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailure(ToolOutcome.failure("INTERRUPTED", "并行分支被中断"));
        }
        return results;
    }

    /** 人工确认：外层已经确认过（例如调用技能前已确认）时视为已满足，不再重复询问 */
    private Object confirm(Exec exec, Step step, Map<String, Object> vars) {
        String message = Scripts.render(step.message(), vars);
        if (exec.confirmed()) {
            exec.span().set("eab.step.confirmation", "已在调用前确认");
            return Map.of("confirmed", true, "message", message);
        }
        boolean approved = Confirmations.require(exec, "confirm", message, Map.of("message", message));
        if (!approved) throw new StepFailure(ToolOutcome.failure("USER_REJECTED", "用户拒绝：" + message));
        return Map.of("confirmed", true, "message", message);
    }

    private Object notify(Exec exec, Step step, Map<String, Object> vars) {
        String message = Scripts.render(step.message(), vars);
        String channel = step.channel() == null ? "im" : step.channel();
        deps.notifier().notify(exec.ctx(), channel, message);
        return Map.of("sent", true, "channel", channel, "message", message);
    }

    /** 等待业务事件：P3 只挂起，P4 接上事件触发后恢复 */
    private Suspended waitEvent(Exec exec, Step step) {
        Journal.Pending pending = new Journal.Pending(
                exec.key() + "/wait", "event", "等待业务事件 " + step.event(), Map.of("event", step.event()));
        exec.journal().suspend(pending);
        return new Suspended(pending);
    }
}

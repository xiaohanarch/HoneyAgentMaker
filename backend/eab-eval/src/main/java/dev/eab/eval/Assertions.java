package dev.eab.eval;

import dev.eab.assembler.Conditions;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Json;
import dev.eab.spec.model.LifecycleSpecs.Assertion;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 评测断言（主提示词 10.8 节）：一个用例的运行结果（事件、回复、结构化结果）逐条核对断言。断言类型有调用了某工具、没有调用某工具、
 * 结果包含、结果不包含、数值区间、不变量（CEL）和裁判。断言优先用不变量，少用依赖具体数据值的断言。
 */
public final class Assertions {

    private Assertions() {}

    /** 一个用例的运行结果 */
    public record CaseRun(String text, Map<String, Object> output, List<RunEvent> events, String status, String error) {

        /** 本次运行调用过的工具（含子 Agent、技能内部的调用） */
        public Set<String> calledTools() {
            Set<String> tools = new LinkedHashSet<>();
            for (RunEvent e : events) {
                if (e.type().equals("tool_call") && e.data().get("tool") instanceof String t) tools.add(t);
                // 结果钩子执行的工作流或技能也算调用过（hook 事件），钩子里的写操作被拒绝时同样算
                if (e.type().equals("hook") && e.data().get("name") instanceof String n) tools.add(n);
            }
            return tools;
        }
    }

    public record AssertionResult(String type, String description, boolean passed, String detail) {}

    public record CaseResult(
            String id,
            String user,
            String question,
            boolean passed,
            List<AssertionResult> assertions,
            String text,
            String runId,
            String traceId) {}

    /** 裁判：脚本化模型下确定性，配置了真实模型时用 judge 档位的模型 */
    public interface Judge {
        AssertionResult judge(String rubric, String description, String text);
    }

    static final Pattern RULE_ID = Pattern.compile("[A-Z][A-Z0-9]{1,5}-R-\\d{3}");
    static final Pattern QUOTED = Pattern.compile("[「“\"]([^」”\"]{2,30})[」”\"]");

    /** 确定性裁判：评分标准里的规则编号和引号里的要点都要在回复里出现；没有可核对的要点时按通过处理 */
    public static final Judge DETERMINISTIC = (rubric, description, text) -> {
        List<String> points = new ArrayList<>();
        Matcher rules = RULE_ID.matcher(rubric);
        while (rules.find()) points.add(rules.group());
        Matcher quoted = QUOTED.matcher(rubric);
        while (quoted.find()) points.add(quoted.group(1));
        if (points.isEmpty()) {
            return new AssertionResult("judge", description, true, "确定性裁判：评分标准里没有可核对的规则编号或要点，按通过处理");
        }
        List<String> missing = points.stream().filter(p -> !text.contains(p)).toList();
        return new AssertionResult(
                "judge",
                description,
                missing.isEmpty(),
                missing.isEmpty()
                        ? "确定性裁判：回复包含 " + String.join("、", points)
                        : "确定性裁判：回复缺少 " + String.join("、", missing));
    };

    /** 模型裁判；模型调用失败或输出无法解析时判为不通过并写明原因，不静默通过 */
    public static Judge model(ChatModel model, String modelName) {
        return (rubric, description, text) -> {
            String system = "你是评测裁判。根据评分标准判断回答是否合格，只输出一个 JSON 对象：{\"pass\": true 或 false, \"reason\": \"一句话理由\"}";
            String user = "评分标准：" + rubric + "\n回答：" + text;
            try {
                String reply = ChatClient.create(model)
                        .prompt(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                        .options(ToolCallingChatOptions.builder()
                                .model(modelName)
                                .temperature(0.0))
                        .call()
                        .content();
                Map<String, Object> parsed = JsonObjects.parse(reply);
                if (parsed == null || !(parsed.get("pass") instanceof Boolean pass)) {
                    return new AssertionResult("judge", description, false, "模型裁判的输出无法解析");
                }
                return new AssertionResult("judge", description, pass, "模型裁判：" + parsed.getOrDefault("reason", ""));
            } catch (RuntimeException e) {
                return new AssertionResult("judge", description, false, "模型裁判调用失败：" + e.getMessage());
            }
        };
    }

    public static CaseResult check(EvalCase evalCase, CaseRun run, String runId, String traceId, Judge judge) {
        List<AssertionResult> results = new ArrayList<>();
        if (!"completed".equals(run.status())) {
            results.add(new AssertionResult(
                    "run", "运行完成", false, "运行状态 " + run.status() + (run.error() == null ? "" : "：" + run.error())));
        } else {
            for (Assertion a : evalCase.assertions()) results.add(assertOne(a, run, judge));
        }
        boolean passed = results.stream().allMatch(AssertionResult::passed);
        return new CaseResult(
                evalCase.id(), evalCase.user(), evalCase.question(), passed, results, run.text(), runId, traceId);
    }

    public static AssertionResult assertOne(Assertion a, CaseRun run, Judge judge) {
        String text = run.text() == null ? "" : run.text();
        String description = a.description() == null ? a.type() : a.description();
        return switch (a.type()) {
            case "calledTool" -> {
                boolean called = run.calledTools().contains(a.tool());
                yield new AssertionResult(
                        a.type(), description, called, called ? "调用了 " + a.tool() : "没有调用 " + a.tool());
            }
            case "notCalledTool" -> {
                boolean called = run.calledTools().contains(a.tool());
                yield new AssertionResult(
                        a.type(), description, !called, called ? "调用了 " + a.tool() : "没有调用 " + a.tool());
            }
            case "contains" ->
                new AssertionResult(
                        a.type(),
                        description,
                        text.contains(a.text()),
                        (text.contains(a.text()) ? "包含" : "不包含") + "「" + a.text() + "」");
            case "notContains" ->
                new AssertionResult(
                        a.type(),
                        description,
                        !text.contains(a.text()),
                        (text.contains(a.text()) ? "包含" : "不包含") + "「" + a.text() + "」");
            case "numberRange" -> numberRange(a, run.output(), description);
            case "invariant" -> invariant(a, run, description);
            case "judge" -> judge.judge(a.rubric(), description, text);
            default -> new AssertionResult(a.type(), description, false, "不支持的断言类型");
        };
    }

    static AssertionResult numberRange(Assertion a, Map<String, Object> output, String description) {
        Object value = output;
        for (String part : a.path().split("\\.")) {
            value = value instanceof Map<?, ?> m ? m.get(part) : null;
        }
        if (!(value instanceof Number n)) {
            return new AssertionResult(a.type(), description, false, "结构化结果里没有数值 " + a.path());
        }
        double v = n.doubleValue();
        boolean ok = (a.min() == null || v >= a.min()) && (a.max() == null || v <= a.max());
        return new AssertionResult(a.type(), description, ok, a.path() + " = " + v);
    }

    /**
     * 不变量的变量：result 与 output（结构化结果，两个名字等价，钩子条件里习惯写 result）、text（回复）、tools（调用过的工具名）。
     */
    static AssertionResult invariant(Assertion a, CaseRun run, String description) {
        Map<String, Object> output = run.output() == null ? Map.of() : run.output();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("output", output);
        vars.put("result", output);
        vars.put("text", run.text() == null ? "" : run.text());
        vars.put("tools", new ArrayList<>(run.calledTools()));
        try {
            boolean ok = Conditions.test(a.expression(), vars);
            return new AssertionResult(a.type(), description, ok, a.expression() + " = " + ok);
        } catch (RuntimeException e) {
            return new AssertionResult(a.type(), description, false, "不变量求值失败：" + e.getMessage());
        }
    }

    public static String toJson(List<CaseResult> results) {
        return Json.MAPPER.writeValueAsString(results);
    }
}

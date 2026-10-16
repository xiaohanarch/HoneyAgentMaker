package dev.eab.eval;

import dev.eab.spec.model.LifecycleSpecs.Assertion;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Given/When/Then 风格的对话测试（参照 Gherkin），写在评测集的 scenarios 字段里，解析成普通的评测用例。
 * 大模型输出不完全稳定，一个场景可以重复运行多次，整个评测集按最低通过率判定。
 *
 * <pre>
 * 场景: 大额异常给出责任部门
 *   假如 用户是 "wangfang"
 *   当 提问 "本月有哪些高风险交易异常"
 *   那么 调用了工具 "transaction_anomaly_list"
 *   并且 回答包含 "责任部门"
 *   并且 回答不包含 "已退回"
 *   并且 没有调用工具 "payment_execute"
 *   并且 不变量 "result.anomalyCount > 0"
 *   并且 数值 "anomalyCount" 在 1 到 50 之间
 *   并且 裁判 "回答说明了责任部门"
 *   重复 3 次
 * </pre>
 *
 * 英文关键词同样可用：Scenario、Given user、When ask、Then/And calls tool、answer contains、answer does not contain、
 * does not call tool、invariant、number … between … and …、judge、repeat … times。
 */
public final class Scenarios {

    private Scenarios() {}

    public static final class ScenarioException extends RuntimeException {
        public ScenarioException(String message) {
            super(message);
        }
    }

    /** 一个场景解析后的用例和重复次数 */
    public record Scenario(EvalCase evalCase, int repeat) {}

    static final String Q = "\"([^\"]*)\"";
    static final String STEP = "^(?:那么|并且|而且|但是|Then|And|But)\\s+";

    record Rule(Pattern pattern, String type) {}

    static final List<Rule> ASSERTIONS = List.of(
            new Rule(Pattern.compile(STEP + "(?:没有调用工具|未调用工具|does not call tool)\\s+" + Q + "$"), "notCalledTool"),
            new Rule(Pattern.compile(STEP + "(?:调用了工具|调用工具|calls tool)\\s+" + Q + "$"), "calledTool"),
            new Rule(Pattern.compile(STEP + "(?:回答不包含|answer does not contain)\\s+" + Q + "$"), "notContains"),
            new Rule(Pattern.compile(STEP + "(?:回答包含|answer contains)\\s+" + Q + "$"), "contains"),
            new Rule(Pattern.compile(STEP + "(?:不变量|invariant)\\s+" + Q + "$"), "invariant"),
            new Rule(Pattern.compile(STEP + "(?:裁判|judge)\\s+" + Q + "$"), "judge"));

    static final Pattern NUMBER = Pattern.compile(STEP + "(?:数值|number)\\s+" + Q
            + "\\s+(?:在|between)\\s+(-?[\\d.]+)\\s+(?:到|and)\\s+(-?[\\d.]+)(?:\\s*之间)?$");
    static final Pattern SCENARIO = Pattern.compile("^(?:场景|Scenario)\\s*[:：]\\s*(.+)$");
    static final Pattern GIVEN = Pattern.compile("^(?:假如|假设|Given)\\s+(?:用户是|user)\\s+" + Q + "$");
    static final Pattern WHEN = Pattern.compile("^(?:当|When)\\s+(?:提问|ask)\\s+" + Q + "$");
    static final Pattern REPEAT = Pattern.compile("^(?:重复|repeat)\\s+(\\d+)\\s*(?:次|times)?$");

    public static List<Scenario> parse(String text) {
        List<Scenario> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        Builder current = null;
        int lineNo = 0;
        for (String raw : text.split("\\R")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.matches("^(?:功能|Feature)\\s*[:：].*")) continue;
            Matcher m;
            if ((m = SCENARIO.matcher(line)).matches()) {
                if (current != null) result.add(current.build());
                current = new Builder(m.group(1).strip(), result.size() + 1);
                continue;
            }
            if (current == null) throw new ScenarioException("第 " + lineNo + " 行：场景步骤之前要先写“场景:”");
            if ((m = GIVEN.matcher(line)).matches()) current.user = m.group(1);
            else if ((m = WHEN.matcher(line)).matches()) current.question = m.group(1);
            else if ((m = REPEAT.matcher(line)).matches()) current.repeat = Integer.parseInt(m.group(1));
            else if ((m = NUMBER.matcher(line)).matches()) {
                current.assertions.add(new Assertion(
                        "numberRange",
                        null,
                        null,
                        m.group(1),
                        Double.valueOf(m.group(2)),
                        Double.valueOf(m.group(3)),
                        null,
                        null,
                        line));
            } else if (!current.addAssertion(line)) {
                throw new ScenarioException("第 " + lineNo + " 行无法识别：" + line);
            }
        }
        if (current != null) result.add(current.build());
        return result;
    }

    private static final class Builder {
        final String name;
        final int index;
        String user;
        String question;
        int repeat = 1;
        final List<Assertion> assertions = new ArrayList<>();

        Builder(String name, int index) {
            this.name = name;
            this.index = index;
        }

        boolean addAssertion(String line) {
            for (Rule rule : ASSERTIONS) {
                Matcher m = rule.pattern().matcher(line);
                if (!m.matches()) continue;
                String v = m.group(1);
                assertions.add(
                        switch (rule.type()) {
                            case "calledTool", "notCalledTool" ->
                                new Assertion(rule.type(), v, null, null, null, null, null, null, line);
                            case "contains", "notContains" ->
                                new Assertion(rule.type(), null, v, null, null, null, null, null, line);
                            case "invariant" -> new Assertion(rule.type(), null, null, null, null, null, v, null, line);
                            default -> new Assertion(rule.type(), null, null, null, null, null, null, v, line);
                        });
                return true;
            }
            return false;
        }

        Scenario build() {
            if (user == null) throw new ScenarioException("场景“" + name + "”缺少“假如 用户是 \"…\"”");
            if (question == null) throw new ScenarioException("场景“" + name + "”缺少“当 提问 \"…\"”");
            if (assertions.isEmpty()) throw new ScenarioException("场景“" + name + "”至少要有一条“那么 …”");
            if (repeat < 1 || repeat > 20) throw new ScenarioException("场景“" + name + "”的重复次数应在 1 到 20 之间");
            return new Scenario(
                    new EvalCase("scenario-" + index + " " + name, user, question, null, List.copyOf(assertions)),
                    repeat);
        }
    }
}

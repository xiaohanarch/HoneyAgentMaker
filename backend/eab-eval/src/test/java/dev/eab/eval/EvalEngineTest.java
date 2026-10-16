package dev.eab.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.eval.Assertions.CaseRun;
import dev.eab.eval.Scenarios.ScenarioException;
import dev.eab.eval.SuiteRunner.Execution;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.model.LifecycleSpecs.Assertion;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import dev.eab.spec.model.LifecycleSpecs.EvalSuiteSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvalEngineTest {

    static final String SCENARIOS = """
            功能: 交易异常
            # 注释行
            场景: 大额异常给出责任部门
              假如 用户是 "wangfang"
              当 提问 "本月有哪些高风险交易异常"
              那么 调用了工具 "transaction_anomaly_list"
              并且 没有调用工具 "payment_execute"
              并且 回答包含 "责任部门"
              并且 回答不包含 "已退回"
              并且 不变量 "result.anomalyCount > 0"
              并且 数值 "anomalyCount" 在 1 到 50 之间
              并且 裁判 "回答引用了规则 T001-R-001"
              重复 3 次

            Scenario: english keywords
              Given user "zhangmin"
              When ask "hello"
              Then calls tool "a"
              And answer contains "b"
              And number "x.y" between 0 and 1
            """;

    static CaseRun run(String text, Map<String, Object> output, String... tools) {
        List<RunEvent> events = java.util.Arrays.stream(tools)
                .map(t -> RunEvent.of("tool_call", "agent", "tool", t))
                .toList();
        return new CaseRun(text, output, events, "completed", null);
    }

    static Assertion assertion(
            String type, String tool, String text, String path, Double min, Double max, String expr, String rubric) {
        return new Assertion(type, tool, text, path, min, max, expr, rubric, null);
    }

    @Test
    @DisplayName("中英文 Given/When/Then 场景解析成评测用例，保留重复次数")
    void parsesScenarios() {
        var scenarios = Scenarios.parse(SCENARIOS);
        assertThat(scenarios).hasSize(2);
        var first = scenarios.getFirst();
        assertThat(first.repeat()).isEqualTo(3);
        assertThat(first.evalCase().user()).isEqualTo("wangfang");
        assertThat(first.evalCase().id()).isEqualTo("scenario-1 大额异常给出责任部门");
        assertThat(first.evalCase().assertions())
                .extracting(Assertion::type)
                .containsExactly(
                        "calledTool", "notCalledTool", "contains", "notContains", "invariant", "numberRange", "judge");
        assertThat(first.evalCase().assertions().get(5).min()).isEqualTo(1.0);
        assertThat(scenarios.get(1).evalCase().assertions())
                .extracting(Assertion::type)
                .containsExactly("calledTool", "contains", "numberRange");
        assertThat(Scenarios.parse(null)).isEmpty();
        assertThat(Scenarios.parse("  ")).isEmpty();
    }

    @Test
    @DisplayName("场景写法有误时给出行号或场景名")
    void rejectsMalformedScenarios() {
        assertThatThrownBy(() -> Scenarios.parse("假如 用户是 \"a\""))
                .isInstanceOf(ScenarioException.class)
                .hasMessageContaining("第 1 行");
        assertThatThrownBy(() -> Scenarios.parse("场景: x\n假如 用户是 \"a\"\n当 提问 \"q\"\n那么 随便写写"))
                .hasMessageContaining("第 4 行无法识别");
        assertThatThrownBy(() -> Scenarios.parse("场景: x\n当 提问 \"q\"\n那么 回答包含 \"a\""))
                .hasMessageContaining("缺少“假如");
        assertThatThrownBy(() -> Scenarios.parse("场景: x\n假如 用户是 \"a\"\n那么 回答包含 \"a\""))
                .hasMessageContaining("缺少“当");
        assertThatThrownBy(() -> Scenarios.parse("场景: x\n假如 用户是 \"a\"\n当 提问 \"q\""))
                .hasMessageContaining("至少要有一条");
        assertThatThrownBy(() -> Scenarios.parse("场景: x\n假如 用户是 \"a\"\n当 提问 \"q\"\n那么 回答包含 \"a\"\n重复 30 次"))
                .hasMessageContaining("重复次数");
    }

    @Test
    @DisplayName("评测集展开：普通用例加场景，重复的场景编号后加 #n")
    void expandsSuite() {
        var plain = new EvalCase(
                "c1", "u", "q", null, List.of(assertion("contains", null, "a", null, null, null, null, null)));
        var spec = new EvalSuiteSpec("vendor.fin/x", "public", "smoke", 0.8, List.of(plain), SCENARIOS);
        var cases = SuiteRunner.expand(spec);
        assertThat(cases)
                .extracting(EvalCase::id)
                .containsExactly(
                        "c1",
                        "scenario-1 大额异常给出责任部门 #1",
                        "scenario-1 大额异常给出责任部门 #2",
                        "scenario-1 大额异常给出责任部门 #3",
                        "scenario-2 english keywords");
        var onlyScenarios = new EvalSuiteSpec("t", "public", "smoke", 1.0, null, SCENARIOS);
        assertThat(SuiteRunner.expand(onlyScenarios)).hasSize(4);
    }

    @Test
    @DisplayName("按最低通过率判定评测集；执行器抛异常的用例记为失败而不中断")
    void runsSuiteWithPassRate() {
        var ok = new EvalCase(
                "ok", "u", "q", null, List.of(assertion("contains", null, "好", null, null, null, null, null)));
        var bad = new EvalCase(
                "bad", "u", "boom", null, List.of(assertion("contains", null, "好", null, null, null, null, null)));
        var spec = new EvalSuiteSpec("t", "public", "smoke", 0.5, List.of(ok, bad), null);
        AtomicInteger calls = new AtomicInteger();
        var result = SuiteRunner.run(
                spec,
                c -> {
                    calls.incrementAndGet();
                    if (c.question().equals("boom")) throw new IllegalStateException("模型不可用");
                    return new Execution(run("很好", Map.of()), "run-1", "trace-1");
                },
                Assertions.DETERMINISTIC);
        assertThat(calls).hasValue(2);
        assertThat(result.status()).isEqualTo("passed");
        assertThat(result.passed()).isEqualTo(1);
        assertThat(result.passRate()).isEqualTo(0.5);
        assertThat(result.cases().get(1).assertions().getFirst().detail()).contains("模型不可用");
        assertThat(result.cases().getFirst().runId()).isEqualTo("run-1");

        var strict = new EvalSuiteSpec("t", "public", "smoke", 0.9, List.of(ok, bad), null);
        assertThat(SuiteRunner.run(
                                strict,
                                c -> {
                                    throw new IllegalStateException("x");
                                },
                                Assertions.DETERMINISTIC)
                        .status())
                .isEqualTo("failed");
        var empty = new EvalSuiteSpec("t", "public", "smoke", 0.0, List.of(), null);
        assertThat(SuiteRunner.run(empty, c -> null, Assertions.DETERMINISTIC).status())
                .isEqualTo("failed");
    }

    @Test
    @DisplayName("各类断言：工具调用、包含、数值区间、不变量（result/output/text/tools）")
    void checksAssertions() {
        var r = run(
                "共 3 条异常，责任部门：财务部",
                Map.of("anomalyCount", 3, "summary", Map.of("total", 9.5)),
                "transaction_anomaly_list");
        var j = Assertions.DETERMINISTIC;
        assertThat(Assertions.assertOne(
                                assertion("calledTool", "transaction_anomaly_list", null, null, null, null, null, null),
                                r,
                                j)
                        .passed())
                .isTrue();
        assertThat(Assertions.assertOne(assertion("calledTool", "x", null, null, null, null, null, null), r, j)
                        .passed())
                .isFalse();
        assertThat(Assertions.assertOne(
                                assertion("notCalledTool", "payment_execute", null, null, null, null, null, null), r, j)
                        .passed())
                .isTrue();
        assertThat(Assertions.assertOne(
                                assertion(
                                        "notCalledTool",
                                        "transaction_anomaly_list",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null),
                                r,
                                j)
                        .passed())
                .isFalse();
        assertThat(Assertions.assertOne(assertion("contains", null, "责任部门", null, null, null, null, null), r, j)
                        .passed())
                .isTrue();
        assertThat(Assertions.assertOne(assertion("notContains", null, "责任部门", null, null, null, null, null), r, j)
                        .passed())
                .isFalse();
        assertThat(Assertions.assertOne(
                                assertion("numberRange", null, null, "summary.total", 1.0, 10.0, null, null), r, j)
                        .passed())
                .isTrue();
        assertThat(Assertions.assertOne(
                                assertion("numberRange", null, null, "anomalyCount", 5.0, null, null, null), r, j)
                        .passed())
                .isFalse();
        var missing =
                Assertions.assertOne(assertion("numberRange", null, null, "nope.x", null, null, null, null), r, j);
        assertThat(missing.passed()).isFalse();
        assertThat(missing.detail()).contains("没有数值");
        assertThat(Assertions.assertOne(
                                assertion(
                                        "invariant",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "result.anomalyCount == output.anomalyCount && 'transaction_anomaly_list' in tools && text.contains('财务部')",
                                        null),
                                r,
                                j)
                        .passed())
                .isTrue();
        var broken = Assertions.assertOne(assertion("invariant", null, null, null, null, null, "result.(", null), r, j);
        assertThat(broken.passed()).isFalse();
        assertThat(broken.detail()).contains("求值失败");
        assertThat(Assertions.assertOne(assertion("unknown", null, null, null, null, null, null, null), r, j)
                        .passed())
                .isFalse();
    }

    @Test
    @DisplayName("确定性裁判核对规则编号和引号里的要点，没有要点时按通过处理")
    void deterministicJudge() {
        var j = Assertions.DETERMINISTIC;
        assertThat(j.judge("应引用 T001-R-001 并说明“责任部门”", "d", "按规则 T001-R-001，责任部门：财务部")
                        .passed())
                .isTrue();
        var miss = j.judge("应引用 T001-R-002", "d", "按规则 T001-R-001");
        assertThat(miss.passed()).isFalse();
        assertThat(miss.detail()).contains("T001-R-002");
        assertThat(j.judge("回答礼貌", "d", "x").detail()).contains("按通过处理");
    }

    @Test
    @DisplayName("运行未完成时不再逐条断言，直接判为失败；工具调用从事件里收集")
    void incompleteRunFails() {
        var c = new EvalCase(
                "c", "u", "q", null, List.of(assertion("contains", null, "a", null, null, null, null, null)));
        var failed = new CaseRun(null, null, List.of(), "failed", "超时");
        var result = Assertions.check(c, failed, null, null, Assertions.DETERMINISTIC);
        assertThat(result.passed()).isFalse();
        assertThat(result.assertions())
                .singleElement()
                .satisfies(a -> assertThat(a.detail()).contains("failed：超时"));
        var events = List.of(
                RunEvent.of("tool_call", "a", "tool", "t1"),
                RunEvent.of("text", "a", "text", "x"),
                RunEvent.of("tool_call", "a", "tool", "t1"));
        assertThat(new CaseRun("", Map.of(), events, "completed", null).calledTools())
                .containsExactly("t1");
        assertThat(Assertions.toJson(List.of(result))).contains("\"id\":\"c\"");
    }

    @Test
    @DisplayName("JSON 解析容忍代码块和前后文字，非对象返回空")
    void parsesJsonObjects() {
        assertThat(JsonObjects.parse("```json\n{\"pass\": true}\n```")).containsEntry("pass", true);
        assertThat(JsonObjects.parse("结论如下 {\"pass\": false, \"reason\": \"x\"} 完"))
                .containsEntry("reason", "x");
        assertThat(JsonObjects.parse("没有 JSON")).isNull();
        assertThat(JsonObjects.parse(null)).isNull();
    }

    @Test
    @DisplayName("结果钩子执行的工作流算调用过：钩子里的写操作被拒绝时，calledTool 断言仍然通过")
    void hookEventsCountAsCalls() {
        var run = new CaseRun(
                "回复",
                Map.of(),
                List.of(
                        RunEvent.of("tool_call", "a/b", "tool", "transaction_anomaly_list"),
                        RunEvent.of("hook", "a/b", "event", "onResult", "name", "create-followup-task")),
                "completed",
                null);
        assertThat(run.calledTools()).containsExactly("transaction_anomaly_list", "create-followup-task");
    }
}

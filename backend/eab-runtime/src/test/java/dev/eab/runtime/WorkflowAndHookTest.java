package dev.eab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.SpanRecord.SpanKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 钩子、工作流各类步骤、运行日志的序列化 */
class WorkflowAndHookTest {

    static final String ANOMALY = "vendor.fin/transaction-anomaly-analyst";

    static final String SCRIPTS = """
            scripts:
              - id: anomaly
                agent: vendor.fin/transaction-anomaly-analyst
                match: { all: [异常] }
                steps:
                  - reply: 本月有 2 条高风险异常
                    output: { severity: high, amount: 2 }
              - id: low
                agent: vendor.fin/transaction-anomaly-analyst
                match: { all: [平稳] }
                steps:
                  - reply: 本月平稳
                    output: { severity: low, amount: 0 }
            """;

    static Asset workflow(String name, List<Map<String, Object>> steps) {
        return Fixtures.build("Workflow", name, "tenant.t001.wf", Map.of("steps", steps), Fixtures.with(), Asset.class);
    }

    static Asset.AgentExtensionAsset hookExtension(String workflow) {
        return Fixtures.extension(Fixtures.with()
                .spec(
                        "hooks",
                        Map.of(
                                "onResult",
                                Map.of(
                                        "workflow",
                                        "tenant.t001.wf/" + workflow,
                                        "when",
                                        "result.severity in ['high', 'critical']"))));
    }

    static TestRuntime runtime(Asset... extra) {
        Asset[] all = new Asset[extra.length + 3];
        all[0] = Fixtures.agent();
        all[1] = Fixtures.skill();
        all[2] = Fixtures.semanticPack();
        System.arraycopy(extra, 0, all, 3, extra.length);
        return new TestRuntime(SCRIPTS, all);
    }

    @Test
    @DisplayName("onResult 钩子按 CEL 条件执行；工作流的 transform、condition、parallel、notify 依次生效，并有 span")
    void hookRunsWorkflow() {
        var steps = List.<Map<String, Object>>of(
                Map.of("id", "count", "type", "transform", "expression", "result.amount * 10", "output", "scaled"),
                Map.of(
                        "id",
                        "check",
                        "type",
                        "condition",
                        "if",
                        "scaled > 5",
                        "then",
                        List.of(Map.of(
                                "id",
                                "alert",
                                "type",
                                "notify",
                                "channel",
                                "im",
                                "message",
                                "高风险 {{result.amount}} 条，放大后 {{scaled}}")),
                        "else",
                        List.of(Map.of("id", "quiet", "type", "notify", "message", "无需处理"))),
                Map.of(
                        "id",
                        "fanout",
                        "type",
                        "parallel",
                        "branches",
                        List.of(
                                List.of(Map.of("id", "a", "type", "transform", "expression", "'甲'")),
                                List.of(Map.of("id", "b", "type", "transform", "expression", "'乙'")))));
        var rt = runtime(workflow("follow", steps));
        rt.extensions.add(hookExtension("follow"));

        var run = rt.run(ANOMALY, "本月异常情况");
        assertThat(run.result().status()).isEqualTo("completed");
        assertThat(rt.notifications).containsExactly("im:高风险 2 条，放大后 20");
        assertThat(run.result().text()).contains("已执行后续流程：tenant.t001.wf/follow");
        var spans = rt.spans(run.traceId());
        assertThat(spans).anyMatch(s -> s.kind() == SpanKind.hook);
        assertThat(spans.stream().filter(s -> s.kind() == SpanKind.workflow).map(SpanRecord::name))
                .contains("步骤 · count", "步骤 · check", "步骤 · alert", "分支 1", "分支 2");

        var quiet = rt.run(
                ANOMALY, "本月平稳吗", "on-behalf", null, new Journal(), "run-2", dev.eab.tracing.Tracer.newTraceId());
        assertThat(quiet.result().text()).isEqualTo("本月平稳");
        assertThat(rt.notifications).hasSize(1);
    }

    @Test
    @DisplayName("租户钩子失败被隔离：主流程照常完成，回复里提示后续流程没有完成")
    void tenantHookFailureIsolated() {
        var rt = runtime(workflow(
                "broken", List.of(Map.of("id", "bad", "type", "transform", "expression", "result.missing + 1"))));
        rt.extensions.add(hookExtension("broken"));
        var run = rt.run(ANOMALY, "本月异常情况");
        assertThat(run.result().status()).isEqualTo("completed");
        assertThat(run.result().text()).startsWith("本月有 2 条高风险异常").contains("租户扩展的后续流程没有完成");
    }

    @Test
    @DisplayName("工作流里的确认步骤挂起整个运行；等待事件的步骤在 P3 只挂起")
    void workflowConfirmAndWait() {
        var rt = runtime(
                workflow(
                        "confirm",
                        List.of(
                                Map.of("id", "ask", "type", "confirm", "message", "通知 {{result.amount}} 条"),
                                Map.of("id", "send", "type", "notify", "message", "已通知"))),
                workflow("wait", List.of(Map.of("id", "w", "type", "waitEvent", "event", "delivery.overdue"))));
        rt.extensions.add(hookExtension("confirm"));
        var first = rt.run(ANOMALY, "本月异常情况");
        assertThat(first.result().status()).isEqualTo("suspended");
        assertThat(first.result().pending().title()).isEqualTo("通知 2 条");

        Journal restored =
                new Journal(Journal.fromJson(Journal.toJson(first.journal().state())));
        restored.decide(true);
        var second = rt.run(ANOMALY, "本月异常情况", "on-behalf", null, restored, "run-1", first.traceId());
        assertThat(second.result().status()).isEqualTo("completed");
        assertThat(rt.notifications).containsExactly("im:已通知");

        rt.extensions.clear();
        rt.extensions.add(hookExtension("wait"));
        var waiting = rt.run(
                ANOMALY, "本月异常情况", "on-behalf", null, new Journal(), "run-3", dev.eab.tracing.Tracer.newTraceId());
        assertThat(waiting.result().status()).isEqualTo("suspended");
        assertThat(waiting.result().pending().kind()).isEqualTo("event");
    }

    @Test
    @DisplayName("运行日志可以序列化：模型结果、工具结果、脱敏映射都能原样恢复")
    void journalRoundTrip() throws Exception {
        Journal journal = new Journal();
        journal.masker().mask("账号 6222021234567890123");
        journal.record("run/turn:1/tool:c1", Map.class, () -> Map.of("total", 3));
        journal.suspend(new Journal.Pending("run/turn:2/confirm", "confirmation", "确认", Map.of("tool", "x")));
        Journal restored = new Journal(Journal.fromJson(Journal.toJson(journal.state())));
        assertThat(restored.has("run/turn:1/tool:c1")).isTrue();
        assertThat(restored.record("run/turn:1/tool:c1", Map.class, () -> Map.of("total", 0)))
                .containsEntry("total", 3);
        assertThat(restored.masker().unmask("[[MASK_1]]")).isEqualTo("6222021234567890123");
        assertThat(restored.pending().key()).isEqualTo("run/turn:2/confirm");
        restored.decide(false);
        assertThat(restored.decision("run/turn:2/confirm")).isFalse();
        assertThat(restored.pending()).isNull();
    }
}

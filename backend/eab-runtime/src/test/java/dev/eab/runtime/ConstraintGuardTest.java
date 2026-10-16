package dev.eab.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import dev.eab.tracing.SpanRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConstraintGuardTest {

    static final String NEGOTIATOR = "vendor.pur/negotiator";

    static final String SCRIPTS = """
            scripts:
              - id: negotiate
                agent: vendor.pur/negotiator
                match: { all: [谈判] }
                steps:
                  - calls: [{ tool: purchase_order_list, args: { supplierName: 百润精密 } }]
                  - reply: '{{#tools.purchase_order_list.platformConstraints}}[{{rule}}] {{remedy}}。{{/tools.purchase_order_list.platformConstraints}}谈判策略如下'
            """;

    static Asset pack(String when) {
        return Fixtures.build(
                "SemanticPack",
                "negotiation",
                "vendor.pur",
                Map.of(
                        "rules",
                        List.of(Map.of(
                                "id", "PUR-R-007",
                                "severity", "block",
                                "remedy", "先处理交付问题",
                                "check", Map.of("tool", "purchase_order_list", "when", when)))));
    }

    static Asset negotiator(List<String> constraints) {
        return Fixtures.build(
                "Agent",
                "negotiator",
                "vendor.pur",
                Map.of(
                        "expertise",
                        "制定谈判策略",
                        "instructions",
                        "制定谈判策略",
                        "publicInstructions",
                        "制定谈判策略",
                        "constraints",
                        constraints,
                        "model",
                        Map.of("profile", "reasoning"),
                        "writePolicy",
                        Map.of("maxLevel", "L0"),
                        "routing",
                        Map.of("description", "制定谈判策略", "examples", List.of("帮我准备降价谈判")),
                        "semanticPacks",
                        List.of("vendor.pur/negotiation"),
                        "tools",
                        List.of(Map.of(
                                "api",
                                Map.of(
                                        "source",
                                        "vendor.erp.tools/demo-erp-bo",
                                        "tools",
                                        List.of("purchase_order_list"))))));
    }

    static final String OVER_90 = "output.items.exists(o, o.overdueDays > 90)";

    TestRuntime runtime(List<String> constraints, long overdueDays) {
        var rt = new TestRuntime(SCRIPTS, pack(OVER_90), negotiator(constraints));
        rt.boResponder = call -> new Ports.BoResponse(
                200,
                Map.of(
                        "items",
                        List.of(Map.of("id", "PO-2026-0117", "overdueDays", overdueDays)),
                        "total",
                        1,
                        "filtered",
                        true,
                        "visibleScope",
                        List.of("华东")));
        return rt;
    }

    @Test
    @DisplayName("声明了 PUR-R-007 且有逾期超过 90 天的订单：写守卫 span、推送 constraint 事件，模型拿到规则和处置建议")
    void triggers() {
        var rt = runtime(List.of("PUR-R-007"), 120);
        var run = rt.run(NEGOTIATOR, "帮我准备和百润精密的降价谈判");

        assertThat(run.result().text()).startsWith("[PUR-R-007] 先处理交付问题。谈判策略如下");
        assertThat(run.events())
                .filteredOn(e -> e.type().equals("constraint"))
                .singleElement()
                .satisfies(e ->
                        assertThat(e.data()).containsEntry("rule", "PUR-R-007").containsEntry("severity", "block"));
        SpanRecord span = rt.spans(run.traceId()).stream()
                .filter(s -> s.name().equals("约束 · PUR-R-007 触发"))
                .findFirst()
                .orElseThrow();
        assertThat(span.kind()).isEqualTo(SpanRecord.SpanKind.guard);
        assertThat(span.attributes()).containsEntry("eab.constraint.remedy", "先处理交付问题");
    }

    @Test
    @DisplayName("没有逾期超过 90 天的订单，或 Agent 没有声明这条约束时，不触发")
    void notTriggered() {
        for (var rt : List.of(runtime(List.of("PUR-R-007"), 30), runtime(List.of(), 120))) {
            var run = rt.run(NEGOTIATOR, "帮我准备和百润精密的降价谈判");
            assertThat(run.result().text()).isEqualTo("谈判策略如下");
            assertThat(run.events()).noneMatch(e -> e.type().equals("constraint"));
            assertThat(rt.spans(run.traceId())).noneMatch(s -> s.name().startsWith("约束"));
        }
    }

    @Test
    @DisplayName("工具结果写入约束时保留原有字段；结果不是 JSON 对象时包一层")
    void annotate() {
        var notes = List.<Map<String, Object>>of(Map.of("rule", "PUR-R-007"));
        assertThat(ConstraintGuard.annotate("{\"total\":1}", notes))
                .isEqualTo("{\"total\":1,\"platformConstraints\":[{\"rule\":\"PUR-R-007\"}]}");
        assertThat(ConstraintGuard.annotate("不是 JSON", notes)).contains("\"result\":\"不是 JSON\"");
    }
}

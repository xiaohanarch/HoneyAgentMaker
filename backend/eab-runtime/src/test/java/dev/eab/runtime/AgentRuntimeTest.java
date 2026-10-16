package dev.eab.runtime;

import static dev.eab.runtime.TestRuntime.ANALYST;
import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.SpanRecord.SpanStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    static final String SCRIPTS = """
            scripts:
              - id: lookup
                agent: vendor.pur/analyst
                match: { all: [表现] }
                steps:
                  - thought: 先确认供应商
                    calls: [{ tool: supplier-lookup, args: { supplierName: 百润精密 } }]
                  - calls: [{ tool: purchase_order_list, args: { supplierName: 百润精密, overdueDaysGt: 30 } }]
                  - reply: '百润精密有 {{tools.purchase_order_list.total}} 张逾期订单：{{#tools.purchase_order_list.items}}{{id}} {{/tools.purchase_order_list.items}}'
                    output: { supplier: 百润精密 }
              - id: forever
                agent: vendor.pur/analyst
                match: { all: [一直查] }
                steps:
                  - calls: [{ tool: purchase_order_list, args: {} }]
              - id: undeclared
                agent: vendor.pur/analyst
                match: { all: [越权] }
                steps:
                  - calls: [{ tool: followup_task_create, args: { title: x, assignee: y } }]
                  - reply: '{{#tools.followup_task_create.error}}被拒绝：{{reason}}{{/tools.followup_task_create.error}}'
              - id: broken
                agent: vendor.pur/analyst
                match: { all: [报错] }
                steps:
                  - calls: [{ tool: supplier_get, args: { supplierId: S-0001 } }]
                  - reply: '查询失败：{{tools.supplier_get.error.reason}}'
              - id: followup
                agent: vendor.pur/analyst
                match: { all: [跟进任务] }
                steps:
                  - calls: [{ tool: create-followup-task, args: { title: 催交, assignee: 采购部, relatedObject: PO-2026-0117 } }]
                  - reply: '{{#tools.create_followup_task.item}}已创建 {{id}}{{/tools.create_followup_task.item}}{{#tools.create_followup_task.error}}没有创建：{{reason}}{{/tools.create_followup_task.error}}'
              - id: made-up
                agent: vendor.pur/analyst
                match: { all: [编造] }
                steps:
                  - calls: [{ tool: purchase_order_list, args: {} }]
                  - reply: 订单 PO-2026-0117 金额 520,000.00 元，另有 PO-2099-0001 金额 1,234,567 元
              - id: calc
                agent: vendor.pur/analyst
                match: { all: [算一下] }
                steps:
                  - calls: [{ tool: calculator, args: { expression: '(3 / 12) * 100' } }]
                  - reply: '逾期率 {{tools.calculator.value}}%'
              - id: boss
                agent: vendor.pur/boss
                match: { all: [汇总] }
                steps:
                  - calls: [{ tool: helper, args: { message: 查订单 } }]
                  - reply: '子 Agent 说：{{tools.helper.text}}'
              - id: helper
                agent: vendor.pur/helper
                match: { all: [查订单] }
                steps:
                  - calls: [{ tool: purchase_order_list, args: {} }]
                  - reply: 共 {{tools.purchase_order_list.total}} 张
              - id: loop
                agent: vendor.pur/loop
                match: { all: [继续] }
                steps:
                  - calls: [{ tool: loop, args: { message: 继续 } }]
                  - reply: '{{#tools.loop.error}}到此为止：{{reason}}{{/tools.loop.error}}{{#tools.loop.text}}{{tools.loop.text}}{{/tools.loop.text}}'
            """;

    static Map<String, Object> orders(String... ids) {
        return Map.of(
                "items",
                java.util.Arrays.stream(ids).map(id -> Map.of("id", id)).toList(),
                "total",
                ids.length,
                "filtered",
                true,
                "visibleScope",
                List.of("华东"));
    }

    @Nested
    @DisplayName("Agent 循环")
    class Loop {

        @Test
        @DisplayName("正常流程：技能与 BO 工具依次执行，回复基于工具结果，追踪里有组装、模型、鉴权、工具、守卫")
        void normalFlow() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder = call -> new Ports.BoResponse(
                    200,
                    call.operation().equals("purchase_order_list")
                            ? orders("PO-2026-0117")
                            : Map.of(
                                    "items",
                                    List.of(Map.of("id", "S-0001", "name", "百润精密")),
                                    "filtered",
                                    true,
                                    "visibleScope",
                                    List.of("华东")));
            var run = rt.run(ANALYST, "百润精密最近表现怎么样");

            assertThat(run.result().status()).isEqualTo("completed");
            assertThat(run.result().text()).isEqualTo("百润精密有 1 张逾期订单：PO-2026-0117");
            assertThat(run.result().output()).containsEntry("supplier", "百润精密");
            assertThat(run.types())
                    .startsWith("run_started", "step", "tool_call", "tool_result")
                    .endsWith("final");
            assertThat(run.types()).contains("text_delta");
            assertThat(rt.boCalls)
                    .extracting(Ports.BoCall::operation)
                    .containsExactly("supplier_list", "purchase_order_list");
            var po = rt.boCalls.get(1);
            assertThat(po.method()).isEqualTo("GET");
            assertThat(po.query()).containsEntry("overdueDaysGt", "30").containsEntry("supplierName", "百润精密");
            assertThat(po.agentToken()).isEqualTo("agent-token:vendor.pur/analyst@1.0.0");
            assertThat(po.subject().userToken()).isEqualTo("user-token");
            assertThat(rt.session).hasSize(1);

            var kinds = rt.spans(run.traceId()).stream().map(SpanRecord::kind).toList();
            assertThat(kinds)
                    .contains(
                            SpanKind.run,
                            SpanKind.assemble,
                            SpanKind.model,
                            SpanKind.policy,
                            SpanKind.tool,
                            SpanKind.skill,
                            SpanKind.workflow,
                            SpanKind.guard,
                            SpanKind.memory,
                            SpanKind.pipeline);
            assertThat(rt.spans(run.traceId())).allMatch(s -> s.status() == SpanStatus.ok);
        }

        @Test
        @DisplayName("轮次上限：模型一直要求调用工具时，到上限后停下并说明")
        void maxTurns() {
            var rt = new TestRuntime(SCRIPTS);
            var run = rt.run(ANALYST, "一直查");
            assertThat(run.result().status()).isEqualTo("completed");
            assertThat(run.result().text()).contains("最多 4 轮");
            assertThat(rt.boCalls).hasSize(4);
        }

        @Test
        @DisplayName("工具报错后把结构化原因交给模型，模型继续回答")
        void toolErrorContinues() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder =
                    call -> new Ports.BoResponse(404, Map.of("error", Map.of("code", "NOT_FOUND", "reason", "供应商不存在")));
            var run = rt.run(ANALYST, "报错了吗");
            assertThat(run.result().text()).isEqualTo("查询失败：供应商不存在");
            var tool = run.events().stream()
                    .filter(e -> e.type().equals("tool_result"))
                    .findFirst()
                    .orElseThrow();
            assertThat(tool.data()).containsEntry("ok", false);
        }

        @Test
        @DisplayName("没有声明的工具被鉴权点 3 拒绝（TOOL-001），不会调用 BO API")
        void undeclaredTool() {
            var rt = new TestRuntime(SCRIPTS);
            var run = rt.run(ANALYST, "越权写一下");
            assertThat(rt.boCalls).isEmpty();
            assertThat(run.result().text()).contains("被拒绝").contains("TOOL-001");
            assertThat(rt.decisions)
                    .anyMatch(d -> "TOOL-001".equals(d.ruleId()) && d.outcome().equals("deny"));
        }

        @Test
        @DisplayName("内置计算器用 CEL 精确计算")
        void calculator() {
            var rt = new TestRuntime(SCRIPTS);
            assertThat(rt.run(ANALYST, "算一下逾期率").result().text()).isEqualTo("逾期率 25%");
        }

        @Test
        @DisplayName("剧本未覆盖时回退到规则回复")
        void fallback() {
            var rt = new TestRuntime(SCRIPTS);
            var run = rt.run(ANALYST, "今天天气");
            assertThat(run.result().text()).contains("（脚本化模型）").contains("百润精密最近表现怎么样");
        }
    }

    @Nested
    @DisplayName("人工确认的挂起与恢复")
    class Confirmation {

        @Test
        @DisplayName("L2 技能先挂起；序列化后恢复，确认后写入一次，技能内部不重复询问，已推送的事件不再重复")
        void approve() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder = call -> new Ports.BoResponse(201, Map.of("item", Map.of("id", "TASK-2026-0001")));
            var first = rt.run(ANALYST, "为 PO-2026-0117 建个跟进任务");
            assertThat(first.result().status()).isEqualTo("suspended");
            assertThat(first.types())
                    .contains("confirmation_required", "suspended")
                    .doesNotContain("final");
            var pending = first.result().pending();
            assertThat(pending.title()).contains("create-followup-task");
            assertThat(rt.boCalls).isEmpty();
            assertThat(rt.spans(first.traceId())).anyMatch(s -> s.status() == SpanStatus.suspended);

            var second = rt.resume(first, "为 PO-2026-0117 建个跟进任务", true);
            assertThat(second.result().status()).isEqualTo("completed");
            assertThat(second.result().text()).isEqualTo("已创建 TASK-2026-0001");
            assertThat(rt.boCalls).hasSize(1);
            var write = rt.boCalls.getFirst();
            assertThat(write.method()).isEqualTo("POST");
            assertThat(write.body())
                    .containsEntry("title", "催交")
                    .containsEntry("relatedObject", "PO-2026-0117")
                    .doesNotContainKey("dueDate");
            assertThat(write.idempotencyKey()).startsWith("eab-");
            assertThat(second.types()).doesNotContain("confirmation_required").contains("tool_result", "final");
            assertThat(rt.spans(first.traceId())).noneMatch(s -> s.status() == SpanStatus.suspended);
            assertThat(rt.spans(first.traceId()))
                    .filteredOn(s -> s.kind() == SpanKind.confirmation)
                    .allMatch(s -> "approved".equals(s.attributes().get("eab.confirmation.decision")));
        }

        @Test
        @DisplayName("用户拒绝时把“用户拒绝”作为工具结果交给模型，没有写入")
        void reject() {
            var rt = new TestRuntime(SCRIPTS);
            var first = rt.run(ANALYST, "为 PO-2026-0117 建个跟进任务");
            var second = rt.resume(first, "为 PO-2026-0117 建个跟进任务", false);
            assertThat(second.result().status()).isEqualTo("completed");
            assertThat(second.result().text()).startsWith("没有创建：用户拒绝执行 create-followup-task");
            assertThat(rt.boCalls).isEmpty();
        }

        @Test
        @DisplayName("受托自主运行不做需要确认的写操作（TOOL-007），也不会挂起")
        void delegatedWrite() {
            var rt = new TestRuntime(SCRIPTS);
            rt.delegationScope = List.of("create-followup-task", "followup_task_create");
            var run = rt.run(
                    ANALYST,
                    "为 PO-2026-0117 建个跟进任务",
                    "delegated",
                    "DLG-0001",
                    new Journal(),
                    "run-2",
                    dev.eab.tracing.Tracer.newTraceId());
            assertThat(run.result().status()).isEqualTo("completed");
            assertThat(run.result().text()).contains("TOOL-007");
            assertThat(rt.boCalls).isEmpty();
        }
    }

    @Nested
    @DisplayName("子 Agent")
    class SubAgents {

        @Test
        @DisplayName("先过鉴权点 2，再递归执行，子 Agent 的事件和 span 挂在父运行下")
        void subAgent() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder = call -> new Ports.BoResponse(200, orders("PO-1", "PO-2"));
            var run = rt.run("vendor.pur/boss", "汇总一下");
            assertThat(run.result().text()).isEqualTo("子 Agent 说：共 2 张");
            assertThat(rt.decisions)
                    .anyMatch(
                            d -> d.checkpoint().equals("handoff") && d.outcome().equals("allow"));
            assertThat(rt.boCalls.getFirst().agentToken()).isEqualTo("agent-token:vendor.pur/helper@1.0.0");
            assertThat(run.events()).extracting(RunEvent::agent).contains("vendor.pur/helper");
            var spans = rt.spans(run.traceId());
            assertThat(spans).anyMatch(s -> s.kind() == SpanKind.agent);
            assertThat(spans.stream().map(SpanRecord::traceId).distinct()).hasSize(1);
        }

        @Test
        @DisplayName("调用深度超过 3 层时被鉴权点 2 拒绝（HANDOFF-003）")
        void depthLimit() {
            var rt = new TestRuntime(SCRIPTS);
            var run = rt.run("vendor.pur/loop", "继续");
            assertThat(run.result().status()).isEqualTo("completed");
            assertThat(rt.decisions).anyMatch(d -> "HANDOFF-003".equals(d.ruleId()));
            assertThat(run.result().text()).contains("到此为止");
        }
    }

    @Nested
    @DisplayName("监督层")
    class Supervision {

        @Test
        @DisplayName("输出守卫：工具结果里没有的单号和金额标注“未经核实”")
        void outputGuard() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder = call -> new Ports.BoResponse(
                    200, Map.of("items", List.of(Map.of("id", "PO-2026-0117", "totalAmount", 520000)), "total", 1));
            var run = rt.run(ANALYST, "编造一下");
            assertThat(run.result().unverified()).containsExactlyInAnyOrder("PO-2099-0001", "1,234,567");
            assertThat(run.result().text())
                    .contains("PO-2099-0001（未经核实）")
                    .contains("1,234,567（未经核实）")
                    .doesNotContain("PO-2026-0117（未经核实）")
                    .doesNotContain("520,000.00（未经核实）");
        }

        @Test
        @DisplayName("同一会话写操作过多时告警")
        void writeBurst() {
            var rt = new TestRuntime(SCRIPTS);
            rt.writes.addAll(List.of("a", "b", "c", "d", "e"));
            var first = rt.run(ANALYST, "为 PO-2026-0117 建个跟进任务");
            rt.resume(first, "为 PO-2026-0117 建个跟进任务", true);
            assertThat(rt.alerts).contains("write_burst");
        }

        @Test
        @DisplayName("数据范围在运行中扩大时告警")
        void scopeExpansion() {
            var rt = new TestRuntime(SCRIPTS);
            rt.boResponder = call -> new Ports.BoResponse(
                    200,
                    call.operation().equals("supplier_list")
                            ? Map.of("items", List.of(), "filtered", true, "visibleScope", List.of("华东"))
                            : Map.of("items", List.of(), "total", 0, "visibleScope", List.of("华东", "华南")));
            rt.run(ANALYST, "百润精密最近表现怎么样");
            assertThat(rt.alerts).contains("scope_expansion");
        }

        @Test
        @DisplayName("引用核对：日期、年份和单号里的数字不算金额")
        void referenceCheck() {
            var check = Supervisor.checkReferences("2026-06-17 计划交付，2026 年第 3 季度，PO-2026-0117 共 3 行", "PO-2026-0117");
            assertThat(check.unverified()).isEmpty();
        }
    }
}

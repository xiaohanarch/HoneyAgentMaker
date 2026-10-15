package dev.eab.policy;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.assembler.Assembler;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.policy.PolicyInputs.Assistant;
import dev.eab.policy.PolicyInputs.Caller;
import dev.eab.policy.PolicyInputs.Delegation;
import dev.eab.policy.PolicyInputs.Document;
import dev.eab.policy.PolicyInputs.HandoffTarget;
import dev.eab.policy.PolicyInputs.SubjectFacts;
import dev.eab.policy.PolicyInputs.ToolContext;
import dev.eab.spec.InMemoryAssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

    static final SubjectFacts ZHANGMIN = new SubjectFacts(
            "zhangmin", "t001", List.of("purchasing-manager"), List.of("supplier_list", "purchase_order_list"));

    static EffectiveDefinition definition() {
        var boSource = Fixtures.toolSource(with().name("demo-erp-bo")
                .namespace("vendor.erp.tools")
                .spec("type", "bo-api")
                .spec("connection", "DEMO_ERP")
                .spec(
                        "tools",
                        List.of(
                                Map.of(
                                        "name",
                                        "purchase_order_list",
                                        "description",
                                        "查订单",
                                        "writeLevel",
                                        "L0",
                                        "category",
                                        "query"),
                                Map.of(
                                        "name",
                                        "purchase_order_change_approve",
                                        "description",
                                        "审批变更",
                                        "writeLevel",
                                        "L2",
                                        "category",
                                        "write"),
                                Map.of(
                                        "name",
                                        "auto_post",
                                        "description",
                                        "自动过账",
                                        "writeLevel",
                                        "L3",
                                        "category",
                                        "posting"),
                                Map.of(
                                        "name",
                                        "auto_tag",
                                        "description",
                                        "打标签",
                                        "writeLevel",
                                        "L3",
                                        "category",
                                        "write"))));
        var agent = Fixtures.agent(with().spec("writePolicy", Map.of("maxLevel", "L3"))
                .spec(
                        "tools",
                        List.of(
                                Map.of("api", Map.of("source", "vendor.erp.tools/demo-erp-bo")),
                                Map.of("skill", "vendor.fin.skills/invoice-lookup")))
                .spec(
                        "approvals",
                        List.of(Map.of(
                                "tool", "invoice-lookup",
                                "policy", "require-approval",
                                "when", "input.amount > 100000.0",
                                "approverRole", "财务经理"))));
        var ext = Fixtures.extension(with().spec("tools", Map.of("add", List.of("tenant.t001.tools/notify-im")))
                .spec(
                        "approvals",
                        Map.of(
                                "add",
                                List.of(Map.of(
                                        "tool", "tenant.t001.tools/notify-im", "policy", "require-confirmation")))));
        return Assembler.assemble(new Assembler.Input(
                        "vendor.fin/transaction-anomaly-analyst",
                        null,
                        List.of(ext),
                        List.of(Fixtures.platformRules()),
                        InMemoryAssetResolver.published(
                                agent, Fixtures.semanticPack(), Fixtures.skill(), Fixtures.toolSource(), boSource),
                        null))
                .definition();
    }

    static final EffectiveDefinition DEF = definition();

    static Decision tool(
            String toolName,
            String mode,
            Map<String, Object> toolInput,
            boolean l3,
            Delegation delegation,
            Document document) {
        return PolicyEngine.evaluate(
                PolicyEngine.DEFAULT_RULES,
                "tool",
                PolicyInputs.tool(new ToolContext(ZHANGMIN, mode, DEF, toolName, toolInput, l3, delegation, document)));
    }

    static Decision tool(String toolName) {
        return tool(toolName, "on-behalf", null, false, null, null);
    }

    @Test
    @DisplayName("默认规则集加载时校验通过；条件写错的规则集拒绝加载")
    void ruleSetLoading() {
        assertThat(PolicyEngine.DEFAULT_RULES.rules()).hasSizeGreaterThanOrEqualTo(15);
        var bad = Json.MAPPER.valueToTree(Map.of(
                "version",
                "x",
                "rules",
                List.of(Map.of(
                        "id",
                        "TOOL-999",
                        "checkpoint",
                        "tool",
                        "description",
                        "d",
                        "effect",
                        "deny",
                        "when",
                        "input.a ==",
                        "reason",
                        "r"))));
        assertThatThrownBy(() -> PolicyEngine.load((tools.jackson.databind.JsonNode) bad))
                .hasMessageContaining("条件无效");
    }

    @Nested
    @DisplayName("鉴权点 1：入口")
    class Entry {
        Decision entry(SubjectFacts subject, Assistant assistant) {
            return PolicyEngine.evaluate(
                    PolicyEngine.DEFAULT_RULES, "entry", PolicyInputs.entry(subject, "t001", assistant));
        }

        @Test
        @DisplayName("本租户有业务权限的用户允许使用；其他租户的用户被拒绝，原因里带用户编号")
        void tenantMembership() {
            assertThat(entry(ZHANGMIN, null).outcome()).isEqualTo("allow");
            var decision = entry(new SubjectFacts("zhouting", "t002", ZHANGMIN.roles(), ZHANGMIN.operations()), null);
            assertThat(decision.outcome()).isEqualTo("deny");
            assertThat(decision.ruleId()).isEqualTo("ENTRY-001");
            assertThat(decision.reason()).isEqualTo("用户 zhouting 不属于租户 t001");
        }

        @Test
        @DisplayName("没有业务权限的用户被拒绝；岗位助手只对指定角色开放")
        void rolesAndAssistants() {
            assertThat(entry(new SubjectFacts("chengong", "t001", List.of("tenant-developer"), List.of()), null)
                            .ruleId())
                    .isEqualTo("ENTRY-002");
            assertThat(entry(ZHANGMIN, new Assistant("vendor.fin/finance-assistant", List.of("finance-manager")))
                            .ruleId())
                    .isEqualTo("ENTRY-003");
            assertThat(entry(ZHANGMIN, new Assistant("vendor.pur/procurement-assistant", List.of("purchasing-manager")))
                            .outcome())
                    .isEqualTo("allow");
        }
    }

    @Nested
    @DisplayName("鉴权点 2：Agent 交接")
    class Handoff {
        Decision handoff(Caller caller, String kind, boolean enabled) {
            return PolicyEngine.evaluate(
                    PolicyEngine.DEFAULT_RULES,
                    "handoff",
                    PolicyInputs.handoff(
                            ZHANGMIN,
                            caller,
                            new HandoffTarget(
                                    "tenant.t001.remote/supplier-risk",
                                    kind,
                                    enabled,
                                    List.of("tenant.t001.pur/price-negotiation"))));
        }

        @Test
        @DisplayName("登记过的调用方可以调用远程 Agent")
        void registeredCaller() {
            assertThat(handoff(new Caller("agent", "tenant.t001.pur/price-negotiation", 1), "RemoteAgent", true)
                            .outcome())
                    .isEqualTo("allow");
        }

        @Test
        @DisplayName("未登记的调用方、未启用的 Agent、过深的调用链、无身份的入站调用都被拒绝")
        void rejected() {
            assertThat(handoff(new Caller("agent", "vendor.pur/supplier-performance-analyst", 1), "RemoteAgent", true)
                            .ruleId())
                    .isEqualTo("HANDOFF-002");
            assertThat(handoff(new Caller("router", "router", 0), "RemoteAgent", false)
                            .ruleId())
                    .isEqualTo("HANDOFF-001");
            assertThat(handoff(new Caller("agent", "tenant.t001.pur/price-negotiation", 4), "RemoteAgent", true)
                            .ruleId())
                    .isEqualTo("HANDOFF-003");
            assertThat(handoff(new Caller("external", "", 0), "Agent", true).ruleId())
                    .isEqualTo("HANDOFF-004");
        }
    }

    @Nested
    @DisplayName("鉴权点 3：工具")
    class Tool {
        @Test
        @DisplayName("声明过的只读工具直接允许；未声明的工具被拒绝；集成账号模式被拒绝")
        void basics() {
            assertThat(tool("purchase_order_list").outcome()).isEqualTo("allow");
            var undeclared = tool("wire_transfer");
            assertThat(undeclared.outcome()).isEqualTo("deny");
            assertThat(undeclared.ruleId()).isEqualTo("TOOL-001");
            assertThat(tool("purchase_order_list", "integration", null, false, null, null)
                            .ruleId())
                    .isEqualTo("TOOL-002");
        }

        @Test
        @DisplayName("过账类工具不能 L3 自主执行；其他 L3 工具需要租户开启")
        void l3() {
            assertThat(tool("auto_post", "on-behalf", null, true, null, null).ruleId())
                    .isEqualTo("TOOL-003");
            assertThat(tool("auto_tag").ruleId()).isEqualTo("TOOL-004");
            assertThat(tool("auto_tag", "on-behalf", null, true, null, null).outcome())
                    .isEqualTo("allow");
        }

        @Test
        @DisplayName("职责分离：审批自己提交的单据被拒绝，审批他人提交的需要确认")
        void segregationOfDuties() {
            var own = tool(
                    "purchase_order_change_approve",
                    "on-behalf",
                    null,
                    false,
                    null,
                    new Document("DC-1", "approve", "zhangmin"));
            assertThat(own.outcome()).isEqualTo("deny");
            assertThat(own.ruleId()).isEqualTo("TOOL-005");
            assertThat(own.reason()).contains("职责分离");
            var others = tool(
                    "purchase_order_change_approve",
                    "on-behalf",
                    null,
                    false,
                    null,
                    new Document("DC-1", "approve", "liqiang"));
            assertThat(others.outcome()).isEqualTo("confirm");
            assertThat(others.ruleId()).isEqualTo("TOOL-010");
        }

        @Test
        @DisplayName("受托运行只能用委托范围内的工具，且不做 L2 写")
        void delegated() {
            var delegation = new Delegation("DLG-1", List.of("purchase_order_list"));
            assertThat(tool("purchase_order_list", "delegated", null, false, delegation, null)
                            .outcome())
                    .isEqualTo("allow");
            assertThat(tool("auto_tag", "delegated", null, true, delegation, null)
                            .ruleId())
                    .isEqualTo("TOOL-006");
            assertThat(tool(
                                    "purchase_order_change_approve",
                                    "delegated",
                                    null,
                                    false,
                                    new Delegation("DLG-1", List.of("purchase_order_change_approve")),
                                    null)
                            .ruleId())
                    .isEqualTo("TOOL-007");
        }

        @Test
        @DisplayName("扩展声明的确认点生效，L2 与确认点同时命中时都列出")
        void confirmations() {
            var decision = tool("notify_im");
            assertThat(decision.outcome()).isEqualTo("confirm");
            assertThat(decision.requirements().stream().map(r -> r.ruleId())).containsExactly("TOOL-010", "TOOL-011");
        }

        @Test
        @DisplayName("带条件的审批点只在条件成立时生效，审批优先于确认")
        void conditionalApproval() {
            assertThat(tool("invoice-lookup", "on-behalf", Map.of("amount", 5000), false, null, null)
                            .outcome())
                    .isEqualTo("allow");
            var big = tool("invoice-lookup", "on-behalf", Map.of("amount", 200000), false, null, null);
            assertThat(big.outcome()).isEqualTo("approve");
            assertThat(big.ruleId()).isEqualTo("TOOL-012");
            assertThat(big.reason()).contains("财务经理");
        }

        @Test
        @DisplayName("规则求值出错时拒绝（失败即拒绝）")
        void failClosed() {
            var rules = PolicyEngine.load(Json.MAPPER.valueToTree(Map.of(
                    "version",
                    "x",
                    "rules",
                    List.of(Map.of(
                            "id",
                            "TOOL-900",
                            "checkpoint",
                            "tool",
                            "description",
                            "引用不存在的事实",
                            "effect",
                            "confirm",
                            "when",
                            "input.nope.deeper == 1",
                            "reason",
                            "r")))));
            var decision = PolicyEngine.evaluate(rules, "tool", Map.of("subject", Map.of()));
            assertThat(decision.outcome()).isEqualTo("deny");
            assertThat(decision.ruleId()).isEqualTo("TOOL-900");
            assertThat(decision.reason()).contains("失败即拒绝");
        }

        @Test
        @DisplayName("裁决记录带规则集版本、时间和判定依据")
        void decisionRecord() {
            var decision = tool("wire_transfer");
            assertThat(decision.rulesVersion()).isEqualTo(PolicyEngine.DEFAULT_RULES.version());
            assertThat(decision.at()).matches("^\\d{4}-\\d{2}-\\d{2}T.*");
            @SuppressWarnings("unchecked")
            var toolFacts = (Map<String, Object>) decision.facts().get("tool");
            assertThat(toolFacts).containsEntry("name", "wire_transfer").containsEntry("declared", false);
        }
    }
}

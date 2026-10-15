package dev.eab.assembler;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.assembler.Conditions.CheckOutcome;
import dev.eab.assembler.EffectiveDefinition.EffectiveHook;
import dev.eab.spec.InMemoryAssetResolver;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CapabilitiesAndHooksTest {

    static final Asset REMOTE_AGENT = Fixtures.build("RemoteAgent", "supplier-risk", "tenant.t001.remote", Map.of(
            "connection", "SUPPLIER_RISK_AGENT",
            "auth", Map.of("type", "platform-service-token"),
            "routing", Map.of("description", "查询供应商外部舆情风险")));
    static final Asset KNOWLEDGE = Fixtures.build("KnowledgeSource", "negotiation-playbook", "tenant.t001.kb",
            Map.of("documents", List.of(Map.of("title", "降价谈判制度", "path", "docs/playbook.md"))));

    static InMemoryAssetResolver resolver(Asset... extra) {
        List<Asset> list = new ArrayList<>(List.of(
                Fixtures.agent(), Fixtures.semanticPack(), Fixtures.skill(), Fixtures.toolSource(), Fixtures.workflow()));
        list.addAll(List.of(extra));
        return InMemoryAssetResolver.published(list.toArray(Asset[]::new));
    }

    static Assembler.Result assemble(Asset.AgentExtensionAsset ext, InMemoryAssetResolver resolver) {
        return Assembler.assemble(new Assembler.Input(
                "vendor.fin/transaction-anomaly-analyst", null, List.of(ext), null, resolver, null));
    }

    @Test
    @DisplayName("扩展里直接写引用时按被引用资产的 kind 归一化：技能、Agent、远程 Agent、知识源、MCP 工具来源都能挂载")
    void shorthandRefsNormalize() {
        var helperAgent = Fixtures.agent(with().name("helper-agent").namespace("tenant.t001.agents"));
        var tenantSkill = Fixtures.skill(with().name("vendor-check").namespace("tenant.t001.skills").spec("writeLevel", "L1"));
        var mcpSource = Fixtures.toolSource(with().name("logistics-mcp")
                .spec("type", "mcp")
                .spec("connection", "LOGISTICS_MCP")
                .spec("tools", List.of(Map.of("name", "track_shipment", "description", "物流在途查询", "writeLevel", "L0"))));
        var ext = Fixtures.extension(with().spec("tools", Map.of("add", List.of(
                "tenant.t001.skills/vendor-check",
                "tenant.t001.agents/helper-agent",
                "tenant.t001.remote/supplier-risk",
                "tenant.t001.kb/negotiation-playbook",
                "tenant.t001.tools/logistics-mcp"))));
        var result = assemble(ext, resolver(REMOTE_AGENT, KNOWLEDGE, helperAgent, tenantSkill, mcpSource));
        assertThat(result.issues()).isEmpty();
        assertThat(result.definition().tools().stream()
                        .filter(t -> t.layer() == Layer.tenant)
                        .map(t -> t.type() + ":" + t.name()))
                .containsExactly(
                        "skill:vendor-check",
                        "agent:helper-agent",
                        "remoteAgent:supplier-risk",
                        "documents:kb_negotiation_playbook",
                        "mcp:track_shipment");
    }

    @Test
    @DisplayName("引用的资产不是可挂载的 kind，或 kind 与条目类型不符时报错")
    void badShorthandRefs() {
        var ext = Fixtures.extension(with().spec("tools", Map.of("add", List.of(
                "vendor.fin/finance",
                Map.of("agent", "tenant.t001.remote/supplier-risk"),
                Map.of("builtin", "teleport")))));
        assertThat(assemble(ext, resolver(REMOTE_AGENT)).issues().stream().map(AssemblyIssue::code))
                .containsExactly("REFERENCE_UNRESOLVED", "REFERENCE_UNRESOLVED", "REFERENCE_UNRESOLVED");
    }

    @Test
    @DisplayName("onFailure 可以引用 error，工具调用事件可以引用 tool、input、output")
    void hookEnvironments() {
        assertThat(Conditions.check(Conditions.hookEnvironment("onFailure", null), "error.code == 'TIMEOUT'").valid())
                .isTrue();
        assertThat(Conditions.check(
                                Conditions.hookEnvironment("beforeToolCall", null), "tool == 'notify_im' && input.to != ''")
                        .valid())
                .isTrue();
        assertThat(Conditions.check(Conditions.hookEnvironment("afterToolCall", null), "result.x == 1").valid())
                .isFalse();
    }

    @Test
    @DisplayName("没有声明输出结构时 result 按 map 处理，条件结果必须是布尔值")
    void resultMapAndBoolType() {
        assertThat(Conditions.check(Conditions.hookEnvironment("onResult", null), "result.anything == 'x'").valid())
                .isTrue();
        var output = new AgentSpec.Output(Map.of("amount", new AgentSpec.OutputField("double", null, null)));
        CheckOutcome nonBool = Conditions.check(Conditions.hookEnvironment("onResult", output), "result.amount + 1.0");
        assertThat(nonBool).isEqualTo(new CheckOutcome.Invalid("条件的结果类型是 double，应为 bool"));
    }

    @Test
    @DisplayName("求值：整数与小数混合比较、map 字面量、缺失字段用 has 判断")
    void evaluation() {
        assertThat(Conditions.test("input.amount > 100000", Map.of("input", Map.of("amount", 520000.5)))).isTrue();
        assertThat(Conditions.test("input.amount > 100000", Map.of("input", Fixtures.tree(Map.of("amount", 3))))).isFalse();
        assertThat(Conditions.evaluate("{'n': items.size(), 'first': items[0]}", Map.of("items", List.of("a", "b"))))
                .isEqualTo(Map.of("n", 2L, "first", "a"));
        assertThat(Conditions.test("has(input.missing)", Map.of("input", Map.of()))).isFalse();
    }

    // ── 钩子执行 ───────────────────────────────────────────────────────────

    static EffectiveHook hook(String source, Layer layer, String failurePolicy, String event, String when) {
        return new EffectiveHook(event, layer, source, null, "vendor.fin.skills/x", when, failurePolicy);
    }

    static final EffectiveHook VENDOR_HOOK = hook("vendor", Layer.vendor, "propagate", "onResult", null);
    static final EffectiveHook TENANT_HOOK = hook("tenant-a", Layer.tenant, "isolate", "onResult", null);
    static final EffectiveHook TENANT_HOOK_2 = hook("tenant-b", Layer.tenant, "isolate", "onResult", null);

    static Hooks.HookRunner failingOn(Set<String> failing, List<String> order) {
        return new Hooks.HookRunner() {
            @Override
            public boolean shouldRun(EffectiveHook hook) {
                return true;
            }

            @Override
            public void run(EffectiveHook hook) {
                order.add(hook.source());
                if (failing.contains(hook.source())) throw new IllegalStateException(hook.source() + " 失败");
            }
        };
    }

    @Test
    @DisplayName("按顺序执行，只执行指定事件的钩子")
    void runsInOrder() throws Exception {
        List<String> order = new ArrayList<>();
        var other = hook("failure-hook", Layer.vendor, "propagate", "onFailure", null);
        var report = Hooks.run(List.of(VENDOR_HOOK, other, TENANT_HOOK), "onResult", failingOn(Set.of(), order));
        assertThat(order).containsExactly("vendor", "tenant-a");
        assertThat(report.executed()).hasSize(2);
    }

    @Test
    @DisplayName("租户钩子失败被隔离，后续钩子继续执行，失败记录在报告里")
    void tenantFailureIsolated() throws Exception {
        List<String> order = new ArrayList<>();
        var report = Hooks.run(
                List.of(VENDOR_HOOK, TENANT_HOOK, TENANT_HOOK_2), "onResult", failingOn(Set.of("tenant-a"), order));
        assertThat(order).containsExactly("vendor", "tenant-a", "tenant-b");
        assertThat(report.isolatedFailures()).containsExactly(new Hooks.HookFailure(TENANT_HOOK, "tenant-a 失败"));
        assertThat(report.executed().stream().map(EffectiveHook::source)).containsExactly("vendor", "tenant-b");
    }

    @Test
    @DisplayName("标品钩子失败向上抛出，中止主流程")
    void vendorFailurePropagates() {
        assertThatThrownBy(() -> Hooks.run(
                        List.of(VENDOR_HOOK, TENANT_HOOK), "onResult", failingOn(Set.of("vendor"), new ArrayList<>())))
                .hasMessage("vendor 失败");
    }

    @Test
    @DisplayName("条件不满足的钩子跳过，没有条件的钩子不询问 shouldRun")
    void conditionalHooks() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        var conditional = hook("conditional", Layer.vendor, "propagate", "onResult", "result.severity == 'high'");
        var report = Hooks.run(List.of(VENDOR_HOOK, conditional), "onResult", new Hooks.HookRunner() {
            @Override
            public boolean shouldRun(EffectiveHook hook) {
                asked.incrementAndGet();
                return false;
            }

            @Override
            public void run(EffectiveHook hook) {}
        });
        assertThat(asked.get()).isEqualTo(1);
        assertThat(report.skipped()).containsExactly(conditional);
        assertThat(report.executed()).containsExactly(VENDOR_HOOK);
    }
}

package dev.eab.assembler;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.assembler.Assembler.Result;
import dev.eab.assembler.EffectiveDefinition.ResolvedTarget;
import dev.eab.assembler.EffectiveDefinition.Segment;
import dev.eab.assembler.EffectiveDefinition.SegmentKind;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.InMemoryAssetResolver;
import dev.eab.spec.InMemoryAssetResolver.Entry;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.testing.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AssemblerTest {

    static final String AGENT = "vendor.fin/transaction-anomaly-analyst";
    static final String TARGET = "vendor.fin/transaction-anomaly-analyst@^1.0";

    static List<Asset> base() {
        return List.of(
                Fixtures.agent(with().version("1.2.0")),
                Fixtures.semanticPack(),
                Fixtures.skill(),
                Fixtures.toolSource(),
                Fixtures.workflow());
    }

    static List<Asset> baseWithAgent(Asset agent) {
        List<Asset> list = new ArrayList<>(base().subList(1, 5));
        list.addFirst(agent);
        return list;
    }

    static List<Asset> plus(List<Asset> assets, Asset... extra) {
        List<Asset> list = new ArrayList<>(assets);
        list.addAll(List.of(extra));
        return list;
    }

    static InMemoryAssetResolver resolverOf(List<Asset> assets, Map<Asset, Status> statuses) {
        return new InMemoryAssetResolver(assets.stream()
                .map(a -> new Entry(a, statuses.getOrDefault(a, Status.published)))
                .toList());
    }

    /** 默认参数与 TS 测试的 run() 一致：租户 t001、带平台规则、只用已发布版本 */
    static final class Run {
        String agent = AGENT;
        List<AgentExtensionAsset> extensions = List.of();
        List<Asset> assets = base();
        Map<Asset, Status> statuses = Map.of();
        List<Status> resolve = null;

        Run agent(String value) {
            agent = value;
            return this;
        }

        Run extensions(AgentExtensionAsset... value) {
            extensions = List.of(value);
            return this;
        }

        Run assets(List<Asset> value) {
            assets = value;
            return this;
        }

        Run statuses(Map<Asset, Status> value) {
            statuses = value;
            return this;
        }

        Run resolve(List<Status> value) {
            resolve = value;
            return this;
        }

        Result go() {
            return Assembler.assemble(new Assembler.Input(
                    agent, "t001", extensions, List.of(Fixtures.platformRules()), resolverOf(assets, statuses), resolve));
        }
    }

    static Run run() {
        return new Run();
    }

    static List<String> codes(Result result) {
        return result.issues().stream().map(AssemblyIssue::code).toList();
    }

    static AgentExtensionAsset ext(Map<String, Object> spec) {
        var options = with().spec("target", TARGET);
        spec.forEach(options::spec);
        return Fixtures.extension(options);
    }

    @Nested
    @DisplayName("规则 1：指令按固定顺序拼接，每段带来源")
    class Rule1 {
        @Test
        @DisplayName("平台规则 → 标品指令 → 标品约束 → 语义包指令 → 租户追加指令 → 租户追加约束")
        void segmentOrder() {
            var extension = ext(Map.of(
                    "instructions", Map.of("append", "单笔超 50 万须给出责任部门"),
                    "constraints", Map.of("append", List.of("结论里不写客户姓名"))));
            var definition = run().extensions(extension).go().definition();
            assertThat(definition.segments().stream().map(s -> s.kind().value())).containsExactly(
                    "platform-rule",
                    "vendor-instructions",
                    "vendor-constraint",
                    "vendor-constraint",
                    "semantic-instruction",
                    "tenant-instructions",
                    "tenant-constraint");
            assertThat(definition.segments().stream().map(s -> s.layer().name()))
                    .containsExactly("platform", "vendor", "vendor", "vendor", "vendor", "tenant", "tenant");
            assertThat(definition.segments().get(1).source()).isEqualTo("vendor.fin/transaction-anomaly-analyst@1.2.0");
            assertThat(definition.segments().get(5).source()).isEqualTo("tenant.t001.finance/anomaly-analyst-ext@0.3.0");
        }

        @Test
        @DisplayName("规则编号约束展开为语义包里的处置建议，并且对租户可见")
        void ruleIdConstraintExpands() {
            Segment rule = run().go().definition().segments().stream()
                    .filter(s -> "FIN-R-001".equals(s.label()))
                    .findFirst()
                    .orElseThrow();
            assertThat(rule.text()).isEqualTo("[FIN-R-001] 金额必须带币种");
            assertThat(rule.confidential()).isFalse();
        }

        @Test
        @DisplayName("约束引用的规则在语义包里找不到时组装失败")
        void unknownRuleFails() {
            var agent = Fixtures.agent(with().version("1.2.0").spec("constraints", List.of("FIN-R-999")));
            Result result = run().assets(baseWithAgent(agent)).go();
            assertThat(result.ok()).isFalse();
            assertThat(codes(result)).contains("REFERENCE_UNRESOLVED");
        }

        @Test
        @DisplayName("renderInstructions 输出带来源标记的系统提示词")
        void renderInstructions() {
            String text = DefinitionView.renderInstructions(run().go().definition());
            assertThat(text.indexOf("【平台机密】")).isLessThan(text.indexOf(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS));
            assertThat(text)
                    .contains("<!-- vendor-instructions vendor.fin/transaction-anomaly-analyst@1.2.0 spec.instructions -->");
        }
    }

    @Nested
    @DisplayName("规则 2：工具取并集，租户工具名冲突时拒绝")
    class Rule2 {
        @Test
        @DisplayName("标品工具、语义查询工具、租户新增工具合并")
        void toolsMerge() {
            var definition = run().extensions(ext(Map.of("tools", Map.of("add", List.of("tenant.t001.tools/notify-im")))))
                    .go()
                    .definition();
            var names = definition.tools().stream().map(t -> t.layer().name() + ":" + t.name()).toList();
            assertThat(names).contains("vendor:invoice-lookup", "platform:semantic.describe_object", "tenant:notify_im");
            assertThat(definition.tools().stream().filter(t -> t.name().equals("notify_im")).findFirst().orElseThrow()
                            .writeLevel())
                    .isEqualTo("L2");
        }

        @Test
        @DisplayName("租户新增的工具与标品工具重名时报 TOOL_NAME_CONFLICT")
        void nameConflict() {
            var tenantSkill = Fixtures.skill(with().namespace("tenant.t001.skills"));
            Result result = run().extensions(ext(Map.of("skills", Map.of("add", List.of("tenant.t001.skills/invoice-lookup")))))
                    .assets(plus(base(), tenantSkill))
                    .go();
            assertThat(result.ok()).isFalse();
            assertThat(codes(result)).containsExactly("TOOL_NAME_CONFLICT");
        }

        @Test
        @DisplayName("引用不存在的工具来源或工具时报错")
        void unresolvedTools() {
            var extension = ext(Map.of(
                    "tools",
                    Map.of(
                            "add",
                            List.of(
                                    Map.of("api", Map.of("source", "tenant.t001.tools/notify-im", "tools", List.of("notify_sms"))),
                                    "tenant.t001.tools/missing"))));
            assertThat(codes(run().extensions(extension).go()))
                    .containsExactly("REFERENCE_UNRESOLVED", "REFERENCE_UNRESOLVED");
        }
    }

    @Nested
    @DisplayName("规则 3：人工确认策略取最严")
    class Rule3 {
        final List<Asset> assets = baseWithAgent(Fixtures.agent(with().version("1.2.0")
                .spec("approvals", List.of(Map.of("tool", "invoice-lookup", "policy", "require-confirmation")))));

        @Test
        @DisplayName("标品和租户的确认点都保留，租户可以加更严格的确认")
        void keepsBothLayers() {
            var extension = ext(Map.of(
                    "tools", Map.of("add", List.of("tenant.t001.tools/notify-im")),
                    "approvals",
                    Map.of(
                            "add",
                            List.of(
                                    Map.of("tool", "tenant.t001.tools/notify-im", "policy", "require-confirmation"),
                                    Map.of(
                                            "tool", "invoice-lookup",
                                            "policy", "require-approval",
                                            "when", "input.amount > 100000",
                                            "approverRole", "财务经理")))));
            var definition = run().extensions(extension).assets(assets).go().definition();
            assertThat(definition.approvals().stream().map(a -> a.layer().name() + ":" + a.tool() + ":" + a.policy()))
                    .containsExactly(
                            "vendor:invoice-lookup:require-confirmation",
                            "tenant:tenant.t001.tools/notify-im:require-confirmation",
                            "tenant:invoice-lookup:require-approval");
        }

        @Test
        @DisplayName("租户放宽标品已有的确认要求时报 APPROVAL_RELAXED")
        void relaxedRejected() {
            var extension = ext(Map.of("approvals", Map.of("add", List.of(Map.of("tool", "invoice-lookup", "policy", "auto")))));
            assertThat(codes(run().extensions(extension).assets(assets).go())).containsExactly("APPROVAL_RELAXED");
        }

        @Test
        @DisplayName("确认点指向不存在的工具或条件写错时报错")
        void badApprovals() {
            var extension = ext(Map.of(
                    "approvals",
                    Map.of(
                            "add",
                            List.of(Map.of("tool", "wire_transfer", "policy", "require-approval", "when", "input.amount >")))));
            assertThat(codes(run().extensions(extension).assets(assets).go()))
                    .containsExactly("REFERENCE_UNRESOLVED", "APPROVAL_CONDITION_INVALID");
        }
    }

    @Nested
    @DisplayName("规则 4 与故事 F：钩子顺序、隔离和条件检查")
    class Rule4 {
        final AgentExtensionAsset extension = ext(Map.of(
                "hooks",
                Map.of(
                        "onResult",
                        Map.of("workflow", "tenant.t001.wf/create-followup-task", "when", "result.severity in ['high','critical']"))));

        @Test
        @DisplayName("同一事件先标品钩子后租户钩子，租户钩子标注 isolate")
        void hookOrder() {
            var agent = Fixtures.agent(with().version("1.2.0")
                    .spec("hooks", Map.of("onResult", Map.of("skill", "vendor.fin.skills/invoice-lookup"))));
            var definition = run().extensions(extension).assets(baseWithAgent(agent)).go().definition();
            assertThat(definition.hooks().stream().map(h -> h.layer().name() + ":" + h.failurePolicy()))
                    .containsExactly("vendor:propagate", "tenant:isolate");
        }

        @Test
        @DisplayName("标品升级把 severity 改名为 riskLevel 后，租户钩子停用并给出警告")
        void renamedOutputDisablesTenantHook() {
            var upgraded = Fixtures.agent(with().version("1.3.0")
                    .spec("output", Map.of("schema", Map.of("riskLevel", Map.of("type", "string"), "amount", Map.of("type", "double")))));
            Result result = run().extensions(extension).assets(plus(base(), upgraded)).go();
            var definition = result.definition();
            assertThat(definition.provenance().agent()).isEqualTo("vendor.fin/transaction-anomaly-analyst@1.3.0");
            assertThat(definition.hooks()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            AssemblyIssue issue = result.issues().getFirst();
            assertThat(issue.code()).isEqualTo("HOOK_CONDITION_INVALID");
            assertThat(issue.severity()).isEqualTo("warning");
            assertThat(issue.source()).isEqualTo("tenant.t001.finance/anomaly-analyst-ext@0.3.0");
            assertThat(issue.message()).contains("severity");
        }

        @Test
        @DisplayName("标品钩子条件无效时组装失败")
        void vendorHookConditionInvalid() {
            var agent = Fixtures.agent(with().version("1.2.0").spec("hooks", Map.of(
                    "onResult", Map.of("skill", "vendor.fin.skills/invoice-lookup", "when", "result.missing == 1"))));
            Result result = run().assets(baseWithAgent(agent)).go();
            assertThat(result.ok()).isFalse();
            assertThat(result.issues().getFirst().severity()).isEqualTo("error");
        }
    }

    @Nested
    @DisplayName("规则 5：模型档位只能在标品允许的范围内选择")
    class Rule5 {
        @Test
        @DisplayName("允许范围内的档位生效")
        void allowedProfile() {
            var model = run().extensions(ext(Map.of("model", Map.of("profile", "fast")))).go().definition().model();
            assertThat(model.profile()).isEqualTo("fast");
            assertThat(model.layer().name()).isEqualTo("tenant");
        }

        @Test
        @DisplayName("超出范围时报错")
        void disallowedProfile() {
            assertThat(codes(run().extensions(ext(Map.of("model", Map.of("profile", "judge")))).go()))
                    .containsExactly("MODEL_NOT_ALLOWED");
        }

        @Test
        @DisplayName("没有扩展时使用标品配置")
        void vendorModel() {
            var model = run().go().definition().model();
            assertThat(model.profile()).isEqualTo("reasoning");
            assertThat(model.layer().name()).isEqualTo("vendor");
        }
    }

    @Nested
    @DisplayName("规则 6：扩展的 target 版本范围解析")
    class Rule6 {
        final Asset draft = Fixtures.agent(with().version("1.5.0"));
        final List<Asset> versions = plus(
                base().subList(1, 5),
                Fixtures.agent(with().version("1.2.0")),
                Fixtures.agent(with().version("1.4.0")),
                draft,
                Fixtures.agent(with().version("2.0.0")));
        final Map<Asset, Status> statuses = Map.of(draft, Status.draft);

        @Test
        @DisplayName("解析到当前生效的标品版本，范围覆盖它的扩展生效并记录在 provenance")
        void resolvesCurrentVersion() {
            var extension = Fixtures.extension(with().spec("target", "vendor.fin/transaction-anomaly-analyst@^2.0")
                    .spec("instructions", Map.of("append", "追加")));
            var definition = run().extensions(extension).assets(versions).statuses(statuses).go().definition();
            assertThat(definition.provenance().agent()).endsWith("@2.0.0");
            assertThat(definition.provenance().resolvedTarget()).isEqualTo(new ResolvedTarget(List.of("^2.0"), "2.0.0"));
            assertThat(definition.provenance().extensions()).containsExactly("tenant.t001.finance/anomaly-analyst-ext@0.3.0");
        }

        @Test
        @DisplayName("请求可以带版本范围；私有环境测试可以带上草稿")
        void requestRangeAndDrafts() {
            var pinned = run().agent(TARGET).assets(versions).statuses(statuses).go();
            assertThat(pinned.definition().provenance().agent()).endsWith("@1.4.0");
            var withDraft = run().agent(TARGET)
                    .extensions(Fixtures.extension())
                    .assets(versions)
                    .statuses(statuses)
                    .resolve(List.of(Status.published, Status.draft))
                    .go();
            assertThat(withDraft.definition().provenance().agent()).endsWith("@1.5.0");
        }

        @Test
        @DisplayName("范围不覆盖当前版本的扩展本次不生效，给出警告而不是让整个 Agent 不可用")
        void staleExtensionWarns() {
            Result result = run().extensions(Fixtures.extension()).assets(versions).statuses(statuses).go();
            assertThat(result.definition().provenance().agent()).endsWith("@2.0.0");
            assertThat(result.definition().provenance().extensions()).isEmpty();
            assertThat(result.issues()).hasSize(1);
            assertThat(result.issues().getFirst().code()).isEqualTo("TARGET_UNRESOLVED");
            assertThat(result.issues().getFirst().severity()).isEqualTo("warning");
            assertThat(result.issues().getFirst().path()).isEqualTo("spec.target");
        }

        @Test
        @DisplayName("请求的 Agent 引用不合法时返回问题而不是抛异常")
        void invalidRequest() {
            assertThat(codes(run().agent("not a ref").go())).containsExactly("AGENT_NOT_FOUND");
        }

        @Test
        @DisplayName("只应用 target 指向本 Agent 的扩展；Agent 不存在时报 AGENT_NOT_FOUND")
        void onlyTargetingExtensions() {
            var other = Fixtures.extension(with().name("other").spec("target", "vendor.pur/supplier-performance-analyst@^1.0"));
            assertThat(run().extensions(other).go().definition().provenance().extensions()).isEmpty();
            assertThat(codes(run().agent("vendor.fin/missing").go())).containsExactly("AGENT_NOT_FOUND");
        }

        @Test
        @DisplayName("扩展使用未声明的扩展点时报 EXTENSION_POINT_UNDECLARED")
        void undeclaredPoint() {
            var narrow = Fixtures.agent(with().version("1.2.0").spec("extensionPoints", List.of()));
            assertThat(codes(run().extensions(Fixtures.extension()).assets(baseWithAgent(narrow)).go()))
                    .containsExactly("EXTENSION_POINT_UNDECLARED");
        }
    }

    @Nested
    @DisplayName("规则 7：有效定义按查看者脱敏")
    class Rule7 {
        final EffectiveDefinition definition = run().extensions(Fixtures.extension()).go().definition();

        @Test
        @DisplayName("租户视角下标品指令和平台规则原文是占位块，序列化后找不到原文")
        void tenantViewRedacted() {
            var view = DefinitionView.view(definition, Viewer.tenant("t001"), null);
            assertThat(view.toString())
                    .doesNotContain(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS)
                    .doesNotContain("【标品机密】")
                    .doesNotContain("【平台机密】");
            var segments = view.get("segments");
            java.util.function.Function<String, tools.jackson.databind.JsonNode> byKind = kind -> {
                for (var s : segments) if (s.get("kind").asString().equals(kind)) return s;
                return null;
            };
            assertThat(Visibility.isRedacted(byKind.apply("vendor-instructions").get("text"))).isTrue();
            assertThat(byKind.apply("platform-rule").get("label").asString()).isEqualTo("租户数据隔离");
            assertThat(byKind.apply("tenant-instructions").get("text").asString())
                    .isEqualTo("单笔金额超过 50 万元的异常，结论中必须给出责任部门。");
            assertThat(byKind.apply("semantic-instruction").get("text").asString()).isEqualTo("引用金额时必须带币种");
        }

        @Test
        @DisplayName("厂商视角看到全文")
        void vendorViewFull() {
            assertThat(DefinitionView.view(definition, Viewer.VENDOR, null).toString())
                    .contains(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS);
        }
    }

    @Test
    @DisplayName("扩展追加的语义包及其父包进入有效定义，知识源成为文档检索工具")
    void knowledgeAndPacks() {
        var tenantPack = Fixtures.semanticPack(with().name("finance-ext")
                .namespace("tenant.t001.semantic")
                .spec("extends", "vendor.fin/finance")
                .spec("rules", List.of(Map.of("id", "FIN-R-101", "severity", "warn", "remedy", "跨期冲销不算异常")))
                .spec("agent_instructions", List.of("跨期冲销按正常业务处理")));
        var kb = Fixtures.build("KnowledgeSource", "anomaly-playbook", "tenant.t001.kb",
                Map.of("documents", List.of(Map.of("title", "异常处理手册", "path", "docs/anomaly.md"))));
        var extension = ext(Map.of(
                "knowledge", Map.of("add", List.of("tenant.t001.semantic/finance-ext", "tenant.t001.kb/anomaly-playbook")),
                "constraints", Map.of("append", List.of("FIN-R-101"))));
        var definition = run().extensions(extension).assets(plus(base(), tenantPack, kb)).go().definition();
        assertThat(definition.semanticPacks())
                .containsExactly("vendor.fin/finance@1.0.0", "tenant.t001.semantic/finance-ext@1.0.0");
        assertThat(definition.segments().stream()
                        .filter(s -> s.kind() == SegmentKind.semantic_instruction)
                        .map(s -> s.layer().name()))
                .containsExactly("vendor", "tenant");
        assertThat(definition.segments().getLast().text()).isEqualTo("[FIN-R-101] 跨期冲销不算异常");
        assertThat(definition.tools()).anyMatch(t -> t.name().equals("kb_anomaly_playbook") && t.type().equals("documents"));
    }

    @Nested
    @DisplayName("评审补充：可见性、确认点、写级别")
    class Review {
        @Test
        @DisplayName("租户自己的 Agent 的指令对本租户不保密")
        void tenantAgentNotConfidential() {
            var tenantAgent = Fixtures.agent(with().namespace("tenant.t001.agents")
                    .spec("semanticPacks", List.of())
                    .spec("constraints", List.of("自定义约束")));
            var definition = Assembler.assemble(new Assembler.Input(
                            "tenant.t001.agents/transaction-anomaly-analyst",
                            null,
                            null,
                            null,
                            InMemoryAssetResolver.published(tenantAgent, Fixtures.skill()),
                            null))
                    .definition();
            assertThat(definition.segments()).noneMatch(Segment::confidential);
        }

        @Test
        @DisplayName("租户不能声明 auto，也不能通过资产引用的写法放宽标品确认")
        void cannotRelax() {
            var assets = baseWithAgent(Fixtures.agent(with().version("1.2.0")
                    .spec("approvals", List.of(Map.of("tool", "invoice-lookup", "policy", "require-approval")))));
            var auto = ext(Map.of("approvals", Map.of("add", List.of(
                    Map.of("tool", "invoice-lookup", "policy", "auto", "when", "input.x == 1")))));
            assertThat(codes(run().extensions(auto).assets(assets).go())).containsExactly("APPROVAL_RELAXED");
            var byRef = ext(Map.of("approvals", Map.of("add", List.of(
                    Map.of("tool", "vendor.fin.skills/invoice-lookup", "policy", "require-confirmation")))));
            assertThat(codes(run().extensions(byRef).assets(assets).go())).containsExactly("APPROVAL_RELAXED");
        }

        @Test
        @DisplayName("strictestPolicy 在匹配的确认点里取最严")
        void strictestPolicy() {
            var assets = baseWithAgent(Fixtures.agent(with().version("1.2.0")
                    .spec("approvals", List.of(Map.of("tool", "invoice-lookup", "policy", "require-confirmation")))));
            var extension = ext(Map.of("approvals", Map.of("add", List.of(Map.of(
                    "tool", "vendor.fin.skills/invoice-lookup",
                    "policy", "require-approval",
                    "when", "input.amount > 100000.0")))));
            var definition = run().extensions(extension).assets(assets).go().definition();
            var tool = definition.tools().stream().filter(t -> t.name().equals("invoice-lookup")).findFirst().orElseThrow();
            var matched = Capabilities.matchingApprovals(definition.approvals(), tool);
            assertThat(matched).hasSize(2);
            assertThat(Capabilities.strictestPolicy(matched)).isEqualTo("require-approval");
            assertThat(Capabilities.strictestPolicy(List.of())).isEqualTo("auto");
        }

        @Test
        @DisplayName("租户新增工具的写级别不能超过 Agent 上限")
        void writeLevelCap() {
            var assets = baseWithAgent(Fixtures.agent(with().version("1.2.0").spec("writePolicy", Map.of("maxLevel", "L1"))));
            var extension = ext(Map.of("tools", Map.of("add", List.of("tenant.t001.tools/notify-im"))));
            assertThat(codes(run().extensions(extension).assets(assets).go())).containsExactly("TOOL_WRITE_LEVEL_EXCEEDED");
        }
    }
}

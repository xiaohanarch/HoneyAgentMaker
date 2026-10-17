package dev.eab.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.AssetLoader;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.RepoPaths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** demo/scripts 下的全部剧本：格式合法、编号不重复、Agent 存在，示例话术能匹配到剧本 */
class DemoScriptsTest {

    static final List<Scripts.Script> SCRIPTS =
            Scripts.loadDirectory(RepoPaths.root().resolve("demo").resolve("scripts"));

    static final List<Asset.AgentAsset> AGENTS =
            AssetLoader.loadDirectory(RepoPaths.assets().resolve("vendor")).assets().stream()
                    .map(AssetLoader.LoadedAsset::asset)
                    .filter(Asset.AgentAsset.class::isInstance)
                    .map(Asset.AgentAsset.class::cast)
                    .toList();

    /** Studio 推荐、经验回流生成候选规则用的平台身份，以及故事 A 在向导里新建、发布前没有资产文件的 Agent */
    static final Set<String> STUDIO_AGENTS =
            Set.of("platform/studio", "platform/feedback", "tenant.t001.agents/price-negotiation-assistant");

    @Test
    @DisplayName("剧本全部能加载，引用的 Agent 是仓库里的标品 Agent，或 Studio 的建模助手与故事 A 新建的 Agent")
    void loads() {
        Set<String> agents = AGENTS.stream()
                .map(a -> a.metadata().namespace() + "/" + a.metadata().name())
                .collect(Collectors.toCollection(java.util.HashSet::new));
        agents.addAll(STUDIO_AGENTS);
        assertThat(SCRIPTS).isNotEmpty();
        assertThat(SCRIPTS).allMatch(s -> agents.contains(s.agent()));
    }

    @Test
    @DisplayName("四个标品 Agent 各至少有一条剧本，并且它们的第一条示例话术能匹配到剧本")
    void everyVendorAgentCovered() {
        for (String agent : List.of(
                "vendor.pur/supplier-performance-analyst",
                "vendor.fin/transaction-anomaly-analyst",
                "vendor.fin/period-close-checker",
                "vendor.scm/inventory-risk-analyst")) {
            var asset = AGENTS.stream()
                    .filter(a -> (a.metadata().namespace() + "/" + a.metadata().name()).equals(agent))
                    .findFirst()
                    .orElseThrow();
            String example = asset.spec().routing().examples().getFirst();
            assertThat(Scripts.match(SCRIPTS, agent, "anyone", example))
                    .as(agent + "：" + example)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("带工具条件的剧本只在这些工具可用时匹配，并且优先于不带条件的剧本（故事 B：扩展增加了 notify_im）")
    void toolConditions() {
        String agent = "vendor.fin/transaction-anomaly-analyst";
        String question = "本月有哪些高风险交易异常";
        assertThat(Scripts.match(SCRIPTS, agent, "wangfang", question, java.util.Set.of("transaction_anomaly_list"))
                        .script()
                        .id())
                .isEqualTo("fin-anomaly-high-risk");
        assertThat(Scripts.match(
                                SCRIPTS,
                                agent,
                                "wangfang",
                                question,
                                java.util.Set.of("transaction_anomaly_list", "notify_im"))
                        .script()
                        .id())
                .isEqualTo("story-b-anomaly-extended");
    }

    @Test
    @DisplayName("故事 E：没有备选供应商技能时走能力缺口剧本，扩展发布后（技能可用）走补货剧本")
    void storyEToolConditions() {
        String agent = "vendor.pur/purchase-order-clerk";
        String question = "百润精密这批货到不了，帮我从备选供应商里找有现货的下单";
        assertThat(Scripts.match(
                                SCRIPTS,
                                agent,
                                "zhangmin",
                                question,
                                Set.of("purchase_order_list", "purchase_order_get"))
                        .script()
                        .id())
                .isEqualTo("story-e-clerk-gap");
        assertThat(Scripts.match(
                                SCRIPTS,
                                agent,
                                "zhangmin",
                                question,
                                Set.of("purchase_order_list", "purchase_order_get", "alternative-supplier-lookup"))
                        .script()
                        .id())
                .isEqualTo("story-e-alternative-order");
    }

    @Test
    @DisplayName("故事 E：方案剧本按“任务：生成扩展方案”话术匹配，资产草稿与评测集和仓库里的租户资产一致")
    void storyEPlanScript() {
        String prompt = "任务：生成扩展方案\n需求：百润精密这批货到不了，帮我从备选供应商里找有现货的下单\n补充说明：金额超过 10 万的必须人工审批";
        assertThat(Scripts.match(SCRIPTS, "platform/studio", "chengong", prompt)
                        .script()
                        .id())
                .isEqualTo("story-e-request-plan");
        Map<String, Object> plan = SCRIPTS.stream()
                .filter(s -> s.id().equals("story-e-request-plan"))
                .findFirst()
                .orElseThrow()
                .steps()
                .getFirst()
                .output();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> drafts = (List<Map<String, String>>) plan.get("drafts");
        Map<String, String> yamlByKind =
                drafts.stream().collect(Collectors.toMap(d -> d.get("kind"), d -> d.get("yaml")));
        assertThat(yamlByKind).containsOnlyKeys("Skill", "AgentExtension");
        assertSameTree(yamlByKind.get("Skill"), "tenants/t001/pur/skills/alternative-supplier-lookup.yaml");
        assertSameTree(yamlByKind.get("AgentExtension"), "tenants/t001/pur/purchase-order-clerk-ext.yaml");
        @SuppressWarnings("unchecked")
        Map<String, String> evalSuite = (Map<String, String>) plan.get("evalSuite");
        assertThat(evalSuite.get("name")).isEqualTo("purchase-clerk-ext-smoke");
        assertSameTree(evalSuite.get("yaml"), "tenants/t001/evals/purchase-clerk-ext-smoke.yaml");
    }

    /** 剧本里嵌的资产 YAML 必须和仓库里的租户资产是同一棵树：采纳后发布的就是仓库里这份的演示态 */
    private static void assertSameTree(String scriptYaml, String repoRelative) {
        tools.jackson.databind.JsonNode scriptTree = YAML.readTree(scriptYaml);
        tools.jackson.databind.JsonNode repoTree;
        try {
            repoTree = YAML.readTree(
                    java.nio.file.Files.readString(RepoPaths.assets().resolve(repoRelative)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读不到仓库资产 " + repoRelative, e);
        }
        assertThat(scriptTree).as(repoRelative).isEqualTo(repoTree);
    }

    private static final tools.jackson.dataformat.yaml.YAMLMapper YAML =
            tools.jackson.dataformat.yaml.YAMLMapper.builder().build();
}

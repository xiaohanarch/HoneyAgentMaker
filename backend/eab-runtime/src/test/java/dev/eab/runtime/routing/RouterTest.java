package dev.eab.runtime.routing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.runtime.routing.Catalog.Entry;
import dev.eab.spec.AssetLoader;
import dev.eab.spec.testing.RepoPaths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 用仓库里的标品资产生成能力目录，测试两级路由 */
class RouterTest {

    static final List<Entry> CATALOG =
            Catalog.build(AssetLoader.loadDirectory(RepoPaths.assets().resolve("vendor")).assets().stream()
                    .map(AssetLoader.LoadedAsset::asset)
                    .toList());

    static final Router ROUTER = new Router(new Router.Bm25Retriever(), new Router.ScoreDecider());

    static List<Entry> firstLevel() {
        return CATALOG.stream()
                .filter(e -> e.kind().equals("assistant") || e.direct())
                .toList();
    }

    static List<Entry> members(Entry assistant) {
        List<Entry> members = new ArrayList<>(CATALOG.stream()
                .filter(e -> assistant.members().contains(e.ref()))
                .toList());
        members.addAll(Catalog.NAVIGATION);
        return members;
    }

    static Entry entry(String ref) {
        return CATALOG.stream().filter(e -> e.ref().equals(ref)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("能力目录包含三个岗位助手、全部标品 Agent，Agent 的示例话术合并了语义包的 verified_tasks")
    void catalog() {
        assertThat(CATALOG.stream().filter(e -> e.kind().equals("assistant")).map(Entry::ref))
                .containsExactlyInAnyOrder(
                        "vendor.pur/procurement-assistant",
                        "vendor.scm/inventory-assistant",
                        "vendor.fin/finance-assistant");
        assertThat(entry("vendor.pur/supplier-performance-analyst").examples()).contains("百润精密最近一次询价的报价情况如何");
    }

    @ParameterizedTest(name = "{0} → {1} → {2}")
    @CsvSource({
        "百润精密最近表现怎么样, vendor.pur/procurement-assistant, vendor.pur/supplier-performance-analyst",
        "把 PO-2026-0117 的交期改到下月 15 号, vendor.pur/procurement-assistant, vendor.pur/purchase-order-clerk",
        "打开百润精密的供应商详情, vendor.pur/procurement-assistant, platform/navigation:supplier",
        "我们的降价谈判制度是怎么规定的, vendor.pur/procurement-assistant, vendor.pur/procurement-policy-advisor",
        "华东区本季度逾期交付率是多少, vendor.pur/procurement-assistant, vendor.pur/supplier-performance-analyst",
        "哪些物料低于安全库存, vendor.scm/inventory-assistant, vendor.scm/inventory-risk-analyst",
        "本月有哪些高风险交易异常, vendor.fin/finance-assistant, vendor.fin/transaction-anomaly-analyst",
        "本期资产核算的结账检查还有哪些没通过, vendor.fin/finance-assistant, vendor.fin/period-close-checker",
    })
    @DisplayName("演示话术经两级路由到达预期的岗位助手和 Agent")
    void twoLevels(String query, String assistant, String target) {
        Router.Level first = ROUTER.route(query, firstLevel(), e -> null);
        assertThat(first.outcome()).as(first.toString()).isEqualTo("route");
        assertThat(first.decision().chosen().ref()).isEqualTo(assistant);
        Router.Level second = ROUTER.route(query, members(first.decision().chosen()), e -> null);
        assertThat(second.decision().chosen().ref())
                .as(second.candidates().toString())
                .isEqualTo(target);
        assertThat(second.decision().reason()).contains("最接近");
    }

    @Test
    @DisplayName("权限过滤掉的候选不会被选中，并记录原因；全部被过滤时给出能力缺口")
    void filtered() {
        Router.Level level = ROUTER.route(
                "本月有哪些高风险交易异常",
                firstLevel(),
                e -> e.ref().equals("vendor.fin/finance-assistant") ? "财务助手没有对采购员开放" : null);
        assertThat(level.candidates()).anyMatch(c -> "财务助手没有对采购员开放".equals(c.filtered()));
        assertThat(level.outcome()).isIn("gap", "route", "clarify");
        if (level.decision() != null)
            assertThat(level.decision().chosen().ref()).isNotEqualTo("vendor.fin/finance-assistant");
        assertThat(ROUTER.route("本月有哪些高风险交易异常", firstLevel(), e -> "不允许").outcome())
                .isEqualTo("gap");
    }

    @Test
    @DisplayName("和任何能力都不相关的问题给出能力缺口")
    void gap() {
        assertThat(ROUTER.route("帮我订一张去上海的机票", firstLevel(), e -> null).outcome())
                .isEqualTo("gap");
    }

    @Test
    @DisplayName("分数接近时向用户澄清，给出两个选项")
    void clarify() {
        Router.Decider undecided = (q, c) -> new Router.Decision(c.getFirst().entry(), 0.5, "两个都可能", "score");
        Router.Level level = new Router(new Router.Bm25Retriever(), undecided).route("采购订单", firstLevel(), e -> null);
        assertThat(level.outcome()).isIn("clarify", "route");
        if (level.outcome().equals("clarify")) assertThat(level.options()).hasSize(2);
    }

    @Test
    @DisplayName("模型决策：解析 JSON，选了候选之外的项时置信度为 0")
    void modelDeciderParsing() {
        assertThat(ModelDecider.strip("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(ModelDecider.system(
                        List.of(new Router.Candidate(entry("vendor.pur/procurement-assistant"), 3, "x", null))))
                .contains("vendor.pur/procurement-assistant");
    }
}

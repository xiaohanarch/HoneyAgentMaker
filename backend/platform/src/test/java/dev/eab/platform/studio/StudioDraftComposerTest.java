package dev.eab.platform.studio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.AssetParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioDraftComposerTest {

    static final String INTENT = "帮采购经理制定和供应商的降价谈判策略";

    @Test
    @DisplayName("新草稿能通过 schema；采纳各步后对应字段改变，工具列表按条目类型替换，不碰其他条目")
    void applySteps() {
        var tree = StudioDraftComposer.skeleton("t001", "chengong", "agent-abc123", INTENT);
        assertThat(AssetParser.parseYaml(StudioDraftComposer.write(tree)).ok()).isTrue();

        tree = StudioDraftComposer.apply(
                tree, "naming", Map.of("name", "price-negotiation-assistant", "displayName", "价格谈判助手"));
        tree = StudioDraftComposer.apply(
                tree,
                "collaborators",
                Map.of("agents", List.of("vendor.pur/supplier-performance-analyst", "connection:X")));
        tree = StudioDraftComposer.apply(
                tree,
                "tools",
                Map.of(
                        "skills", List.of("vendor.pur.skills/rfq-lookup"),
                        "tools",
                                List.of(Map.of(
                                        "source", "vendor.erp.tools/demo-erp-bo", "name", "purchase_order_list"))));
        tree = StudioDraftComposer.apply(
                tree, "instructions", Map.of("instructions", "指令", "constraints", List.of("PUR-R-007")));

        String yaml = StudioDraftComposer.write(tree);
        assertThat(yaml)
                .contains(
                        "name: price-negotiation-assistant", "价格谈判助手", "rfq-lookup", "purchase_order_list", "PUR-R-007")
                .contains("builtin: calculator")
                .doesNotContain("connection:X");
        assertThat(AssetParser.parseYaml(yaml).ok()).isTrue();

        // 再次采纳协作 Agent 只替换 agent 条目
        tree = StudioDraftComposer.apply(tree, "collaborators", Map.of("agents", List.of()));
        assertThat(StudioDraftComposer.write(tree))
                .doesNotContain("supplier-performance-analyst")
                .contains("rfq-lookup", "purchase_order_list");
    }
}

package dev.eab.platform.studio;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StudioAnswersTest {

    static StudioCatalog.Item agent(String ref, String writeLevel) {
        return new StudioCatalog.Item(ref, ref, "", "vendor", writeLevel, true, null, List.of());
    }

    @Test
    @DisplayName("模型选了写级别高于 L0 的协作 Agent、技能或工具时不接受，只保留只读的候选")
    void writeLevelsAboveReadOnlyAreDropped() {
        Map<String, Object> agents =
                Map.of("agents", List.of(agent("vendor.pur/analyst", "L0"), agent("vendor.scm/risk", "L2")));
        var collaborators = StudioAnswers.accept(
                "collaborators",
                Map.of("agents", List.of("vendor.pur/analyst", "vendor.scm/risk", "vendor.x/unknown")),
                agents,
                Map.of("agents", List.of()));
        assertThat(collaborators.get("agents")).isEqualTo(List.of("vendor.pur/analyst"));

        Map<String, Object> tools = Map.of(
                "skills",
                List.of(agent("vendor.pur.skills/rfq-lookup", "L0"), agent("vendor.common.skills/followup", "L2")),
                "tools",
                List.of(
                        new StudioCatalog.Tool("src", "purchase_order_list", "", "L0", "query"),
                        new StudioCatalog.Tool("src", "followup_task_create", "", "L2", "write")));
        var picked = StudioAnswers.accept(
                "tools",
                Map.of(
                        "skills",
                        List.of("vendor.pur.skills/rfq-lookup", "vendor.common.skills/followup"),
                        "tools",
                        List.of(
                                Map.of("source", "src", "name", "purchase_order_list"),
                                Map.of("source", "src", "name", "followup_task_create"))),
                tools,
                Map.of("skills", List.of(), "tools", List.of()));
        assertThat(picked.get("skills")).isEqualTo(List.of("vendor.pur.skills/rfq-lookup"));
        assertThat(picked.get("tools")).isEqualTo(List.of(Map.of("source", "src", "name", "purchase_order_list")));
    }

    @Test
    @DisplayName("标识不符合命名规则或档位不在可选范围时整步不接受，退回检索推荐")
    void invalidAnswersAreRejected() {
        assertThat(StudioAnswers.accept("naming", Map.of("name", "价格助手", "displayName", "价格助手"), Map.of(), Map.of()))
                .isNull();
        assertThat(StudioAnswers.accept("model", Map.of("profile", "judge"), Map.of(), Map.of()))
                .isNull();
        assertThat(StudioAnswers.accept("model", Map.of("profile", "fast", "temperature", 0.1), Map.of(), Map.of()))
                .containsEntry("profile", "fast")
                .containsEntry("temperature", 0.1);
    }
}

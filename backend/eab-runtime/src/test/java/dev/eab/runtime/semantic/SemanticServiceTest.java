package dev.eab.runtime.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.spec.AssetLoader;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.testing.RepoPaths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 用仓库里的三个标品语义包测试四个语义查询工具 */
class SemanticServiceTest {

    static final List<SemanticPackAsset> PACKS =
            AssetLoader.loadDirectory(RepoPaths.assets().resolve("vendor")).assets().stream()
                    .map(AssetLoader.LoadedAsset::asset)
                    .filter(a -> a instanceof SemanticPackAsset)
                    .map(a -> (SemanticPackAsset) a)
                    .toList();

    static final SemanticService.MetaClient META = ref -> ref.startsWith("erp.pur.Supplier")
            ? Optional.of(Map.of(
                    "ref", "erp.pur.Supplier@v1",
                    "label", "供应商",
                    "permissionDimension", "采购组织",
                    "fields", List.of(Map.of("name", "rating", "type", "enum", "label", "评级"))))
            : Optional.empty();

    SemanticService procurement() {
        return new SemanticService(
                PACKS.stream()
                        .filter(p -> p.metadata().name().equals("procurement"))
                        .toList(),
                META);
    }

    @Test
    @DisplayName("describe_object 合并元数据定义与语义包的字段说明，并带两种版本")
    void describeObject() {
        var result = procurement().call("semantic.describe_object", Map.of("object", "供应商"));
        assertThat(result).containsEntry("ref", "erp.pur.Supplier").containsEntry("permissionDimension", "采购组织");
        assertThat(result.get("fields").toString()).contains("enumMeaning").contains("战略供应商");
        @SuppressWarnings("unchecked")
        var provenance = (Map<String, Object>) result.get("provenance");
        assertThat(provenance.get("semanticPack").toString()).startsWith("vendor.pur/procurement@");
        assertThat(provenance).containsEntry("metadata", "erp.pur.Supplier@v1");
    }

    @Test
    @DisplayName("只在挂载的语义包里查询，找不到时列出可用的对象")
    void scopedToMountedPacks() {
        assertThatThrownBy(() -> procurement().describeObject("erp.fin.TransactionAnomaly"))
                .hasMessageContaining("可用的对象")
                .hasMessageContaining("erp.pur.Supplier");
        assertThatThrownBy(() -> procurement().describeObject(" ")).hasMessageContaining("请给出业务对象名");
    }

    @Test
    @DisplayName("get_metric 按名称或显示名查口径，find_rules 按编号或关键词，search 跨对象、指标、规则")
    void metricsRulesSearch() {
        var service = procurement();
        assertThat(service.getMetric("overdue_days")).containsEntry("name", "overdue_days");
        assertThat(service.getMetric("交付逾期天数")).containsEntry("name", "overdue_days");
        assertThatThrownBy(() -> service.getMetric("毛利率")).hasMessageContaining("可用的指标");
        assertThat(service.findRules(null, "PUR-R-007").get("total")).isEqualTo(1);
        assertThat(service.search("逾期", 5).get("total")).isNotEqualTo(0);
        assertThat(service.retrieve("交付逾期怎么算", 4)).isNotEmpty();
        assertThatThrownBy(() -> service.call("semantic.nope", Map.of())).hasMessageContaining("没有语义查询工具");
    }
}

package dev.eab.spec;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.AssetResolver.VersionInfo;
import dev.eab.spec.InMemoryAssetResolver.Entry;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.testing.Fixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResolverTest {

    @Test
    @DisplayName("解析带版本范围的引用")
    void parsesRefWithRange() {
        assertThat(AssetRef.parse("vendor.fin/transaction-anomaly-analyst@^1.0"))
                .isEqualTo(new AssetRef("vendor.fin", "transaction-anomaly-analyst", "^1.0"));
        assertThat(AssetRef.parse("tenant.t001.kb/playbook").toString()).isEqualTo("tenant.t001.kb/playbook");
    }

    @ParameterizedTest(name = "拒绝非法引用 {0}")
    @ValueSource(strings = {"no-slash", "vendor.fin/", "acme.fin/x", "vendor.fin/Bad_Name", "vendor.fin/x@not-a-range!"})
    void rejectsInvalidRefs(String ref) {
        assertThatThrownBy(() -> AssetRef.parse(ref)).isInstanceOf(AssetRef.InvalidAssetRefException.class);
    }

    @Test
    @DisplayName("区分锁定版本和版本范围")
    void pinnedRefs() {
        assertThat(AssetRef.isPinned("vendor.pur/procurement@1.0.0")).isTrue();
        assertThat(AssetRef.isPinned("vendor.pur/procurement@^1.0")).isFalse();
        assertThat(AssetRef.isPinned("vendor.pur/procurement")).isFalse();
    }

    final InMemoryAssetResolver resolver = new InMemoryAssetResolver(List.of(
            new Entry(Fixtures.agent(with().version("1.2.0")), Status.published),
            new Entry(Fixtures.agent(with().version("1.3.0")), Status.draft),
            new Entry(Fixtures.agent(with().version("2.0.0")), Status.published),
            new Entry(Fixtures.semanticPack(), Status.published)));

    @Test
    @DisplayName("默认只在已发布版本里取满足范围的最高版本")
    void resolvesHighestPublished() {
        AssetRef ref = AssetRef.parse("vendor.fin/transaction-anomaly-analyst@^1.0");
        assertThat(resolver.resolveVersion(ref, AssetResolver.PUBLISHED)).contains("1.2.0");
        assertThat(resolver.resolveVersion(ref, List.of(Status.published, Status.draft))).contains("1.3.0");
    }

    @Test
    @DisplayName("不带范围时取最新的已发布版本")
    void resolvesLatestWithoutRange() {
        assertThat(resolver.resolve("vendor.fin/transaction-anomaly-analyst").map(a -> a.metadata().version()))
                .contains("2.0.0");
    }

    @Test
    @DisplayName("没有满足的版本或者 kind 不符时返回空")
    void emptyWhenUnresolved() {
        assertThat(resolver.resolve("vendor.fin/transaction-anomaly-analyst@^3")).isEmpty();
        assertThat(resolver.resolveOfKind("vendor.fin/finance", AgentAsset.class, AssetResolver.PUBLISHED)).isEmpty();
        assertThat(resolver.resolveOfKind("vendor.fin/finance", SemanticPackAsset.class, AssetResolver.PUBLISHED))
                .isPresent();
    }

    @Test
    @DisplayName("版本列表按从新到旧排序")
    void versionsSortedDescending() {
        assertThat(resolver.listVersions("vendor.fin", "transaction-anomaly-analyst").stream().map(VersionInfo::version))
                .containsExactly("2.0.0", "1.3.0", "1.2.0");
        assertThat(resolver.listVersions("vendor.fin", "missing")).isEmpty();
    }

    @ParameterizedTest(name = "{0} 属于正确的层")
    @ValueSource(strings = {"platform", "vendor.pur", "vendor.fin.close", "tenant.t001", "tenant.t001.finance"})
    void layersOfValidNamespaces(String namespace) {
        String expected = namespace.split("\\.")[0];
        assertThat(Layers.layerOfNamespace(namespace).name()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "拒绝不合法的命名空间 \"{0}\"")
    @ValueSource(strings = {"vendor", "tenant", "platform.x", "acme.pur", "Vendor.pur", "vendor..pur", ""})
    void rejectsInvalidNamespaces(String namespace) {
        assertThatThrownBy(() -> Layers.layerOfNamespace(namespace))
                .isInstanceOf(Layers.InvalidNamespaceException.class);
    }

    @Test
    @DisplayName("从租户命名空间取出租户编号，非租户命名空间返回空")
    void tenantIdOfNamespace() {
        assertThat(Layers.tenantIdOfNamespace("tenant.t001.finance")).contains("t001");
        assertThat(Layers.tenantIdOfNamespace("vendor.pur")).isEmpty();
        assertThat(Layers.tenantIdOfNamespace("platform")).isEmpty();
    }
}

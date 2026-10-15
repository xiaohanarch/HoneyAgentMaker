package dev.eab.spec;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.spec.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VisibilityTest {

    static final Viewer TENANT = Viewer.tenant("t001");
    static final Viewer OTHER_TENANT = Viewer.tenant("t002");

    @ParameterizedTest(name = "往返解析 {0}")
    @ValueSource(strings = {"platform", "vendor", "tenant:t001"})
    void viewerRoundTrip(String value) {
        assertThat(Viewer.parse(value).format()).isEqualTo(value);
    }

    @Test
    @DisplayName("拒绝无法识别的身份")
    void rejectsUnknownViewer() {
        assertThatThrownBy(() -> Viewer.parse("admin")).hasMessageContaining("无法识别");
    }

    @Test
    @DisplayName("只有本层身份能写本层命名空间")
    void canWriteNamespace() {
        assertThat(Visibility.canWriteNamespace(Viewer.VENDOR, "vendor.fin")).isTrue();
        assertThat(Visibility.canWriteNamespace(TENANT, "vendor.fin")).isFalse();
        assertThat(Visibility.canWriteNamespace(TENANT, "platform")).isFalse();
        assertThat(Visibility.canWriteNamespace(TENANT, "tenant.t001.finance")).isTrue();
        assertThat(Visibility.canWriteNamespace(OTHER_TENANT, "tenant.t001.finance")).isFalse();
        assertThat(Visibility.canWriteNamespace(Viewer.PLATFORM, "platform")).isTrue();
        assertThat(Visibility.canWriteNamespace(Viewer.PLATFORM, "vendor.fin")).isFalse();
    }

    @Test
    @DisplayName("租户之间互相不可见，厂商看不到租户资产")
    void tenantIsolation() {
        var ext = Fixtures.extension();
        assertThat(Visibility.canReadAsset(TENANT, ext)).isTrue();
        assertThat(Visibility.canReadAsset(OTHER_TENANT, ext)).isFalse();
        assertThat(Visibility.canReadAsset(Viewer.VENDOR, ext)).isFalse();
        assertThat(Visibility.canReadAsset(Viewer.PLATFORM, ext)).isTrue();
    }

    @Test
    @DisplayName("租户看不到标品的内部评测集")
    void internalEvalSuites() {
        var suite = Fixtures.build("EvalSuite", "anomaly-internal", "vendor.fin.evals", Map.of(
                "target", "vendor.fin/transaction-anomaly-analyst",
                "cases", List.of(Map.of(
                        "id", "c1", "user", "wangfang", "question", "q",
                        "assertions", List.of(Map.of("type", "contains", "text", "x"))))));
        assertThat(Visibility.canReadAsset(TENANT, suite)).isFalse();
        assertThat(Visibility.canReadAsset(Viewer.VENDOR, suite)).isTrue();
    }

    @Test
    @DisplayName("租户视角下标品指令、语气说明、自由文本约束被替换为占位，规则编号保留")
    void redactsVendorInstructions() {
        var agent = Fixtures.agent(with().spec("additionalContext", "语气专业"));
        var view = Visibility.redactAsset(agent, TENANT, null);
        var spec = view.get("spec");
        assertThat(Visibility.isRedacted(spec.get("instructions"))).isTrue();
        assertThat(Visibility.isRedacted(spec.get("additionalContext"))).isTrue();
        assertThat(spec.get("constraints").get(0).asString()).isEqualTo("FIN-R-001");
        assertThat(Visibility.isRedacted(spec.get("constraints").get(1))).isTrue();
        assertThat(view.toString()).doesNotContain(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS).doesNotContain("【标品机密】");
        // 公开版指令、扩展点、工具清单对租户可见
        assertThat(spec.has("publicInstructions")).isTrue();
        assertThat(spec.get("extensionPoints")).hasSize(7);
    }

    @Test
    @DisplayName("占位的 digest 随原文变化，用于显示“已变更”")
    void digestChangesWithText() {
        var v1 = Visibility.redactAsset(Fixtures.agent(), TENANT, null);
        var v2 = Visibility.redactAsset(Fixtures.agent(with().spec("instructions", "新版指令")), TENANT, null);
        assertThat(v1.get("spec").get("instructions")).isNotEqualTo(v2.get("spec").get("instructions"));
        assertThat(Visibility.digestOf("a")).matches("^[0-9a-f]{8}$");
        // 与 TS 版的 FNV-1a 结果一致
        assertThat(Visibility.digestOf("a")).isEqualTo("e40c292c");
    }

    @Test
    @DisplayName("平台规则对租户只保留名称和说明")
    void redactsPlatformRules() {
        var view = Visibility.redactAsset(Fixtures.platformRules(), TENANT, null);
        var rule = view.get("spec").get("rules").get(0);
        assertThat(rule.get("name").asString()).isEqualTo("租户数据隔离");
        assertThat(Visibility.isRedacted(rule.get("instruction"))).isTrue();
        assertThat(view.toString()).doesNotContain("【平台机密】");
    }

    @Test
    @DisplayName("厂商、平台视角和租户看自己的资产时不裁剪")
    void noRedactionForOwnLayers() {
        var agent = Fixtures.agent();
        assertThat(Visibility.redactAsset(agent, Viewer.VENDOR, null)).isEqualTo(Json.toTree(agent));
        assertThat(Visibility.redactAsset(agent, Viewer.PLATFORM, null)).isEqualTo(Json.toTree(agent));
        var ext = Fixtures.extension();
        assertThat(Visibility.redactAsset(ext, TENANT, null)).isEqualTo(Json.toTree(ext));
    }
}

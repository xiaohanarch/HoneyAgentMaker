package dev.eab.platform.procode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** eab-project.yaml 清单的读写与合并 */
class ProjectManifestTest {

    @Test
    @DisplayName("清单往返：toYaml 再 read 回到相等的清单，null 字段不写出")
    void roundTrip() {
        ProjectManifest manifest = new ProjectManifest(
                "extension",
                "http://127.0.0.1:4000",
                null,
                "vendor.pur/purchase-order-clerk",
                null,
                List.of(new ProjectManifest.Entry(
                        "assets/purchase-order-clerk-ext.yaml",
                        "AgentExtension",
                        "tenant.t001.pur/purchase-order-clerk-ext",
                        "drf-ab12cd34")));
        String yaml = manifest.toYaml();
        assertThat(yaml).doesNotContain("user:").doesNotContain("cardUrl:");
        assertThat(ProjectManifest.read(yaml)).isEqualTo(manifest);
    }

    @Test
    @DisplayName("缺 template、未知模板、条目缺 kind、路径越界、坏 YAML 都给出明确错误")
    void rejectsBrokenManifests() {
        assertThatThrownBy(() -> ProjectManifest.read("platform: http://127.0.0.1:4000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("template");
        assertThatThrownBy(() -> ProjectManifest.read("template: unknown")).hasMessageContaining("未知的模板");
        assertThatThrownBy(() -> ProjectManifest.read("template: agent\nassets:\n- file: a.yaml"))
                .hasMessageContaining("kind");
        assertThatThrownBy(() -> ProjectManifest.read("template: agent\nassets:\n- file: ../x.yaml\n  kind: Agent"))
                .hasMessageContaining("相对路径");
        assertThatThrownBy(() -> ProjectManifest.read("template: agent\nassets:\n- file: /x.yaml\n  kind: Agent"))
                .hasMessageContaining("相对路径");
        assertThatThrownBy(() -> ProjectManifest.read("template: [broken")).hasMessageContaining("不是合法的 YAML");
    }

    @Test
    @DisplayName("清单里的未知字段被忽略，清单格式向后兼容")
    void ignoresUnknownFields() {
        ProjectManifest manifest = ProjectManifest.read("template: agent\nfutureField: whatever");
        assertThat(manifest.template()).isEqualTo("agent");
        assertThat(manifest.assets()).isEmpty();
    }

    @Test
    @DisplayName("withAsset 按 file 替换或追加，不改动原清单")
    void withAssetMergesByFile() {
        ProjectManifest.Entry first = new ProjectManifest.Entry("assets/a.yaml", "Agent", null, null);
        ProjectManifest manifest = new ProjectManifest("agent", null, null, null, null, List.of(first));
        ProjectManifest updated = manifest.withAsset(
                new ProjectManifest.Entry("assets/a.yaml", "Agent", "tenant.t001.agents/a", "drf-1"));
        assertThat(updated.assets()).hasSize(1);
        assertThat(updated.assets().get(0).draftId()).isEqualTo("drf-1");
        assertThat(updated.asset("assets/a.yaml")).isPresent();
        assertThat(updated.asset("assets/b.yaml")).isEmpty();
        assertThat(manifest.assets().get(0).draftId()).isNull();
    }

    @Test
    @DisplayName("资产类型映射到工程模板，EvalSuite 与其他类型不单独成工程")
    void templateForKind() {
        assertThat(ProjectManifest.templateForKind("Agent")).isEqualTo("agent");
        assertThat(ProjectManifest.templateForKind("AgentExtension")).isEqualTo("extension");
        assertThat(ProjectManifest.templateForKind("Skill")).isEqualTo("skill");
        assertThat(ProjectManifest.templateForKind("EvalSuite")).isNull();
        assertThat(ProjectManifest.templateForKind("RemoteAgent")).isNull();
    }

    @Test
    @DisplayName("工程目录里没有清单时提示当前目录不是代码工程")
    void readMissingFile(@TempDir Path dir) {
        assertThatThrownBy(() -> ProjectManifest.read(dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 EAB 代码工程");
    }
}

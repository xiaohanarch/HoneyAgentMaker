package dev.eab.platform.procode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** init 模板解包：文件齐全、占位符全部替换、清单与文件一致 */
class ProjectTemplatesTest {

    @ParameterizedTest
    @ValueSource(strings = {"agent", "extension", "a2a-python", "a2a-ts"})
    @DisplayName("四类模板都能 init 出完整工程，占位符全部替换，清单可读回")
    void initRendersEveryTemplate(String template, @TempDir Path dir) throws IOException {
        String target = "extension".equals(template) ? "vendor.pur/purchase-order-clerk" : null;
        ProjectManifest manifest =
                ProjectTemplates.init(template, "demo-agent-1", target, "http://127.0.0.1:4000", dir);
        assertThat(manifest.template()).isEqualTo(template);
        assertThat(ProjectManifest.read(dir)).isEqualTo(manifest);
        for (String file : ProjectTemplates.filePaths(template, "demo-agent-1", target, null)) {
            assertThat(dir.resolve(file)).as(file).isRegularFile();
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("{{");
            }
        }
    }

    @Test
    @DisplayName("agent 模板：资产与冒烟评测集进清单，评测集指向同租户命名空间")
    void initAgent(@TempDir Path dir) throws IOException {
        ProjectManifest manifest = ProjectTemplates.init("agent", "credit-clerk", null, null, dir);
        assertThat(manifest.assets()).extracting(ProjectManifest.Entry::kind).containsExactly("Agent", "EvalSuite");
        assertThat(manifest.asset("assets/credit-clerk.yaml")).isPresent();
        assertThat(Files.readString(dir.resolve("assets/credit-clerk.yaml")))
                .contains("name: credit-clerk")
                .contains("kind: Agent");
        assertThat(Files.readString(dir.resolve("evals/credit-clerk-smoke.yaml")))
                .contains("target: tenant.<改我>.agents/credit-clerk");
    }

    @Test
    @DisplayName("extension 模板：target 落进 spec 与清单，默认范围 ^1.0，传入范围原样保留")
    void initExtension(@TempDir Path dir) throws IOException {
        ProjectManifest manifest = ProjectTemplates.init(
                "extension", "purchase-order-clerk-ext", "vendor.pur/purchase-order-clerk", null, dir);
        assertThat(manifest.target()).isEqualTo("vendor.pur/purchase-order-clerk");
        assertThat(Files.readString(dir.resolve("assets/purchase-order-clerk-ext.yaml")))
                .contains("target: vendor.pur/purchase-order-clerk@^1.0");
        assertThat(manifest.assets())
                .extracting(ProjectManifest.Entry::file)
                .containsExactly(
                        "assets/purchase-order-clerk-ext.yaml",
                        "evals/purchase-order-clerk-ext-smoke.yaml",
                        "skills/purchase-order-clerk-ext-skill.yaml");

        Path pinned = dir.resolve("pinned");
        ProjectTemplates.init("extension", "e2", "vendor.pur/purchase-order-clerk@^1.2", null, pinned);
        assertThat(Files.readString(pinned.resolve("assets/e2.yaml")))
                .contains("target: vendor.pur/purchase-order-clerk@^1.2");
    }

    @Test
    @DisplayName("a2a 模板：cardUrl 进清单、没有资产条目，TS 类名转帕斯卡")
    void initA2a(@TempDir Path dir) throws IOException {
        ProjectManifest py = ProjectTemplates.init("a2a-python", "supplier-mood", null, null, dir.resolve("py"));
        assertThat(py.cardUrl()).isEqualTo("http://127.0.0.1:4300");
        assertThat(py.assets()).isEmpty();
        assertThatThrownBy(() -> ProjectTemplates.init("a2a-ts", "x", "vendor.pur/a", null, dir.resolve("ts")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不使用 --target");
        ProjectTemplates.init("a2a-ts", "supplier-mood", null, null, dir.resolve("ts"));
        assertThat(Files.readString(dir.resolve("ts/src/executor.ts"))).contains("class SupplierMoodExecutor");
        assertThat(Files.readString(dir.resolve("ts/src/app.ts"))).contains("new SupplierMoodExecutor");
        assertThat(Files.readString(dir.resolve("ts/package.json"))).contains("\"name\": \"supplier-mood\"");
    }

    @Test
    @DisplayName("目录非空、名称或模板不合法、skill 模板不能 init 都被拒绝")
    void rejectsBadInit(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("busy.txt"), "x");
        assertThatThrownBy(() -> ProjectTemplates.init("agent", "a", null, null, dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非空");
        assertThatThrownBy(() -> ProjectTemplates.init("agent", "大写名字", null, null, dir.resolve("x1")))
                .hasMessageContaining("名称");
        assertThatThrownBy(() -> ProjectTemplates.init("nope", "a", null, null, dir.resolve("x2")))
                .hasMessageContaining("未知的模板");
        assertThatThrownBy(() ->
                        ProjectTemplates.init(ProjectTemplates.SKILL_TEMPLATE, "a", null, null, dir.resolve("x3")))
                .hasMessageContaining("不能用于 init");
        assertThatThrownBy(() -> ProjectTemplates.init("agent", "a", "vendor.pur/x", null, dir.resolve("x4")))
                .hasMessageContaining("不使用 --target");
        assertThatThrownBy(() -> ProjectTemplates.init("extension", "a", "not-a-ref", null, dir.resolve("x5")))
                .hasMessageContaining("--target");
    }
}

package dev.eab.platform.procode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.platform.studio.StudioStores;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 草稿导出为代码工程：文件集与 init 同构，清单带回 draftId 供 push 找回草稿 */
class ProjectExportTest {

    private static final String NAME = "purchase-order-clerk-ext";
    private static final String TARGET = "vendor.pur/purchase-order-clerk";

    private static StudioStores.Draft draft(String kind, String namespace, String name, String yaml, String evalYaml) {
        return new StudioStores.Draft(
                "drf-ab12cd34",
                "t001",
                "chengong",
                kind,
                namespace,
                name,
                "订单交付有问题时从备选供应商补货",
                6,
                yaml,
                evalYaml,
                null,
                "2026-10-18T08:00:00Z",
                "2026-10-18T08:00:00Z");
    }

    @Test
    @DisplayName("扩展草稿导出：文件集来自 init 模板（不含技能脚手架），清单回写 ref 与 draftId")
    void exportsExtensionDraft() {
        Map<String, String> vars = ProjectTemplates.vars(NAME, TARGET, TARGET + "@^1.2");
        String yaml = ProjectTemplates.render("extension", "assets/extension.yaml", vars)
                .replace("<改我>", "t001");
        // 评测集用自己的名字（故事 E 的冒烟集不叫 <资产名>-smoke），导出路径跟着评测集的名字走
        String evalYaml = ProjectTemplates.render("extension", "evals/extension-smoke.yaml", vars)
                .replace("<改我>", "t001")
                .replace(NAME + "-smoke", "purchase-clerk-ext-smoke");
        Map<String, String> files =
                ProjectExport.files(draft("AgentExtension", "tenant.t001.pur", NAME, yaml, evalYaml));

        Set<String> expected = new HashSet<>(ProjectTemplates.filePaths("extension", NAME, TARGET, null));
        expected.remove("skills/" + NAME + "-skill.yaml");
        expected.remove("evals/" + NAME + "-smoke.yaml");
        expected.add("evals/purchase-clerk-ext-smoke.yaml");
        assertThat(files.keySet()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(files.get("assets/" + NAME + ".yaml")).isEqualTo(yaml);
        assertThat(files.get("evals/purchase-clerk-ext-smoke.yaml")).isEqualTo(evalYaml);
        assertThat(files.get("README.md")).contains(NAME).doesNotContain("{{");

        ProjectManifest manifest = ProjectManifest.read(files.get(ProjectManifest.FILE));
        assertThat(manifest.template()).isEqualTo("extension");
        assertThat(manifest.user()).isEqualTo("chengong");
        assertThat(manifest.target()).isEqualTo(TARGET);
        assertThat(manifest.assets())
                .containsExactly(
                        new ProjectManifest.Entry(
                                "assets/" + NAME + ".yaml",
                                "AgentExtension",
                                "tenant.t001.pur/" + NAME,
                                "drf-ab12cd34"),
                        new ProjectManifest.Entry(
                                "evals/purchase-clerk-ext-smoke.yaml",
                                "EvalSuite",
                                "tenant.t001.evals/purchase-clerk-ext-smoke",
                                "drf-ab12cd34"));
    }

    @Test
    @DisplayName("Agent 草稿导出：文件集与 init 产物完全一致")
    void exportsAgentDraft() {
        Map<String, String> vars = ProjectTemplates.vars("credit-clerk", null, null);
        String yaml =
                ProjectTemplates.render("agent", "assets/agent.yaml", vars).replace("<改我>", "t001");
        String evalYaml =
                ProjectTemplates.render("agent", "evals/agent-smoke.yaml", vars).replace("<改我>", "t001");
        Map<String, String> files =
                ProjectExport.files(draft("Agent", "tenant.t001.agents", "credit-clerk", yaml, evalYaml));
        assertThat(files.keySet())
                .containsExactlyInAnyOrderElementsOf(ProjectTemplates.filePaths("agent", "credit-clerk", null, null));
    }

    @Test
    @DisplayName("技能草稿导出：技能放 skills/，没有评测集就不产生 evals 条目")
    void exportsSkillDraft() {
        String yaml = "kind: Skill\nmetadata:\n  name: alternative-supplier-lookup\n  namespace: tenant.t001.skills\n";
        Map<String, String> files =
                ProjectExport.files(draft("Skill", "tenant.t001.skills", "alternative-supplier-lookup", yaml, null));
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "skills/alternative-supplier-lookup.yaml", ProjectManifest.FILE, "README.md", ".gitignore");
        assertThat(files.keySet())
                .containsAll(ProjectTemplates.filePaths(
                        ProjectTemplates.SKILL_TEMPLATE, "alternative-supplier-lookup", null, null));
        ProjectManifest manifest = ProjectManifest.read(files.get(ProjectManifest.FILE));
        assertThat(manifest.assets())
                .containsExactly(new ProjectManifest.Entry(
                        "skills/alternative-supplier-lookup.yaml",
                        "Skill",
                        "tenant.t001.skills/alternative-supplier-lookup",
                        "drf-ab12cd34"));
    }

    @Test
    @DisplayName("评测集语法坏掉也导出原文，评测名退回草稿名加 -smoke")
    void exportsBrokenEvalYaml() {
        String yaml = "kind: AgentExtension\nmetadata:\n  name: " + NAME + "\n  namespace: tenant.t001.pur\n"
                + "spec:\n  target: " + TARGET + "@^1.0\n";
        Map<String, String> files =
                ProjectExport.files(draft("AgentExtension", "tenant.t001.pur", NAME, yaml, "not: [valid"));
        assertThat(files.get("evals/" + NAME + "-smoke.yaml")).isEqualTo("not: [valid");
        ProjectManifest manifest = ProjectManifest.read(files.get(ProjectManifest.FILE));
        assertThat(manifest.assets())
                .extracting(ProjectManifest.Entry::ref)
                .containsExactly("tenant.t001.pur/" + NAME, null);
    }

    @Test
    @DisplayName("不支持的草稿类型明确报错")
    void rejectsUnsupportedKind() {
        assertThatThrownBy(() ->
                        ProjectExport.files(draft("EvalSuite", "tenant.t001.evals", "x", "kind: EvalSuite\n", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("暂不支持导出 EvalSuite");
    }

    @Test
    @DisplayName("zip 打包：条目名与文件集一致，内容逐字节相同")
    void zipsDraft() throws IOException {
        Map<String, String> vars = ProjectTemplates.vars(NAME, TARGET, null);
        String yaml = ProjectTemplates.render("extension", "assets/extension.yaml", vars)
                .replace("<改我>", "t001");
        String evalYaml = ProjectTemplates.render("extension", "evals/extension-smoke.yaml", vars)
                .replace("<改我>", "t001");
        StudioStores.Draft draft = draft("AgentExtension", "tenant.t001.pur", NAME, yaml, evalYaml);
        Map<String, String> files = ProjectExport.files(draft);
        byte[] zip = ProjectExport.zip(draft);
        assertThat(zip[0]).isEqualTo((byte) 'P');
        assertThat(zip[1]).isEqualTo((byte) 'K');
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.add(entry.getName());
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .as(entry.getName())
                        .isEqualTo(files.get(entry.getName()));
            }
        }
        assertThat(entries).containsExactlyInAnyOrderElementsOf(files.keySet());
    }
}

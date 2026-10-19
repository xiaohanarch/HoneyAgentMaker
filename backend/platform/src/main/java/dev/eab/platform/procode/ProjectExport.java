package dev.eab.platform.procode;

import dev.eab.platform.studio.StudioStores;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * 草稿导出为代码工程（ADR 0014）：文件集与 ProjectTemplates.init 同构（同一份模板渲染 README），
 * 清单里带 draftId，推回平台（pnpm eab push）时据此找回同一条草稿——界面与文件是同一份数据的两种视图。
 */
public final class ProjectExport {

    private ProjectExport() {}

    /** 导出的文件集（相对路径 → 文件内容），与 init 产物一一对应 */
    public static Map<String, String> files(StudioStores.Draft draft) {
        return files(
                draft.kind(), draft.name(), draft.owner(), draft.ref(), draft.id(), draft.yaml(), draft.evalYaml());
    }

    /**
     * 单个草稿的文件集。除导出端点外，eab pull --draft 也走这里：拉取与导出产物逐字节一致，
     * 两种入口不需要各维护一份文件组织规则。
     */
    public static Map<String, String> files(
            String kind, String name, String owner, String ref, String draftId, String yaml, String evalYaml) {
        String template = ProjectManifest.templateForKind(kind);
        if (template == null) throw new IllegalArgumentException("暂不支持导出 " + kind + " 类型的草稿");
        JsonNode tree = Json.YAML.readTree(yaml);
        String assetName = tree.path("metadata").path("name").asString(name);
        String targetKey = null;
        if ("AgentExtension".equals(kind)) {
            String target = tree.path("spec").path("target").asString(null);
            if (target != null) targetKey = AssetRef.parse(target).key();
        }
        Map<String, String> vars = ProjectTemplates.vars(assetName, targetKey, null);

        String assetFile = ProjectTemplates.assetPath(kind, assetName);
        List<ProjectManifest.Entry> assets = new ArrayList<>();
        assets.add(new ProjectManifest.Entry(assetFile, kind, ref, draftId));
        Map<String, String> files = new LinkedHashMap<>();
        files.put(assetFile, yaml);
        if (evalYaml != null) {
            String evalName = name + "-smoke";
            String evalRef = null;
            try {
                JsonNode eval = Json.YAML.readTree(evalYaml);
                evalName = eval.path("metadata").path("name").asString(evalName);
                String namespace = eval.path("metadata").path("namespace").asString(null);
                if (namespace != null) evalRef = namespace + "/" + evalName;
            } catch (JacksonException e) {
                // 评测集草稿语法坏掉时也照样导出原文，修正后再推回平台
            }
            assets.add(new ProjectManifest.Entry(ProjectTemplates.evalPath(evalName), "EvalSuite", evalRef, draftId));
            files.put(ProjectTemplates.evalPath(evalName), evalYaml);
        }
        files.put(ProjectManifest.FILE, new ProjectManifest(template, null, owner, targetKey, null, assets).toYaml());
        files.put("README.md", ProjectTemplates.render(template, "README.md", vars));
        files.put(".gitignore", ProjectTemplates.read(template, "gitignore"));
        return files;
    }

    /** 打成 zip：条目名用正斜杠，内容 UTF-8 */
    public static byte[] zip(StudioStores.Draft draft) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : files(draft).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}

package dev.eab.platform.procode;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.eab.spec.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * 代码工程清单 eab-project.yaml：工程模板、平台地址、登录用户、扩展目标与资产索引（ADR 0014）。
 * init 与草稿导出都从这一个模型生成清单，保证两种入口产出的工程同构。
 *
 * @param platform 平台地址，例如 http://127.0.0.1:4000；未定时命令行用默认地址
 * @param target 扩展模板要扩展的标品 Agent（不带版本范围），其余模板为 null
 * @param cardUrl A2A 模板的 Agent Card 地址，接入平台时据此读取卡片；其余模板为 null
 * @param assets 资产文件索引：file 相对工程根目录，kind 是资产类型；ref 与 draftId 在 push 后回写，
 *               ref 用于确认本地文件对应注册中心里的哪个资产，draftId 让下一次 push 找回同一条草稿
 */
public record ProjectManifest(
        String template, String platform, String user, String target, String cardUrl, List<Entry> assets) {

    /** 清单文件名，位于工程根目录 */
    public static final String FILE = "eab-project.yaml";

    /** 全部已知模板：init 提供四类，skill 只在拉取与导出技能草稿时出现 */
    public static final List<String> TEMPLATES = List.of("agent", "extension", "a2a-python", "a2a-ts", "skill");

    public record Entry(String file, String kind, String ref, String draftId) {}

    /** 写给人编辑的 YAML：省略 null 字段，字符串尽量不加引号，不写文档起始标记 */
    static final YAMLMapper YAML = YAMLMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    public ProjectManifest {
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    /**
     * 解析清单内容。清单是开发者手写的文件，逐字段读取并给出明确的错误信息；
     * 未知字段直接忽略（不走 Json.MAPPER 的严格绑定），清单格式向后兼容。
     */
    public static ProjectManifest read(String yaml) {
        JsonNode tree;
        try {
            tree = Json.YAML.readTree(yaml);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(FILE + " 不是合法的 YAML：" + e.getMessage());
        }
        String template = tree.path("template").asString(null);
        if (template == null || template.isBlank()) throw new IllegalArgumentException(FILE + " 缺少 template 字段");
        if (!TEMPLATES.contains(template)) throw new IllegalArgumentException("未知的模板：" + template);
        List<Entry> assets = new ArrayList<>();
        if (tree.path("assets") instanceof ArrayNode array) {
            for (JsonNode entry : array) {
                String file = entry.path("file").asString(null);
                String kind = entry.path("kind").asString(null);
                if (file == null || file.isBlank()) throw new IllegalArgumentException("资产条目缺少 file 字段");
                if (kind == null || kind.isBlank()) throw new IllegalArgumentException("资产条目 " + file + " 缺少 kind 字段");
                if (file.contains("..") || file.startsWith("/") || file.contains("\\")) {
                    throw new IllegalArgumentException("资产条目 " + file + " 的 file 不是工程内的相对路径");
                }
                assets.add(new Entry(
                        file,
                        kind,
                        entry.path("ref").asString(null),
                        entry.path("draftId").asString(null)));
            }
        }
        return new ProjectManifest(
                template,
                tree.path("platform").asString(null),
                tree.path("user").asString(null),
                tree.path("target").asString(null),
                tree.path("cardUrl").asString(null),
                assets);
    }

    /** 读工程根目录下的清单；目录不对时提示操作者 */
    public static ProjectManifest read(Path projectDir) {
        String yaml;
        try {
            yaml = Files.readString(projectDir.resolve(FILE));
        } catch (IOException e) {
            throw new IllegalArgumentException("当前目录不是 EAB 代码工程（找不到 " + FILE + "）");
        }
        return read(yaml);
    }

    public String toYaml() {
        return YAML.writeValueAsString(this);
    }

    /** 把条目按 file 合并进资产索引并返回新清单；file 相同时替换（push 回写 draftId 用） */
    public ProjectManifest withAsset(Entry entry) {
        List<Entry> merged = new ArrayList<>();
        boolean replaced = false;
        for (Entry existing : assets) {
            if (existing.file().equals(entry.file())) {
                merged.add(entry);
                replaced = true;
            } else {
                merged.add(existing);
            }
        }
        if (!replaced) merged.add(entry);
        return new ProjectManifest(template, platform, user, target, cardUrl, merged);
    }

    public Optional<Entry> asset(String file) {
        return assets.stream().filter(entry -> entry.file().equals(file)).findFirst();
    }

    public boolean isA2a() {
        return template != null && template.startsWith("a2a-");
    }

    /** 资产类型对应的工程模板；EvalSuite 随主资产走，不单独成工程 */
    public static String templateForKind(String kind) {
        return switch (kind) {
            case "Agent" -> "agent";
            case "AgentExtension" -> "extension";
            case "Skill" -> "skill";
            default -> null;
        };
    }
}

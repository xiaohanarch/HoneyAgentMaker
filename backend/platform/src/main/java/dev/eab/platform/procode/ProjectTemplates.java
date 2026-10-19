package dev.eab.platform.procode;

import dev.eab.spec.AssetRef;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * init 模板（ADR 0014）：classpath:procode/templates 下每套模板一份文件清单，占位符用 String.replace 替换，
 * 不引模板引擎。文件清单写死在代码里：jar 里列不出资源目录，资源缺失会直接在启动测试里暴露。
 * 导出（ProjectExport）用同一份模板渲染 README，保证导出工程与 init 产物同构。
 */
public final class ProjectTemplates {

    private ProjectTemplates() {}

    /** init 命令提供的四类模板：声明式 Agent、标品扩展、A2A 外部 Agent 的 Python 与 TS 工程 */
    public static final List<String> INIT_TEMPLATES = List.of("agent", "extension", "a2a-python", "a2a-ts");

    /** 拉取或导出技能草稿时使用的模板，init 不提供 */
    public static final String SKILL_TEMPLATE = "skill";

    private static final Pattern ASSET_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /** A2A 模板的本机默认卡片地址（端口避开演示样例的 4200/4210） */
    private static final Map<String, String> A2A_CARD_URL =
            Map.of("a2a-python", "http://127.0.0.1:4300", "a2a-ts", "http://127.0.0.1:4310");

    /**
     * resource 是模板目录内的相对路径；path 是写出到工程的相对路径，可含占位符。
     * resource 不能以点开头：maven-jar-plugin 底层的 plexus-archiver 按 Ant 默认排除清单跳过
     * 点开头的 gitignore 等文件，模板里存成 gitignore，写出时才落成 .gitignore。
     */
    record TemplateFile(String resource, String path) {}

    /** 模板里 gitignore 文件的资源名（不带点，理由见 TemplateFile 的说明） */
    private static final String GITIGNORE = "gitignore";

    private static final Map<String, List<TemplateFile>> FILES = Map.of(
            "agent",
            List.of(
                    new TemplateFile("assets/agent.yaml", "assets/{{name}}.yaml"),
                    new TemplateFile("evals/agent-smoke.yaml", "evals/{{name}}-smoke.yaml"),
                    new TemplateFile("README.md", "README.md"),
                    new TemplateFile(GITIGNORE, ".gitignore")),
            "extension",
            List.of(
                    new TemplateFile("assets/extension.yaml", "assets/{{name}}.yaml"),
                    new TemplateFile("evals/extension-smoke.yaml", "evals/{{name}}-smoke.yaml"),
                    new TemplateFile("skills/skill.yaml", "skills/{{name}}-skill.yaml"),
                    new TemplateFile("README.md", "README.md"),
                    new TemplateFile(GITIGNORE, ".gitignore")),
            SKILL_TEMPLATE,
            List.of(new TemplateFile("README.md", "README.md"), new TemplateFile(GITIGNORE, ".gitignore")),
            "a2a-python",
            List.of(
                    new TemplateFile("agent.py", "agent.py"),
                    new TemplateFile("pyproject.toml", "pyproject.toml"),
                    new TemplateFile("README.md", "README.md"),
                    new TemplateFile(GITIGNORE, ".gitignore")),
            "a2a-ts",
            List.of(
                    new TemplateFile("src/app.ts", "src/app.ts"),
                    new TemplateFile("src/main.ts", "src/main.ts"),
                    new TemplateFile("src/auth.ts", "src/auth.ts"),
                    new TemplateFile("src/callback.ts", "src/callback.ts"),
                    new TemplateFile("src/executor.ts", "src/executor.ts"),
                    new TemplateFile("package.json", "package.json"),
                    new TemplateFile("tsconfig.json", "tsconfig.json"),
                    new TemplateFile("README.md", "README.md"),
                    new TemplateFile(GITIGNORE, ".gitignore")));

    /** 资产文件在工程里的位置：Agent 与扩展放 assets/，技能放 skills/ */
    public static String assetPath(String kind, String name) {
        return switch (kind) {
            case "Agent", "AgentExtension" -> "assets/" + name + ".yaml";
            case "Skill" -> "skills/" + name + ".yaml";
            default -> throw new IllegalArgumentException("不支持的资产类型：" + kind);
        };
    }

    /** 评测集文件名沿用评测集自己的名字（模板生成的冒烟集是 <name>-smoke） */
    public static String evalPath(String evalName) {
        return "evals/" + evalName + ".yaml";
    }

    /**
     * 解包模板到目录并生成工程清单。目录必须不存在或为空，防止覆盖别人的工程。
     *
     * @param target 扩展模板必填（vendor.&lt;域&gt;/&lt;标品Agent&gt;，可带版本范围），其余模板必须为 null
     */
    public static ProjectManifest init(String template, String name, String target, String platform, Path dir) {
        requireKnown(template);
        if (!INIT_TEMPLATES.contains(template)) {
            throw new IllegalArgumentException(
                    "模板 " + template + " 不能用于 init（可选 " + String.join(" | ", INIT_TEMPLATES) + "）");
        }
        if (name == null || !ASSET_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("名称 " + name + " 不合法：只能用小写字母、数字、连字符");
        }
        String targetKey = null;
        String targetRange = null;
        if ("extension".equals(template)) {
            if (target == null || !AssetRef.isValid(target)) {
                throw new IllegalArgumentException(
                        "扩展模板需要 --target vendor.<域>/<标品Agent>，例如 vendor.pur/purchase-order-clerk");
            }
            AssetRef ref = AssetRef.parse(target);
            targetKey = ref.key();
            targetRange = ref.range() == null ? targetKey + "@^1.0" : ref.toString();
        } else if (target != null) {
            throw new IllegalArgumentException("模板 " + template + " 不使用 --target");
        }
        prepare(dir);
        Map<String, String> vars = vars(name, targetKey, targetRange);
        for (TemplateFile file : FILES.get(template)) {
            write(dir, render(template, file.resource(), vars), Path.of(renderPlaceholders(file.path(), vars)));
        }
        List<ProjectManifest.Entry> assets = new ArrayList<>();
        switch (template) {
            case "agent" -> {
                assets.add(new ProjectManifest.Entry(assetPath("Agent", name), "Agent", null, null));
                assets.add(new ProjectManifest.Entry(evalPath(name + "-smoke"), "EvalSuite", null, null));
            }
            case "extension" -> {
                assets.add(new ProjectManifest.Entry(assetPath("AgentExtension", name), "AgentExtension", null, null));
                assets.add(new ProjectManifest.Entry(evalPath(name + "-smoke"), "EvalSuite", null, null));
                assets.add(new ProjectManifest.Entry("skills/" + name + "-skill.yaml", "Skill", null, null));
            }
            default -> {}
        }
        ProjectManifest manifest =
                new ProjectManifest(template, platform, null, targetKey, A2A_CARD_URL.get(template), assets);
        write(dir, manifest.toYaml(), Path.of(ProjectManifest.FILE));
        return manifest;
    }

    /** 占位符与取值：name、namePascal、target、targetName、targetRange */
    static Map<String, String> vars(String name, String targetKey, String targetRange) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{{name}}", name);
        vars.put("{{namePascal}}", pascal(name));
        if (targetKey != null) {
            vars.put("{{target}}", targetKey);
            vars.put("{{targetName}}", AssetRef.parse(targetKey).name());
            vars.put("{{targetRange}}", targetRange == null ? targetKey + "@^1.0" : targetRange);
        }
        return vars;
    }

    /** 读模板文件原文；资源缺失说明打包出了问题，直接失败 */
    public static String read(String template, String resource) {
        String path = "/procode/templates/" + template + "/" + resource;
        try (InputStream in = ProjectTemplates.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("模板文件缺失：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读模板并替换占位符 */
    public static String render(String template, String resource, Map<String, String> vars) {
        String content = read(template, resource);
        for (Map.Entry<String, String> var : vars.entrySet()) {
            content = content.replace(var.getKey(), var.getValue());
        }
        return content;
    }

    /** init 会生成哪些文件（相对路径，占位符已渲染）；也用于断言导出与 init 同构 */
    public static List<String> filePaths(String template, String name, String targetKey, String targetRange) {
        requireKnown(template);
        Map<String, String> vars = vars(name, targetKey, targetRange);
        List<String> paths = new ArrayList<>();
        for (TemplateFile file : FILES.get(template)) {
            paths.add(renderPlaceholders(file.path(), vars));
        }
        paths.add(ProjectManifest.FILE);
        return paths;
    }

    /** 路径里的占位符替换（写出与清单共用，路径永远跟着渲染走） */
    private static String renderPlaceholders(String path, Map<String, String> vars) {
        for (Map.Entry<String, String> var : vars.entrySet()) {
            path = path.replace(var.getKey(), var.getValue());
        }
        return path;
    }

    private static void requireKnown(String template) {
        if (template == null || !FILES.containsKey(template)) {
            throw new IllegalArgumentException(
                    "未知的模板：" + template + "（可选 " + String.join(" | ", ProjectManifest.TEMPLATES) + "）");
        }
    }

    /** 目标目录必须不存在或为空 */
    private static void prepare(Path dir) {
        if (Files.exists(dir)) {
            try (var stream = Files.list(dir)) {
                if (stream.findAny().isPresent()) throw new IllegalArgumentException("目录已存在且非空：" + dir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void write(Path dir, String content, Path relative) {
        Path file = dir.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写不出工程文件 " + file, e);
        }
    }

    /** supplier-risk → SupplierRisk，用于 TS 类名 */
    static String pascal(String name) {
        StringBuilder builder = new StringBuilder();
        for (String part : name.split("[-_]")) {
            if (part.isEmpty()) continue;
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}

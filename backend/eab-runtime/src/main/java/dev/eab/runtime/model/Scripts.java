package dev.eab.runtime.model;

import com.samskivert.mustache.Mustache;
import dev.eab.assembler.Conditions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 演示剧本（demo/scripts/*.yaml，格式见 demo/scripts/README.md）。脚本化模型按“Agent + 用户话术”匹配剧本，
 * 按本轮已经完成的工具调用批次数决定走到哪一步：calls 步返回一批工具调用，reply 步用模板基于真实的工具结果渲染最终回复，
 * 这样回复里的单号和金额都来自 DemoERP 的数据，而不是写死在剧本里。
 */
public final class Scripts {

    private Scripts() {}

    /**
     * 剧本里的值：以 = 开头的字符串是 CEL 表达式，其余字符串按 mustache 模板渲染，数字、布尔原样保留。
     */
    public record Call(String tool, Map<String, Object> args) {}

    /**
     * 一步：要么是一批工具调用（calls，可带思考步骤摘要 thought），要么是最终回复（reply，可带结构化结果 output）。
     */
    public record Step(String thought, List<Call> calls, String reply, Map<String, Object> output) {}

    /**
     * @param all 全部出现才匹配
     * @param any 任意一个出现即可；为空表示不要求
     * @param pattern 正则，命名分组写入 vars，例如 (?&lt;supplier&gt;百润精密)
     * @param user 只对某个用户生效
     */
    public record Match(List<String> all, List<String> any, String pattern, String user) {}

    /** @param agent namespace/name */
    public record Script(
            String id, String description, String agent, Match match, Map<String, String> vars, List<Step> steps) {}

    public record ScriptFile(List<Script> scripts) {}

    private static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern AGENT = Pattern.compile("^[a-z0-9.-]+/[a-z0-9-]+$");

    public static final class ScriptException extends RuntimeException {
        public ScriptException(String message) {
            super(message);
        }
    }

    /** 读取并校验一个剧本文件 */
    public static List<Script> parse(String yaml, String file) {
        ScriptFile parsed;
        try {
            parsed = YAML.readValue(yaml, ScriptFile.class);
        } catch (RuntimeException e) {
            throw new ScriptException(
                    file + "：剧本格式不对：" + e.getMessage().lines().findFirst().orElse(""));
        }
        if (parsed == null || parsed.scripts() == null || parsed.scripts().isEmpty()) {
            throw new ScriptException(file + "：缺少 scripts");
        }
        List<Script> scripts = new ArrayList<>();
        for (Script s : parsed.scripts()) scripts.add(validate(s, file));
        return scripts;
    }

    private static Script validate(Script s, String file) {
        String where = file + " 剧本 " + s.id() + "：";
        if (s.id() == null || !ID.matcher(s.id()).matches()) throw new ScriptException(where + "剧本编号用小写字母、数字和连字符");
        if (s.agent() == null || !AGENT.matcher(s.agent()).matches())
            throw new ScriptException(where + "agent 写成 namespace/name");
        if (s.match() == null) throw new ScriptException(where + "缺少 match");
        if (s.steps() == null || s.steps().isEmpty()) throw new ScriptException(where + "至少一步");
        for (Step step : s.steps()) {
            boolean calls = step.calls() != null && !step.calls().isEmpty();
            boolean reply = step.reply() != null && !step.reply().isBlank();
            if (calls == reply) throw new ScriptException(where + "每一步要么是 calls，要么是 reply");
            if (calls) {
                for (Call call : step.calls()) {
                    if (call.tool() == null || call.tool().isBlank()) throw new ScriptException(where + "调用缺少 tool");
                }
            }
        }
        if (s.match().pattern() != null) {
            try {
                Pattern.compile(s.match().pattern());
            } catch (RuntimeException e) {
                throw new ScriptException(where + "pattern 不是合法的正则");
            }
        }
        Match match = new Match(
                s.match().all() == null ? List.of() : s.match().all(),
                s.match().any() == null ? List.of() : s.match().any(),
                s.match().pattern(),
                s.match().user());
        return new Script(s.id(), s.description(), s.agent(), match, s.vars() == null ? Map.of() : s.vars(), s.steps());
    }

    /** 读取目录下的全部 *.yaml，按文件名排序；同一编号重复时报错 */
    public static List<Script> loadDirectory(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Script> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                all.addAll(parse(Files.readString(file), file.getFileName().toString()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> ids = new ArrayList<>();
        for (Script s : all) {
            if (ids.contains(s.id())) throw new ScriptException("剧本编号重复：" + s.id());
            ids.add(s.id());
        }
        return all;
    }

    public record Matched(Script script, Map<String, String> vars) {}

    public static Matched match(List<Script> scripts, String agent, String userId, String utterance) {
        for (Script script : scripts) {
            if (!script.agent().equals(agent)) continue;
            Match m = script.match();
            if (m.user() != null && !m.user().equals(userId)) continue;
            if (!m.all().stream().allMatch(utterance::contains)) continue;
            if (!m.any().isEmpty() && m.any().stream().noneMatch(utterance::contains)) continue;
            Map<String, String> vars = new LinkedHashMap<>(script.vars());
            if (m.pattern() != null) {
                Matcher found = Pattern.compile(m.pattern()).matcher(utterance);
                if (!found.find()) continue;
                for (String group : namedGroups(m.pattern())) {
                    String value = found.group(group);
                    if (value != null) vars.put(group, value);
                }
            }
            return new Matched(script, vars);
        }
        return null;
    }

    private static List<String> namedGroups(String regex) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>").matcher(regex);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    /** 工具名作为模板变量时把点和连字符换成下划线：supplier-lookup → tools.supplier_lookup */
    public static String templateKey(String toolName) {
        return toolName.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** 缺少的变量和 null 都渲染为空字符串：工具结果为空列表或报错时，模板不会因为取不到 items.0 而失败 */
    private static final Mustache.Compiler MUSTACHE =
            Mustache.compiler().escapeHTML(false).defaultValue("");

    public static String render(String template, Map<String, Object> view) {
        return MUSTACHE.compile(template).execute(view);
    }

    /** 剧本里的参数值和结构化结果：= 开头按 CEL 求值，字符串按模板渲染，其余原样 */
    public static Object resolveValue(Object value, Map<String, Object> view) {
        if (!(value instanceof String s)) return value;
        if (s.startsWith("=")) return Conditions.evaluate(s.substring(1), view);
        return render(s, view);
    }

    /** 渲染为空字符串、null 的参数视为未提供，便于剧本写可选参数 */
    public static Map<String, Object> resolveRecord(Map<String, Object> record, Map<String, Object> view) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (record == null) return out;
        record.forEach((k, v) -> {
            Object resolved = resolveValue(v, view);
            if (resolved != null && !"".equals(resolved)) out.put(k, resolved);
        });
        return out;
    }

    /** 剧本未覆盖时的规则回复，界面上标注“脚本化模型” */
    public static String fallbackReply(String agentName, List<String> examples) {
        String list = examples.isEmpty()
                ? ""
                : "\n\n可以试试这些问法：\n"
                        + String.join("\n", examples.stream().map(e -> "- " + e).toList());
        return "（脚本化模型）这句话不在演示剧本里，" + agentName + "暂时无法给出分析。配置真实模型后可以回答任意问法。" + list;
    }
}

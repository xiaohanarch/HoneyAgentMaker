package dev.eab.platform.extend;

import dev.eab.spec.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * 扩展草稿的编辑（主提示词 5.3 节、10.4 节）：按扩展点类型分页，每一页只改自己负责的字段。草稿以 YAML 为准，
 * 列表为空时去掉整段（schema 要求 add 至少一项），这样“清空”也能表达。
 */
final class ExtensionComposer {

    private ExtensionComposer() {}

    /** 编辑页与扩展点类型的对应 */
    static final Map<String, String> SECTIONS = sections();

    private static Map<String, String> sections() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("instructions", "instructions.append");
        s.put("tools", "tools.add");
        s.put("skills", "skills.add");
        s.put("knowledge", "knowledge.add");
        s.put("hooks", "hooks");
        s.put("approvals", "approvals.add");
        s.put("model", "model.select");
        return s;
    }

    static final List<String> HOOK_EVENTS = List.of("beforeToolCall", "afterToolCall", "onResult", "onFailure");

    static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    static String namespaceFor(String tenantId) {
        return "tenant." + tenantId + ".extensions";
    }

    static String evalNamespaceFor(String tenantId) {
        return "tenant." + tenantId + ".evals";
    }

    static ObjectNode skeleton(String tenantId, String owner, String name, String target, String targetName) {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "AgentExtension");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespaceFor(tenantId));
        metadata.put("version", "0.1.0");
        metadata.put("displayName", targetName + " · 租户扩展");
        metadata.put("description", "对 " + targetName + " 的租户扩展");
        metadata.put("owner", owner);
        asset.put("metadata", metadata);
        asset.put("spec", Map.of("target", target));
        return Json.MAPPER.valueToTree(asset);
    }

    static ObjectNode read(String yaml) {
        JsonNode node = Json.YAML.readTree(yaml);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("草稿不是一个 YAML 对象");
        return object;
    }

    static String write(ObjectNode tree) {
        return YAML.writeValueAsString(tree);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static void setAdd(ObjectNode spec, String field, List<?> values) {
        if (values.isEmpty()) {
            spec.remove(field);
            return;
        }
        ArrayNode add = spec.putObject(field).putArray("add");
        for (Object v : values) add.add((JsonNode) Json.MAPPER.valueToTree(v));
    }

    /** 采纳一页的取值，返回改动后的新树 */
    static ObjectNode apply(ObjectNode original, String section, Map<String, Object> value) {
        ObjectNode tree = original.deepCopy();
        ObjectNode spec = tree.get("spec") instanceof ObjectNode s ? s : tree.putObject("spec");
        switch (section) {
            case "instructions" -> {
                String append = value.get("append") instanceof String a ? a.strip() : "";
                if (append.isEmpty()) spec.remove("instructions");
                else spec.putObject("instructions").put("append", append);
                List<?> constraints = list(value.get("constraints"));
                if (constraints.isEmpty()) spec.remove("constraints");
                else {
                    ArrayNode arr = spec.putObject("constraints").putArray("append");
                    constraints.forEach(c -> arr.add(String.valueOf(c)));
                }
            }
            case "tools" -> setAdd(spec, "tools", list(value.get("add")));
            case "skills" -> setAdd(spec, "skills", list(value.get("add")));
            case "knowledge" -> setAdd(spec, "knowledge", list(value.get("add")));
            case "approvals" -> setAdd(spec, "approvals", list(value.get("add")));
            case "hooks" -> {
                ObjectNode hooks = Json.MAPPER.createObjectNode();
                for (String event : HOOK_EVENTS) {
                    if (!(value.get(event) instanceof Map<?, ?> binding)) continue;
                    Map<String, Object> hook = new LinkedHashMap<>();
                    if (binding.get("workflow") instanceof String w && !w.isBlank()) hook.put("workflow", w);
                    else if (binding.get("skill") instanceof String k && !k.isBlank()) hook.put("skill", k);
                    else continue;
                    if (binding.get("when") instanceof String when && !when.isBlank()) hook.put("when", when);
                    hooks.set(event, Json.MAPPER.valueToTree(hook));
                }
                if (hooks.isEmpty()) spec.remove("hooks");
                else spec.set("hooks", hooks);
            }
            case "model" -> {
                if (value.get("profile") instanceof String p && !p.isBlank())
                    spec.putObject("model").put("profile", p);
                else spec.remove("model");
            }
            default -> throw new IllegalArgumentException("未知的扩展页：" + section);
        }
        return tree;
    }

    /** 扩展评测集草稿 */
    static String evalSuite(String tenantId, String owner, ObjectNode extension, List<Map<String, Object>> cases) {
        String name = extension.path("metadata").path("name").asString();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "EvalSuite");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name + "-smoke");
        metadata.put("namespace", evalNamespaceFor(tenantId));
        metadata.put("version", extension.path("metadata").path("version").asString("0.1.0"));
        metadata.put(
                "displayName", extension.path("metadata").path("displayName").asString(name) + " · 扩展评测集");
        metadata.put("owner", owner);
        asset.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        // 扩展评测集的被测对象是扩展本身（运行时叠加在目标标品 Agent 上）
        spec.put("target", extension.path("metadata").path("namespace").asString() + "/" + name);
        spec.put("visibility", "internal");
        spec.put("tier", "smoke");
        spec.put("minPassRate", 1);
        spec.put("cases", cases);
        asset.put("spec", spec);
        return YAML.writeValueAsString(asset);
    }
}

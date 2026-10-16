package dev.eab.platform.studio;

import dev.eab.spec.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 把向导每一步采纳的取值合并进草稿。草稿以 YAML 为准（YAML 编辑模式改的是同一份），这里在 YAML 解析出的树上按步骤改动：
 * 每一步只替换自己负责的字段，例如协作 Agent 只替换工具列表里的 agent 条目，不碰技能和 BO 工具。
 */
final class StudioDraftComposer {

    private StudioDraftComposer() {}

    static String namespaceFor(String tenantId) {
        return "tenant." + tenantId + ".agents";
    }

    static String evalNamespaceFor(String tenantId) {
        return "tenant." + tenantId + ".evals";
    }

    /** 新草稿：只有意图，其余字段用能通过 schema 的占位值，随向导逐步替换 */
    static ObjectNode skeleton(String tenantId, String owner, String name, String intent) {
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "Agent");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespaceFor(tenantId));
        metadata.put("version", "1.0.0");
        metadata.put("displayName", "新建 Agent");
        metadata.put("description", intent.strip());
        metadata.put("owner", owner);
        asset.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("expertise", intent.strip());
        spec.put("instructions", "目标：" + intent.strip());
        spec.put("constraints", List.of());
        spec.put("model", Map.of("profile", "reasoning", "temperature", 0.2));
        spec.put("tools", List.of(Map.of("builtin", "calculator")));
        spec.put("semanticPacks", List.of());
        spec.put("triggers", List.of(Map.of("type", "assistant")));
        spec.put("channels", List.of("assistant-web"));
        spec.put("writePolicy", Map.of("maxLevel", "L0"));
        spec.put("routing", Map.of("description", intent.strip(), "examples", List.of(intent.strip()), "direct", true));
        asset.put("spec", spec);
        return Json.MAPPER.valueToTree(asset);
    }

    static ObjectNode read(String yaml) {
        JsonNode node = Json.YAML.readTree(yaml);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("草稿不是一个 YAML 对象");
        return object;
    }

    static String write(ObjectNode tree) {
        return Json.YAML.writeValueAsString(tree);
    }

    private static ObjectNode obj(ObjectNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node instanceof ObjectNode o) return o;
        return parent.putObject(field);
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = Json.MAPPER.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    /** 工具列表里按条目类型替换：只保留不属于 kinds 的条目，再追加新的 */
    private static void replaceTools(ObjectNode spec, List<String> kinds, List<Map<String, Object>> added) {
        ArrayNode kept = Json.MAPPER.createArrayNode();
        if (spec.get("tools") instanceof ArrayNode tools) {
            for (JsonNode entry : tools) {
                boolean replaced = kinds.stream().anyMatch(entry::has);
                if (!replaced) kept.add(entry);
            }
        }
        for (Map<String, Object> entry : added) kept.add((JsonNode) Json.MAPPER.valueToTree(entry));
        spec.set("tools", kept);
    }

    /** 采纳一步的取值，返回改动后的新树 */
    static ObjectNode apply(ObjectNode original, String step, Map<String, Object> value) {
        ObjectNode tree = original.deepCopy();
        ObjectNode metadata = obj(tree, "metadata");
        ObjectNode spec = obj(tree, "spec");
        ObjectNode routing = obj(spec, "routing");
        switch (step) {
            case "purpose" -> {
                String intent = String.valueOf(value.get("intent"));
                metadata.put("description", intent);
                spec.put("expertise", intent);
                routing.put("description", intent);
            }
            case "naming" -> {
                if (value.get("name") instanceof String name) metadata.put("name", name);
                if (value.get("displayName") instanceof String d) metadata.put("displayName", d);
                if (value.get("description") instanceof String d) {
                    metadata.put("description", d);
                    routing.put("description", d);
                }
            }
            case "process" -> metadata.set("businessProcess", strings(StudioDefaults.strings(value.get("businessProcess"))));
            case "collaborators" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (String ref : StudioDefaults.strings(value.get("agents"))) {
                    // 远程 Agent 在 P7 接入后才有资产，连接别名形式的候选不写进草稿
                    if (!ref.startsWith("connection:")) entries.add(Map.of("agent", ref));
                }
                replaceTools(spec, List.of("agent", "remoteAgent"), entries);
            }
            case "knowledge" -> {
                spec.set("semanticPacks", strings(StudioDefaults.strings(value.get("semanticPacks"))));
                List<Map<String, Object>> entries = new ArrayList<>();
                for (String ref : StudioDefaults.strings(value.get("knowledgeSources"))) entries.add(Map.of("documents", ref));
                replaceTools(spec, List.of("documents"), entries);
            }
            case "tools" -> {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (String ref : StudioDefaults.strings(value.get("skills"))) entries.add(Map.of("skill", ref));
                Map<String, List<String>> bySource = new LinkedHashMap<>();
                if (value.get("tools") instanceof List<?> tools) {
                    for (Object t : tools) {
                        if (t instanceof Map<?, ?> m) {
                            bySource.computeIfAbsent(String.valueOf(m.get("source")), k -> new ArrayList<>())
                                    .add(String.valueOf(m.get("name")));
                        }
                    }
                }
                bySource.forEach((source, names) -> entries.add(Map.of("api", Map.of("source", source, "tools", names))));
                replaceTools(spec, List.of("skill", "api"), entries);
            }
            case "triggers" -> {
                spec.set("triggers", Json.MAPPER.valueToTree(value.getOrDefault("triggers", List.of(Map.of("type", "assistant")))));
                if (value.get("direct") instanceof Boolean direct) routing.put("direct", direct);
            }
            case "channels" -> spec.set("channels", strings(StudioDefaults.strings(value.get("channels"))));
            case "instructions" -> {
                if (value.get("instructions") instanceof String text) spec.put("instructions", text);
                spec.set("constraints", strings(StudioDefaults.strings(value.get("constraints"))));
            }
            case "model" -> {
                ObjectNode model = obj(spec, "model");
                if (value.get("profile") instanceof String p) model.put("profile", p);
                if (value.get("temperature") instanceof Number t) model.put("temperature", t.doubleValue());
            }
            case "examples" -> routing.set("examples", strings(StudioDefaults.strings(value.get("examples"))));
            default -> throw new IllegalArgumentException("未知的步骤：" + step);
        }
        return tree;
    }

    /** 评测集草稿 */
    static String evalSuite(String tenantId, String owner, ObjectNode agent, List<Map<String, Object>> cases) {
        String name = agent.path("metadata").path("name").asString();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "EvalSuite");
        asset.put(
                "metadata",
                Map.of(
                        "name", name + "-smoke",
                        "namespace", evalNamespaceFor(tenantId),
                        "version", agent.path("metadata").path("version").asString("1.0.0"),
                        "displayName", agent.path("metadata").path("displayName").asString(name) + " · 冒烟集",
                        "owner", owner));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("target", namespaceFor(tenantId) + "/" + name);
        spec.put("visibility", "internal");
        spec.put("tier", "smoke");
        spec.put("minPassRate", 1);
        spec.put("cases", cases);
        asset.put("spec", spec);
        return Json.YAML.writeValueAsString(asset);
    }
}

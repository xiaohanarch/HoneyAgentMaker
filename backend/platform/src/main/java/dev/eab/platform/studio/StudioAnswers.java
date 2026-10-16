package dev.eab.platform.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 核对模型给出的推荐：引用必须在候选里，标识要符合命名规则，档位和渠道要是已知取值。
 * 不合格的字段用检索推荐补上；关键字段整体不合格时返回 null，调用方退回检索推荐。
 */
final class StudioAnswers {

    private StudioAnswers() {}

    static final String NAME_PATTERN = "^[a-z][a-z0-9-]{2,40}$";

    static Map<String, Object> accept(
            String step, Map<String, Object> answer, Map<String, Object> candidates, Map<String, Object> baseline) {
        Map<String, Object> value = new LinkedHashMap<>(baseline);
        switch (step) {
            case "purpose" -> {
                if (!(answer.get("intent") instanceof String intent) || intent.isBlank()) return null;
                value.put("intent", intent.strip());
            }
            case "naming" -> {
                if (!(answer.get("name") instanceof String name) || !name.matches(NAME_PATTERN)) return null;
                if (!(answer.get("displayName") instanceof String displayName) || displayName.isBlank()) return null;
                value.put("name", name);
                value.put("displayName", displayName.strip());
                if (answer.get("description") instanceof String d && !d.isBlank()) value.put("description", d.strip());
            }
            case "process" -> {
                List<String> codes = known(answer.get("businessProcess"), refs(candidates.get("processes"), "code"));
                if (codes.isEmpty()) return null;
                value.put("businessProcess", codes);
            }
            case "collaborators" ->
                value.put("agents", known(answer.get("agents"), readOnly(candidates.get("agents"))));
            case "knowledge" -> {
                value.put(
                        "semanticPacks",
                        known(answer.get("semanticPacks"), refs(candidates.get("semanticPacks"), "ref")));
                value.put(
                        "knowledgeSources",
                        known(answer.get("knowledgeSources"), refs(candidates.get("knowledgeSources"), "ref")));
            }
            case "tools" -> {
                value.put("skills", known(answer.get("skills"), readOnly(candidates.get("skills"))));
                value.put("tools", knownTools(answer.get("tools"), candidates.get("tools")));
            }
            case "triggers" -> {
                if (answer.get("direct") instanceof Boolean direct) value.put("direct", direct);
            }
            case "channels" -> {
                List<String> channels = known(answer.get("channels"), refs(candidates.get("channels"), "value"));
                if (channels.isEmpty()) return null;
                value.put("channels", channels);
            }
            case "instructions" -> {
                if (!(answer.get("instructions") instanceof String text) || text.isBlank()) return null;
                value.put("instructions", text.strip());
                value.put("constraints", known(answer.get("constraints"), refs(candidates.get("rules"), "id")));
            }
            case "model" -> {
                if (!(answer.get("profile") instanceof String profile)
                        || !List.of("fast", "reasoning").contains(profile)) {
                    return null;
                }
                value.put("profile", profile);
                if (answer.get("temperature") instanceof Number t && t.doubleValue() >= 0 && t.doubleValue() <= 1) {
                    value.put("temperature", t.doubleValue());
                }
            }
            default -> {
                return null;
            }
        }
        return value;
    }

    /**
     * 只读候选的引用：新建的 Agent 默认写级别上限是 L0，写级别更高的协作 Agent、技能和工具不接受，
     * 否则组装时会报写级别越界。需要写操作时由开发者调高上限并配置确认点。
     */
    static Set<String> readOnly(Object candidates) {
        Set<String> result = new LinkedHashSet<>();
        if (!(candidates instanceof List<?> list)) return result;
        for (Object item : list) {
            Object level = item instanceof Map<?, ?> m ? m.get("writeLevel") : fieldOf(item, "writeLevel");
            Object ref = item instanceof Map<?, ?> m ? m.get("ref") : fieldOf(item, "ref");
            if (ref != null && (level == null || "L0".equals(level))) result.add(String.valueOf(ref));
        }
        return result;
    }

    /** 候选列表里某个字段的取值集合；候选是记录或 Map 都可以 */
    static Set<String> refs(Object candidates, String field) {
        Set<String> result = new LinkedHashSet<>();
        if (!(candidates instanceof List<?> list)) return result;
        for (Object item : list) {
            Object v = item instanceof Map<?, ?> m ? m.get(field) : fieldOf(item, field);
            if (v != null) result.add(String.valueOf(v));
        }
        return result;
    }

    private static Object fieldOf(Object record, String field) {
        try {
            return record.getClass().getMethod(field).invoke(record);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static List<String> known(Object values, Set<String> allowed) {
        List<String> result = new ArrayList<>();
        for (String v : StudioDefaults.strings(values)) {
            if (allowed.contains(v) && !result.contains(v)) result.add(v);
        }
        return result;
    }

    static List<Map<String, Object>> knownTools(Object values, Object candidates) {
        Set<String> allowed = new LinkedHashSet<>();
        if (candidates instanceof List<?> list) {
            for (Object c : list) {
                if (c instanceof StudioCatalog.Tool t && "L0".equals(t.writeLevel())) {
                    allowed.add(t.source() + "#" + t.name());
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(values instanceof List<?> list)) return result;
        for (Object v : list) {
            if (v instanceof Map<?, ?> m && allowed.contains(m.get("source") + "#" + m.get("name"))) {
                result.add(Map.of("source", String.valueOf(m.get("source")), "name", String.valueOf(m.get("name"))));
            }
        }
        return result;
    }
}

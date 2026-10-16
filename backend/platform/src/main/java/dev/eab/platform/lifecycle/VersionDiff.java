package dev.eab.platform.lifecycle;

import dev.eab.spec.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 两个版本的差异（主提示词第 12 节）。比较的是按查看者裁剪后的视图：租户视角下标品指令是占位块（带摘要），
 * 摘要不同说明内容变了，差异里只写“已变更”，不给出前后内容，也不给出摘要本身。
 */
public final class VersionDiff {

    private VersionDiff() {}

    /**
     * 一处差异。
     *
     * @param change added、removed、changed
     * @param redacted 是否是对查看者隐藏的内容（只显示“已变更”）
     */
    public record Change(String path, String change, String before, String after, boolean redacted) {}

    static final String HIDDEN = "已变更（内容对当前身份不可见）";

    public static List<Change> diff(JsonNode before, JsonNode after) {
        Map<String, JsonNode> a = new LinkedHashMap<>();
        Map<String, JsonNode> b = new LinkedHashMap<>();
        flatten("", before, a);
        flatten("", after, b);
        Set<String> paths = new LinkedHashSet<>(a.keySet());
        paths.addAll(b.keySet());
        List<Change> changes = new ArrayList<>();
        for (String path : paths) {
            if (path.equals("metadata.version")) continue;
            JsonNode x = a.get(path);
            JsonNode y = b.get(path);
            if (x != null && y != null && x.equals(y)) continue;
            boolean redacted = isRedacted(x) || isRedacted(y);
            String change = x == null ? "added" : y == null ? "removed" : "changed";
            changes.add(
                    redacted
                            ? new Change(path, change, null, HIDDEN, true)
                            : new Change(path, change, text(x), text(y), false));
        }
        return changes;
    }

    static boolean isRedacted(JsonNode node) {
        return node != null && node.isObject() && node.path("$redacted").asBoolean(false);
    }

    /** 叶子节点和裁剪占位块作为一项；数组按下标展开 */
    static void flatten(String prefix, JsonNode node, Map<String, JsonNode> out) {
        if (node == null) return;
        if (isRedacted(node) || node.isValueNode() || node.isNull()) {
            out.put(prefix, node);
        } else if (node.isObject()) {
            if (node.isEmpty()) out.put(prefix, node);
            for (var e : node.properties())
                flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out);
        } else if (node.isArray()) {
            if (node.isEmpty()) out.put(prefix, node);
            for (int i = 0; i < node.size(); i++) flatten(prefix + "[" + i + "]", node.get(i), out);
        }
    }

    static String text(JsonNode node) {
        if (node == null) return null;
        return node.isValueNode() ? node.asString() : Json.MAPPER.writeValueAsString(node);
    }
}

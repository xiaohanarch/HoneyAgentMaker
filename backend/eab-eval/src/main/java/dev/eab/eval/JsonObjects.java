package dev.eab.eval;

import dev.eab.spec.Json;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从模型回复里取出 JSON 对象：可能直接是 JSON，也可能包在代码块或说明文字里 */
public final class JsonObjects {

    private JsonObjects() {}

    public static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            Object parsed = Json.MAPPER.readValue(trimmed.substring(start, end + 1), Object.class);
            if (!(parsed instanceof Map<?, ?> map)) return null;
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

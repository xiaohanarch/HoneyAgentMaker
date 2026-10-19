package dev.eab.assembler;

import dev.cel.common.values.NullValue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** JSON 值与 CEL 运行时值之间的转换：整数用 long，小数用 double（BigDecimal 也折算成 double），null 用 CEL 的 NullValue */
final class CelValues {

    private CelValues() {}

    static Object toCel(Object value) {
        if (value == null) return NullValue.NULL_VALUE;
        if (value instanceof JsonNode node) return fromJson(node);
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Short s) return s.longValue();
        if (value instanceof Float f) return f.doubleValue();
        if (value instanceof BigDecimal d) return d.doubleValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toCel(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(toCel(v)));
            return out;
        }
        return value;
    }

    private static Object fromJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return NullValue.NULL_VALUE;
        if (node.isBoolean()) return node.asBoolean();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isNumber()) return node.asDouble();
        if (node.isString()) return node.asString();
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(v -> out.add(fromJson(v)));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            out.put(entry.getKey(), fromJson(entry.getValue()));
        }
        return out;
    }

    /** CEL 结果转成普通 Java 值，便于序列化成 JSON */
    static Object fromCel(Object value) {
        if (value == null || value instanceof NullValue) return null;
        if (value instanceof com.google.common.primitives.UnsignedLong u) return u.longValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), fromCel(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(fromCel(v)));
            return out;
        }
        return value;
    }
}

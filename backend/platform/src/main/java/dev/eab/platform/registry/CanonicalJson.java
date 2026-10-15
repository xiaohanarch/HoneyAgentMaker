package dev.eab.platform.registry;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * JSON 规范化（RFC 8785 JCS）：对象键按 UTF-16 码元排序，字符串只做必要的转义，数字按 ECMAScript 的格式输出。
 * 资产的内容哈希和签名都基于它，同一份内容无论字段顺序如何，哈希都相同。
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String of(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /** 内容哈希，形如 sha256:&lt;十六进制&gt; */
    public static String hashOf(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.asBoolean());
        } else if (node.isNumber()) {
            out.append(number(node));
        } else if (node.isString()) {
            string(node.asString(), out);
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                write(node.get(i), out);
            }
            out.append(']');
        } else {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            // String.compareTo 比较的正是 UTF-16 码元，符合 RFC 8785 的排序规则
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) out.append(',');
                string(entries.get(i).getKey(), out);
                out.append(':');
                write(entries.get(i).getValue(), out);
            }
            out.append('}');
        }
    }

    private static String number(JsonNode node) {
        if (node.isIntegralNumber()) return node.bigIntegerValue().toString();
        double value = node.asDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("JSON 不能包含 NaN 或无穷大");
        if (value == Math.rint(value) && Math.abs(value) < 1e21) return String.valueOf((long) value);
        double abs = Math.abs(value);
        if (abs >= 1e-6 && abs < 1e21)
            return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
        // 科学计数法：Java 的 1.0E-7 写成 ECMAScript 的 1e-7
        String text = Double.toString(value);
        int e = text.indexOf('E');
        String mantissa =
                new BigDecimal(text.substring(0, e)).stripTrailingZeros().toPlainString();
        int exponent = Integer.parseInt(text.substring(e + 1));
        return mantissa + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }

    private static void string(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}

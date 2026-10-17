package dev.eab.erp.bo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * BO API 的参数声明：同时用于校验请求（查询参数、路径参数、请求体字段）和生成 OpenAPI 描述，
 * 对应 TS 版 zod 的 querystring、params、body。查询参数是字符串，int 和 bool 在这里转换。
 */
public record Param(
        String name,
        String type,
        boolean required,
        List<String> enumValues,
        String pattern,
        String patternMessage,
        Number min,
        Number max,
        Object defaultValue,
        String description) {

    public static Param string(String name) {
        return new Param(name, "string", false, null, null, null, null, null, null, null);
    }

    public static Param enumOf(String name, String... values) {
        return new Param(name, "string", false, List.of(values), null, null, null, null, null, null);
    }

    public static Param integer(String name, Integer min, Integer max) {
        return new Param(name, "integer", false, null, null, null, min, max, null, null);
    }

    /** 数值参数；min、max 表示取值范围 */
    public static Param number(String name, Double min, Double max) {
        return new Param(name, "number", false, null, null, null, min, max, null, null);
    }

    /** 只接受 true/false 字符串 */
    public static Param bool(String name) {
        return new Param(name, "boolean", false, List.of("true", "false"), null, null, null, null, null, null);
    }

    /** 最多返回条数，1 到 200，默认 50 */
    public static Param limit() {
        return new Param("limit", "integer", false, null, null, null, 1, 200, 50, "最多返回条数");
    }

    public static Param date(String name) {
        return string(name).matching("^\\d{4}-\\d{2}-\\d{2}$", "日期格式为 YYYY-MM-DD");
    }

    public Param mandatory() {
        return new Param(name, type, true, enumValues, pattern, patternMessage, min, max, defaultValue, description);
    }

    public Param describe(String text) {
        return new Param(name, type, required, enumValues, pattern, patternMessage, min, max, defaultValue, text);
    }

    public Param withDefault(Object value) {
        return new Param(name, type, required, enumValues, pattern, patternMessage, min, max, value, description);
    }

    public Param matching(String regex, String message) {
        return new Param(name, type, required, enumValues, regex, message, min, max, defaultValue, description);
    }

    public Param minLength(int length) {
        return new Param(
                name, type, required, enumValues, pattern, patternMessage, length, max, defaultValue, description);
    }

    /** 校验问题：参数名与说明 */
    public record Problem(String param, String message) {}

    /**
     * 校验并转换参数值。raw 为 null 表示没有提供。返回转换后的值，问题写入 problems。
     * 字符串参数的 min 表示最小长度，整数参数的 min、max 表示取值范围。
     */
    public Object parse(Object raw, List<Problem> problems) {
        if (raw == null) {
            if (defaultValue != null) return defaultValue;
            if (required) problems.add(new Problem(name, "缺少必填参数 " + name));
            return null;
        }
        String text = String.valueOf(raw);
        switch (type) {
            case "integer" -> {
                double number;
                try {
                    number = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    problems.add(new Problem(name, name + " 必须是整数"));
                    return null;
                }
                if (number != Math.rint(number)) {
                    problems.add(new Problem(name, name + " 必须是整数"));
                    return null;
                }
                long value = (long) number;
                if (min != null && value < min.doubleValue()) problems.add(new Problem(name, name + " 不能小于 " + min));
                if (max != null && value > max.doubleValue()) problems.add(new Problem(name, name + " 不能大于 " + max));
                return value;
            }
            case "number" -> {
                double value;
                try {
                    value = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    problems.add(new Problem(name, name + " 必须是数值"));
                    return null;
                }
                if (min != null && value < min.doubleValue()) problems.add(new Problem(name, name + " 不能小于 " + min));
                if (max != null && value > max.doubleValue()) problems.add(new Problem(name, name + " 不能大于 " + max));
                return value;
            }
            case "boolean" -> {
                if (!enumValues.contains(text)) {
                    problems.add(new Problem(name, name + " 只能是 true 或 false"));
                    return null;
                }
                return text.equals("true");
            }
            default -> {
                if (!(raw instanceof String)) {
                    problems.add(new Problem(name, name + " 必须是字符串"));
                    return null;
                }
                if (enumValues != null && !enumValues.contains(text)) {
                    problems.add(new Problem(name, name + " 只能是 " + String.join("、", enumValues)));
                }
                if (min != null && text.length() < min.intValue()) problems.add(new Problem(name, name + " 不能为空"));
                if (pattern != null && !Pattern.compile(pattern).matcher(text).find()) {
                    problems.add(new Problem(name, patternMessage == null ? name + " 格式不对" : patternMessage));
                }
                return text;
            }
        }
    }

    /** OpenAPI 里的 schema */
    public Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        if (enumValues != null) schema.put("enum", enumValues);
        if (pattern != null) schema.put("pattern", pattern);
        if (type.equals("integer") || type.equals("number")) {
            if (min != null) schema.put("minimum", min);
            if (max != null) schema.put("maximum", max);
        } else if (min != null) {
            schema.put("minLength", min);
        }
        if (defaultValue != null) schema.put("default", defaultValue);
        if (description != null) schema.put("description", description);
        return schema;
    }
}

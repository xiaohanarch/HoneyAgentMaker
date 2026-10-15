package dev.eab.spec.schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** schema 节点的工厂方法，写法尽量贴近 zod：Schemas.object().field(...)、Schemas.string().min(1) */
public final class Schemas {

    private Schemas() {}

    public static StringSchema string() {
        return new StringSchema();
    }

    public static NumberSchema number() {
        return new NumberSchema();
    }

    public static Schema bool() {
        return new BooleanSchema();
    }

    public static Schema literal(String value, String message) {
        return new LiteralSchema(value, message);
    }

    public static Schema enumOf(List<String> values) {
        return new EnumSchema(List.copyOf(values));
    }

    public static Schema enumOf(String... values) {
        return enumOf(List.of(values));
    }

    public static ArraySchema array(Schema item) {
        return new ArraySchema(item);
    }

    public static Schema record(Schema key, Schema value) {
        return new RecordSchema(key, value);
    }

    public static ObjectSchema object() {
        return new ObjectSchema();
    }

    /** 按值的 JSON 类型选择分支，报错信息来自被选中的分支，比“所有分支都失败”更好懂 */
    public static Schema union(Schema... options) {
        return new UnionSchema(List.of(options));
    }

    /** 递归定义，导出 JSON Schema 时放进 $defs */
    public static Schema lazy(String name, Supplier<Schema> supplier) {
        return new LazySchema(name, supplier);
    }

    /** 任意值，对应 zod 的 unknown */
    public static Schema any() {
        return new AnySchema();
    }

    // ── 字符串 ─────────────────────────────────────────────────────────────

    public static final class StringSchema extends Schema {
        private final List<Rule> rules = new ArrayList<>();
        private Integer minLength;
        private String pattern;
        private String format;

        private record Rule(String code, Predicate<String> ok, String message) {}

        public StringSchema min(int length) {
            return min(length, length == 1 ? "不能为空" : "长度不能少于 " + length + " 个字符");
        }

        public StringSchema min(int length, String message) {
            minLength = length;
            rules.add(new Rule("too_small", s -> s.length() >= length, message));
            return this;
        }

        public StringSchema regex(String regex, String message) {
            Pattern compiled = Pattern.compile(regex);
            pattern = regex;
            rules.add(new Rule("invalid_format", s -> compiled.matcher(s).find(), message));
            return this;
        }

        public StringSchema startsWith(String prefix, String message) {
            rules.add(new Rule("invalid_format", s -> s.startsWith(prefix), message));
            if (pattern == null)
                pattern = "^" + Pattern.quote(prefix).replace("\\Q", "").replace("\\E", "");
            return this;
        }

        public StringSchema url(String message) {
            format = "uri";
            rules.add(new Rule("invalid_format", Schemas::isUrl, message));
            return this;
        }

        /** 格式检查（例如资产引用、版本号），失败时报 custom */
        public StringSchema check(Predicate<String> ok, String message) {
            rules.add(new Rule("custom", ok, message));
            return this;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isString()) {
                invalidType("string", value, path, issues);
                return null;
            }
            String text = value.asString();
            for (Rule rule : rules) {
                if (!rule.ok().test(text)) issues.add(rule.code(), rule.message(), path);
            }
            return value;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isString();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", "string");
            if (minLength != null) node.put("minLength", minLength);
            if (pattern != null) node.put("pattern", pattern);
            if (format != null) node.put("format", format);
            return node;
        }
    }

    static boolean isUrl(String text) {
        try {
            java.net.URI uri = new java.net.URI(text);
            return uri.getScheme() != null && uri.getHost() != null;
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    // ── 数字、布尔、字面量、枚举 ───────────────────────────────────────────

    public static final class NumberSchema extends Schema {
        private Double min;
        private Double max;
        private boolean integer;

        public NumberSchema min(double value) {
            min = value;
            return this;
        }

        public NumberSchema max(double value) {
            max = value;
            return this;
        }

        public NumberSchema integer() {
            integer = true;
            return this;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isNumber()) {
                invalidType("number", value, path, issues);
                return null;
            }
            double number = value.asDouble();
            if (integer && number != Math.rint(number)) issues.add("invalid_type", "无效输入：期望整数", path);
            if (min != null && number < min) issues.add("too_small", "数值不能小于 " + format(min), path);
            if (max != null && number > max) issues.add("too_big", "数值不能大于 " + format(max), path);
            return value;
        }

        private static String format(double value) {
            return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isNumber();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", integer ? "integer" : "number");
            if (min != null) node.put("minimum", min);
            if (max != null) node.put("maximum", max);
            return node;
        }
    }

    static final class BooleanSchema extends Schema {
        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isBoolean()) {
                invalidType("boolean", value, path, issues);
                return null;
            }
            return value;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isBoolean();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            return NODES.objectNode().put("type", "boolean");
        }
    }

    static final class LiteralSchema extends Schema {
        private final String value;
        private final String message;

        LiteralSchema(String value, String message) {
            this.value = value;
            this.message = message;
        }

        @Override
        protected JsonNode check(JsonNode node, List<Object> path, Issues issues) {
            if (node == null || !node.isString() || !node.asString().equals(value)) {
                issues.add("invalid_value", message, path);
                return null;
            }
            return node;
        }

        @Override
        protected boolean accepts(JsonNode node) {
            return node != null && node.isString();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            return NODES.objectNode().put("type", "string").put("const", value);
        }
    }

    static final class EnumSchema extends Schema {
        private final List<String> values;

        EnumSchema(List<String> values) {
            this.values = values;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isString() || !values.contains(value.asString())) {
                String options = String.join(
                        "|", values.stream().map(v -> "\"" + v + "\"").toList());
                issues.add("invalid_value", "无效选项：期望以下之一 " + options, path);
                return null;
            }
            return value;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isString();
        }

        List<String> values() {
            return values;
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", "string");
            ArrayNode list = node.putArray("enum");
            values.forEach(list::add);
            return node;
        }
    }

    // ── 数组、映射 ─────────────────────────────────────────────────────────

    public static final class ArraySchema extends Schema {
        private final Schema item;
        private Integer min;
        private Integer max;

        ArraySchema(Schema item) {
            this.item = item;
        }

        public ArraySchema min(int count) {
            min = count;
            return this;
        }

        public ArraySchema max(int count) {
            max = count;
            return this;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isArray()) {
                invalidType("array", value, path, issues);
                return null;
            }
            if (min != null && value.size() < min) {
                issues.add("too_small", min == 1 ? "至少需要 1 项" : "至少需要 " + min + " 项", path);
            }
            if (max != null && value.size() > max) issues.add("too_big", "最多只能有 " + max + " 项", path);
            ArrayNode out = NODES.arrayNode();
            for (int i = 0; i < value.size(); i++) {
                JsonNode parsed = item.parse(value.get(i), Issues.append(path, i), issues);
                out.add(parsed == null ? value.get(i) : parsed);
            }
            return out;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isArray();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", "array");
            node.set("items", item.toJsonSchema(ctx));
            if (min != null) node.put("minItems", min);
            if (max != null) node.put("maxItems", max);
            return node;
        }
    }

    /** 键按 key schema 校验的映射；键是枚举时所有键都是可选的（对应 zod 的 partialRecord） */
    static final class RecordSchema extends Schema {
        private final Schema key;
        private final Schema value;

        RecordSchema(Schema key, Schema value) {
            this.key = key;
            this.value = value;
        }

        @Override
        protected JsonNode check(JsonNode node, List<Object> path, Issues issues) {
            if (node == null || !node.isObject()) {
                invalidType("object", node, path, issues);
                return null;
            }
            ObjectNode out = NODES.objectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                List<Object> at = Issues.append(path, entry.getKey());
                key.parse(NODES.stringNode(entry.getKey()), at, issues);
                JsonNode parsed = value.parse(entry.getValue(), at, issues);
                out.set(entry.getKey(), parsed == null ? entry.getValue() : parsed);
            }
            return out;
        }

        @Override
        protected boolean accepts(JsonNode node) {
            return node != null && node.isObject();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", "object");
            if (key instanceof EnumSchema keys) {
                ObjectNode properties = node.putObject("properties");
                for (String k : keys.values()) properties.set(k, value.toJsonSchema(ctx));
                node.put("additionalProperties", false);
            } else {
                node.set("propertyNames", key.toJsonSchema(ctx));
                node.set("additionalProperties", value.toJsonSchema(ctx));
            }
            return node;
        }
    }

    // ── 对象 ───────────────────────────────────────────────────────────────

    /** 严格对象（对应 zod 的 strictObject）：出现未声明的字段时报错，拼错字段名能被发现 */
    public static final class ObjectSchema extends Schema {
        private record Field(Schema schema, boolean optional, JsonNode defaultValue) {}

        private final Map<String, Field> fields = new LinkedHashMap<>();

        public ObjectSchema field(String name, Schema schema) {
            fields.put(name, new Field(schema, false, null));
            return this;
        }

        public ObjectSchema optional(String name, Schema schema) {
            fields.put(name, new Field(schema, true, null));
            return this;
        }

        /** 缺省时取默认值；默认值不再经过校验，与 zod 的 default 一致 */
        public ObjectSchema withDefault(String name, Schema schema, JsonNode defaultValue) {
            fields.put(name, new Field(schema, true, defaultValue));
            return this;
        }

        public Set<String> fieldNames() {
            return fields.keySet();
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || !value.isObject()) {
                invalidType("object", value, path, issues);
                return null;
            }
            List<String> unknown = new ArrayList<>();
            for (Iterator<String> it = value.propertyNames().iterator(); it.hasNext(); ) {
                String name = it.next();
                if (!fields.containsKey(name)) unknown.add(name);
            }
            if (!unknown.isEmpty()) {
                String keys = String.join(
                        "、", unknown.stream().map(k -> "\"" + k + "\"").toList());
                issues.add("unrecognized_keys", "无法识别的键：" + keys, path);
            }
            ObjectNode out = NODES.objectNode();
            fields.forEach((name, field) -> {
                JsonNode raw = value.get(name);
                List<Object> at = Issues.append(path, name);
                if (raw == null || raw.isMissingNode()) {
                    if (field.defaultValue() != null)
                        out.set(name, field.defaultValue().deepCopy());
                    else if (!field.optional()) field.schema().parse(null, at, issues);
                    return;
                }
                JsonNode parsed = field.schema().parse(raw, at, issues);
                out.set(name, parsed == null ? raw : parsed);
            });
            return out;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && value.isObject();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode().put("type", "object");
            ObjectNode properties = node.putObject("properties");
            ArrayNode required = NODES.arrayNode();
            fields.forEach((name, field) -> {
                ObjectNode property = field.schema().toJsonSchema(ctx);
                if (field.defaultValue() != null) property.set("default", field.defaultValue());
                properties.set(name, property);
                if (!field.optional()) required.add(name);
            });
            if (!required.isEmpty()) node.set("required", required);
            node.put("additionalProperties", false);
            return node;
        }
    }

    // ── 联合、递归、任意值 ─────────────────────────────────────────────────

    static final class UnionSchema extends Schema {
        private final List<Schema> options;

        UnionSchema(List<Schema> options) {
            this.options = options;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            for (Schema option : options) {
                if (option.accepts(value)) return option.parse(value, path, issues);
            }
            issues.add("invalid_union", "无效输入：类型不符合任何一种允许的写法，实际接收 " + typeName(value), path);
            return null;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return options.stream().anyMatch(o -> o.accepts(value));
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            ObjectNode node = NODES.objectNode();
            ArrayNode anyOf = node.putArray("anyOf");
            options.forEach(o -> anyOf.add(o.toJsonSchema(ctx)));
            return node;
        }
    }

    static final class LazySchema extends Schema {
        private final String name;
        private final Supplier<Schema> supplier;
        private Schema resolved;

        LazySchema(String name, Supplier<Schema> supplier) {
            this.name = name;
            this.supplier = supplier;
        }

        private Schema target() {
            if (resolved == null) resolved = supplier.get();
            return resolved;
        }

        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            return target().parse(value, path, issues);
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return target().accepts(value);
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            return ctx.reference(name, target());
        }
    }

    static final class AnySchema extends Schema {
        @Override
        protected JsonNode check(JsonNode value, List<Object> path, Issues issues) {
            if (value == null || value.isMissingNode()) {
                invalidType("任意值", value, path, issues);
                return null;
            }
            return value;
        }

        @Override
        protected boolean accepts(JsonNode value) {
            return value != null && !value.isMissingNode();
        }

        @Override
        protected ObjectNode jsonSchema(JsonSchemaContext ctx) {
            return NODES.objectNode();
        }
    }
}

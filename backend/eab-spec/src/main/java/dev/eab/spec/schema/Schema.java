package dev.eab.spec.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 资产 schema 的一个节点。一份定义同时用于三件事：校验作者写的 YAML（问题带字段路径和中文说明）、
 * 填充默认值得到规范化的内容、导出 JSON Schema 给编辑器做补全。写法对照 TS 版的 zod schema。
 *
 * <p>细化规则（refine）只在本节点的基础校验全部通过后执行，规则函数可以放心假设值的形状是对的。
 */
public abstract class Schema {

    /** 细化规则：检查规范化后的值，发现问题时写入 issues，路径相对于本节点 */
    @FunctionalInterface
    public interface Refinement {
        void check(JsonNode value, RefineContext ctx);
    }

    public record RefineContext(List<Object> path, Issues issues) {
        public void addIssue(String message) {
            issues.add("custom", message, path);
        }

        public void addIssue(String field, String message) {
            issues.add("custom", message, Issues.append(path, field));
        }
    }

    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private String description;
    private final List<Refinement> refinements = new ArrayList<>();
    private Function<JsonNode, JsonNode> transform;

    /** 校验并返回规范化后的值；有问题时返回 null，问题写入 issues */
    public final JsonNode parse(JsonNode value, List<Object> path, Issues issues) {
        int before = issues.size();
        JsonNode result = check(value, path, issues);
        if (result == null || issues.size() > before) return null;
        for (Refinement refinement : refinements) refinement.check(result, new RefineContext(path, issues));
        if (issues.size() > before) return null;
        return transform == null ? result : transform.apply(result);
    }

    protected abstract JsonNode check(JsonNode value, List<Object> path, Issues issues);

    /** 联合类型按值的 JSON 类型挑选分支 */
    protected abstract boolean accepts(JsonNode value);

    protected abstract ObjectNode jsonSchema(JsonSchemaContext ctx);

    public final ObjectNode toJsonSchema(JsonSchemaContext ctx) {
        ObjectNode node = jsonSchema(ctx);
        if (description != null) node.put("description", description);
        return node;
    }

    public Schema describe(String text) {
        this.description = text;
        return this;
    }

    public Schema refine(Predicate<JsonNode> ok, String message) {
        refinements.add((value, ctx) -> {
            if (!ok.test(value)) ctx.addIssue(message);
        });
        return this;
    }

    public Schema superRefine(Refinement refinement) {
        refinements.add(refinement);
        return this;
    }

    /** 校验通过后改写值，例如把单个钩子统一成数组 */
    public Schema transform(Function<JsonNode, JsonNode> fn) {
        this.transform = fn;
        return this;
    }

    static String typeName(JsonNode value) {
        if (value == null || value.isMissingNode()) return "undefined";
        if (value.isNull()) return "null";
        if (value.isString()) return "string";
        if (value.isNumber()) return "number";
        if (value.isBoolean()) return "boolean";
        if (value.isArray()) return "array";
        return "object";
    }

    static void invalidType(String expected, JsonNode value, List<Object> path, Issues issues) {
        issues.add("invalid_type", "无效输入：期望 " + expected + "，实际接收 " + typeName(value), path);
    }
}

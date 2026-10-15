package dev.eab.spec;

import dev.eab.spec.schema.JsonSchemaContext;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 每个 kind 的 JSON Schema（描述作者写的 YAML，即输入形态）。生成物提交在仓库根目录的 schemas/ 下，
 * 供 Studio 的编辑器做 YAML 校验和补全；由 pnpm spec:json-schema 重新生成，测试检查它与 schema 同步。
 */
public final class JsonSchemaExport {

    private JsonSchemaExport() {}

    public static ObjectNode kindJsonSchema(AssetKind kind) {
        JsonSchemaContext ctx = new JsonSchemaContext();
        ObjectNode body = kind.schema().toJsonSchema(ctx);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("$id", "https://eab.dev/schemas/v1/" + kind.name() + ".json");
        root.put("title", kind.name());
        root.setAll(body);
        ctx.writeDefs(root);
        return root;
    }

    public static String fileName(AssetKind kind) {
        return kind.name() + ".schema.json";
    }

    public static String serialize(ObjectNode schema) {
        return Json.MAPPER
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(schema)
                        .replace("\r\n", "\n") + "\n";
    }
}

package dev.eab.spec.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.node.ObjectNode;

/** 导出 JSON Schema 时收集递归定义（例如工作流步骤），放进根节点的 $defs */
public final class JsonSchemaContext {

    private final Map<String, ObjectNode> defs = new LinkedHashMap<>();
    private final Map<String, Boolean> inProgress = new LinkedHashMap<>();

    /** 返回指向 $defs/name 的引用；第一次遇到时生成定义 */
    ObjectNode reference(String name, Schema schema) {
        if (!defs.containsKey(name) && !inProgress.containsKey(name)) {
            inProgress.put(name, true);
            ObjectNode definition = schema.toJsonSchema(this);
            defs.put(name, definition);
            inProgress.remove(name);
        }
        return Schema.NODES.objectNode().put("$ref", "#/$defs/" + name);
    }

    public Map<String, ObjectNode> defs() {
        return Map.copyOf(defs);
    }

    public boolean hasDefs() {
        return !defs.isEmpty();
    }

    /** 按生成顺序写入 $defs，保证导出文件稳定 */
    public void writeDefs(ObjectNode root) {
        if (defs.isEmpty()) return;
        ObjectNode container = root.putObject("$defs");
        defs.forEach(container::set);
    }
}

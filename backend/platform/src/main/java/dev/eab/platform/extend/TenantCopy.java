package dev.eab.platform.extend;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.spec.Json;
import dev.eab.spec.kinds.CommonSchemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * “复制为租户 Agent”的副本（ADR 0004、0011）：输入是注册中心按租户视角裁剪过的标品 Agent，只复制租户看得到的部分。
 * 指令取标品的公开版指令；自由文本约束、补充上下文在租户视角下本来就是占位块，不复制；扩展点、钩子、评测集也不复制。
 */
final class TenantCopy {

    private TenantCopy() {}

    static final String NOTICE = "副本归本租户所有，不再随标品升级：标品发布新版本（修复、新工具、指令改进）时副本不会自动获得，" + "需要手工对照升级。只需要增强时，建议用扩展而不是复制。";

    static final List<String> COPIED_SPEC_FIELDS =
            List.of("model", "tools", "semanticPacks", "channels", "writePolicy", "approvals", "output", "memory");

    static ObjectNode build(ObjectNode vendor, DemoUser user, String sourceRef) {
        JsonNode metadata = vendor.path("metadata");
        JsonNode spec = vendor.path("spec");
        String name = metadata.path("name").asString();
        String displayName = metadata.path("displayName").asString(name);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", (name + "-copy").length() > 60 ? name.substring(0, 55) + "-copy" : name + "-copy");
        meta.put("namespace", "tenant." + user.tenantId() + ".agents");
        meta.put("version", "1.0.0");
        meta.put("displayName", displayName + "（租户副本）");
        meta.put("description", metadata.path("description").asString(displayName));
        meta.put("owner", user.id());
        if (metadata.has("businessProcess"))
            meta.put("businessProcess", Json.MAPPER.convertValue(metadata.get("businessProcess"), Object.class));
        meta.put("labels", Map.of("copiedFrom", sourceRef));

        Map<String, Object> s = new LinkedHashMap<>();
        s.put("expertise", spec.path("expertise").asString(displayName));
        String publicInstructions = spec.path("publicInstructions").asString("");
        s.put(
                "instructions",
                "（复制自标品 " + sourceRef + " 的公开版指令。标品指令原文对租户不可见，副本不随标品升级。）\n"
                        + (publicInstructions.isBlank() ? "请补充本 Agent 的指令。" : publicInstructions));
        List<String> rules = new ArrayList<>();
        for (JsonNode c : spec.path("constraints")) {
            // 规则编号可以复制；自由文本约束在租户视角下是占位块，不复制
            if (c.isString() && CommonSchemas.isRuleId(c.asString())) rules.add(c.asString());
        }
        s.put("constraints", rules);
        for (String field : COPIED_SPEC_FIELDS) {
            if (spec.has(field)) s.put(field, Json.MAPPER.convertValue(spec.get(field), Object.class));
        }
        s.put("triggers", List.of(Map.of("type", "assistant")));
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("description", spec.path("routing").path("description").asString(displayName));
        routing.put("examples", Json.MAPPER.convertValue(spec.path("routing").path("examples"), Object.class));
        routing.put("direct", true);
        s.put("routing", routing);

        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "Agent");
        asset.put("metadata", meta);
        asset.put("spec", s);
        return Json.MAPPER.valueToTree(asset);
    }
}

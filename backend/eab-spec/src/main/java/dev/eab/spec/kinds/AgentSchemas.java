package dev.eab.spec.kinds;

import static dev.eab.spec.kinds.CommonSchemas.JSON;
import static dev.eab.spec.kinds.CommonSchemas.assetRef;
import static dev.eab.spec.kinds.CommonSchemas.defineKind;
import static dev.eab.spec.kinds.CommonSchemas.exactlyOneKey;
import static dev.eab.spec.kinds.CommonSchemas.expression;
import static dev.eab.spec.kinds.CommonSchemas.modelProfile;
import static dev.eab.spec.kinds.CommonSchemas.routing;
import static dev.eab.spec.kinds.CommonSchemas.writeLevel;
import static dev.eab.spec.schema.Schemas.array;
import static dev.eab.spec.schema.Schemas.bool;
import static dev.eab.spec.schema.Schemas.enumOf;
import static dev.eab.spec.schema.Schemas.number;
import static dev.eab.spec.schema.Schemas.object;
import static dev.eab.spec.schema.Schemas.record;
import static dev.eab.spec.schema.Schemas.string;
import static dev.eab.spec.schema.Schemas.union;

import dev.eab.spec.AssetRef;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.schema.Schema;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;

/** Agent 与扩展的 schema，对应 TS 版 kinds/agent.ts、agent-parts.ts、agent-extension.ts */
public final class AgentSchemas {

    private AgentSchemas() {}

    public static final List<String> TOOL_ENTRY_TYPES =
            List.of("skill", "agent", "remoteAgent", "mcp", "api", "documents", "builtin");

    public static final List<String> TRIGGER_TYPES = List.of("assistant", "event", "schedule", "agent");

    public static final List<String> CHANNELS = List.of("assistant-web", "im", "api");

    public static final List<String> EXTENSION_POINT_TYPES = List.of(
            "instructions.append",
            "tools.add",
            "skills.add",
            "knowledge.add",
            "hooks",
            "approvals.add",
            "model.select");

    public static final List<String> OUTPUT_FIELD_TYPES = List.of("string", "int", "double", "bool", "list", "map");

    static Schema toolSubset() {
        return object().field("source", assetRef())
                .optional("tools", array(string().min(1)).min(1));
    }

    public static Schema toolEntry() {
        return object().optional("skill", assetRef())
                .optional("agent", assetRef())
                .optional("remoteAgent", assetRef())
                .optional("mcp", toolSubset())
                .optional("api", toolSubset())
                .optional("documents", assetRef())
                .optional("builtin", string().min(1))
                .refine(
                        v -> exactlyOneKey(v, TOOL_ENTRY_TYPES),
                        "每个工具条目只能写一种来源：" + String.join("、", TOOL_ENTRY_TYPES) + " 之一");
    }

    /** 扩展里的 tools.add 允许直接写资产引用 */
    static Schema toolEntryOrRef() {
        return union(assetRef(), toolEntry());
    }

    public static Schema trigger() {
        return object().field("type", enumOf(TRIGGER_TYPES))
                .optional("event", string())
                .optional("cron", string())
                .superRefine((v, ctx) -> {
                    String type = v.get("type").asString();
                    if (type.equals("event") && !v.has("event")) ctx.addIssue("event", "事件触发器必须写 event");
                    if (type.equals("schedule") && !v.has("cron")) ctx.addIssue("cron", "定时触发器必须写 cron");
                });
    }

    static Schema executionSteps() {
        Schema step = object().optional("skill", assetRef())
                .optional("guard", string().min(1))
                .refine(v -> exactlyOneKey(v, List.of("skill", "guard")), "每个执行步骤只能写 skill 或 guard 之一");
        return object().withDefault("pre", array(step), JSON.arrayNode())
                .withDefault("post", array(step), JSON.arrayNode());
    }

    static Schema hookBinding() {
        return object().optional("workflow", assetRef())
                .optional("skill", assetRef())
                .optional("when", expression())
                .refine(v -> exactlyOneKey(v, List.of("workflow", "skill")), "钩子只能挂 workflow 或 skill 之一");
    }

    /** 每个事件可以写单个钩子，也可以写数组，解析后统一成数组 */
    static Schema hookList() {
        return union(hookBinding(), array(hookBinding())).transform(v -> {
            if (v.isArray()) return v;
            ArrayNode list = JSON.arrayNode();
            list.add(v);
            return list;
        });
    }

    public static Schema hooks() {
        var hooks = object();
        for (String event : AgentSpec.HOOK_EVENTS) hooks.optional(event, hookList());
        return hooks;
    }

    public static Schema approvalRule() {
        return object().field("tool", string().min(1))
                .field("policy", enumOf(AgentSpec.APPROVAL_POLICIES))
                .optional("when", expression())
                .optional("approverRole", string())
                .optional("description", string());
    }

    static Schema extensionPoint() {
        return object().field("type", enumOf(EXTENSION_POINT_TYPES))
                .field("description", string().min(1))
                .optional("allowedProfiles", array(modelProfile()).min(1))
                .optional("events", array(enumOf(AgentSpec.HOOK_EVENTS)).min(1))
                .superRefine((v, ctx) -> {
                    if (v.get("type").asString().equals("model.select") && !v.has("allowedProfiles")) {
                        ctx.addIssue("allowedProfiles", "model.select 扩展点必须声明 allowedProfiles");
                    }
                });
    }

    static Schema output() {
        Schema field = object().field("type", enumOf(OUTPUT_FIELD_TYPES))
                .optional("description", string())
                .optional("enum", array(string()));
        return object().field("schema", record(string().regex("^[a-zA-Z_][a-zA-Z0-9_]*$", "字段名只能包含字母、数字和下划线"), field));
    }

    static Schema evalsRef() {
        return object().withDefault("suites", array(assetRef()), JSON.arrayNode());
    }

    public static Schema agentSpec() {
        ArrayNode defaultTriggers = JSON.arrayNode();
        defaultTriggers.addObject().put("type", "assistant");
        ArrayNode defaultChannels = JSON.arrayNode();
        defaultChannels.add("assistant-web");
        return object().field("expertise", string().min(1))
                .field("instructions", string().min(1))
                .optional("publicInstructions", string().min(1))
                .optional("additionalContext", string())
                .withDefault("constraints", array(string().min(1)), JSON.arrayNode())
                .field(
                        "model",
                        object().field("profile", modelProfile())
                                .optional("temperature", number().min(0).max(2)))
                .optional("executionSteps", executionSteps())
                .withDefault("tools", array(toolEntry()), JSON.arrayNode())
                .withDefault("semanticPacks", array(assetRef()), JSON.arrayNode())
                .withDefault("triggers", array(trigger()), defaultTriggers)
                .withDefault("channels", array(enumOf(CHANNELS)), defaultChannels)
                .optional("memory", object().withDefault("longTerm", bool(), JSON.booleanNode(false)))
                .field("writePolicy", object().field("maxLevel", writeLevel()))
                .withDefault("approvals", array(approvalRule()), JSON.arrayNode())
                .optional("hooks", hooks())
                .optional("output", output())
                .field("routing", routing())
                .withDefault("extensionPoints", array(extensionPoint()), JSON.arrayNode())
                .optional("evals", evalsRef());
    }

    public static Schema agent() {
        return defineKind("Agent", agentSpec());
    }

    /** 扩展的目标必须带版本范围，标品升级时按范围解析（主提示词 5.4 节第 6 条） */
    static Schema targetRef() {
        return assetRef()
                .check(
                        ref -> !AssetRef.isValid(ref) || AssetRef.parse(ref).range() != null,
                        "扩展的 target 必须带版本范围，例如 vendor.fin/transaction-anomaly-analyst@^1.0");
    }

    public static Schema agentExtension() {
        return defineKind(
                "AgentExtension",
                object().field("target", targetRef())
                        .optional("instructions", object().field("append", string().min(1)))
                        .optional(
                                "constraints",
                                object().field("append", array(string().min(1)).min(1)))
                        .optional(
                                "tools",
                                object().field("add", array(toolEntryOrRef()).min(1)))
                        .optional(
                                "skills",
                                object().field("add", array(assetRef()).min(1)))
                        .optional(
                                "knowledge",
                                object().field("add", array(assetRef()).min(1)))
                        .optional("hooks", hooks())
                        .optional(
                                "approvals",
                                object().field("add", array(approvalRule()).min(1)))
                        .optional("model", object().field("profile", modelProfile()))
                        .optional("evals", evalsRef()));
    }
}

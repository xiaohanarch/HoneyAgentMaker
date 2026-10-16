package dev.eab.spec.kinds;

import static dev.eab.spec.kinds.CommonSchemas.JSON;
import static dev.eab.spec.kinds.CommonSchemas.assetRef;
import static dev.eab.spec.kinds.CommonSchemas.connectionAlias;
import static dev.eab.spec.kinds.CommonSchemas.defineKind;
import static dev.eab.spec.kinds.CommonSchemas.expression;
import static dev.eab.spec.kinds.CommonSchemas.name;
import static dev.eab.spec.kinds.CommonSchemas.pinnedAssetRef;
import static dev.eab.spec.kinds.CommonSchemas.routing;
import static dev.eab.spec.kinds.CommonSchemas.ruleId;
import static dev.eab.spec.kinds.CommonSchemas.writeLevel;
import static dev.eab.spec.schema.Schemas.any;
import static dev.eab.spec.schema.Schemas.array;
import static dev.eab.spec.schema.Schemas.bool;
import static dev.eab.spec.schema.Schemas.enumOf;
import static dev.eab.spec.schema.Schemas.lazy;
import static dev.eab.spec.schema.Schemas.number;
import static dev.eab.spec.schema.Schemas.object;
import static dev.eab.spec.schema.Schemas.record;
import static dev.eab.spec.schema.Schemas.string;

import dev.eab.spec.model.StepSpecs;
import dev.eab.spec.schema.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** 技能、工作流、工具来源、语义包、能力目录与生命周期相关 kind 的 schema */
public final class OtherSchemas {

    private OtherSchemas() {}

    // ── 步骤（技能、工作流）────────────────────────────────────────────────

    private static final Schema STEP = lazy("Step", OtherSchemas::stepObject);

    private static Schema stepObject() {
        return object().field("id", name())
                .field("type", enumOf(StepSpecs.STEP_TYPES))
                .optional("name", string())
                .optional("tool", string().min(1))
                .optional("source", assetRef())
                .optional("skill", assetRef())
                .optional("agent", assetRef())
                .optional("with", record(string(), any()))
                .optional("expression", expression())
                .optional("if", expression())
                .optional("then", array(STEP).min(1))
                .optional("else", array(STEP))
                .optional("branches", array(array(STEP).min(1)).min(2))
                .optional("message", string())
                .optional("channel", string())
                .optional("event", string())
                .optional("timeoutSeconds", number().integer().min(1))
                .optional("output", string())
                .superRefine((v, ctx) -> {
                    String type = v.get("type").asString();
                    for (String field : StepSpecs.REQUIRED_FIELDS.get(type)) {
                        if (!v.has(field)) ctx.addIssue(field, type + " 步骤必须写 " + field);
                    }
                });
    }

    /** 步骤 id 在同一流程内必须唯一（含嵌套分支），并且只能用允许的步骤类型 */
    private static Schema stepList(List<String> allowed) {
        return array(STEP).min(1).superRefine((steps, ctx) -> {
            List<JsonNode> all = new ArrayList<>();
            flatten(steps, all);
            Set<String> seen = new HashSet<>();
            Set<String> duplicates = new java.util.LinkedHashSet<>();
            for (JsonNode step : all) {
                String id = step.get("id").asString();
                if (!seen.add(id)) duplicates.add(id);
            }
            for (String id : duplicates) ctx.addIssue("步骤 id \"" + id + "\" 重复");
            for (JsonNode step : all) {
                String type = step.get("type").asString();
                if (!allowed.contains(type)) {
                    ctx.addIssue("步骤 \"" + step.get("id").asString() + "\" 的类型 " + type + " 不能用在这里，允许的类型："
                            + String.join("、", allowed));
                }
            }
        });
    }

    private static void flatten(JsonNode steps, List<JsonNode> out) {
        for (JsonNode step : steps) {
            out.add(step);
            if (step.has("then")) flatten(step.get("then"), out);
            if (step.has("else")) flatten(step.get("else"), out);
            if (step.has("branches")) for (JsonNode branch : step.get("branches")) flatten(branch, out);
        }
    }

    /** JSON Schema 对象，原样保存，由运行时校验入参出参 */
    private static Schema jsonSchemaObject() {
        return record(string(), any());
    }

    public static Schema skill() {
        return defineKind(
                "Skill",
                object().field("inputSchema", jsonSchemaObject())
                        .field("outputSchema", jsonSchemaObject())
                        .field("steps", stepList(StepSpecs.SKILL_STEP_TYPES))
                        .withDefault("agentOnly", bool(), JSON.booleanNode(false))
                        .field("writeLevel", writeLevel())
                        .optional("routing", routing()));
    }

    public static Schema workflow() {
        return defineKind(
                "Workflow",
                object().optional("inputSchema", jsonSchemaObject()).field("steps", stepList(StepSpecs.STEP_TYPES)));
    }

    // ── 工具来源 ───────────────────────────────────────────────────────────

    public static final List<String> TOOL_CATEGORIES =
            List.of("query", "draft", "write", "payment", "posting", "notify");

    public static Schema toolSource() {
        Schema tool = object().field("name", string().regex("^[a-z][a-z0-9_.]*$", "工具名用小写字母、数字、下划线和点，例如 supplier_list"))
                .field("description", string().min(1))
                .optional("writeLevel", writeLevel())
                .optional("category", enumOf(TOOL_CATEGORIES))
                .optional(
                        "operation",
                        object().field("method", enumOf("GET", "POST", "PUT", "PATCH", "DELETE"))
                                .field("path", string().startsWith("/", "接口路径必须以 / 开头，且不能包含主机地址")))
                .optional("inputSchema", jsonSchemaObject())
                .optional("outputSchema", jsonSchemaObject());
        return defineKind(
                "ToolSource",
                object().field("type", enumOf("bo-api", "mcp", "http"))
                        .field("connection", connectionAlias())
                        .optional("openapiPath", string().startsWith("/", "路径必须以 / 开头"))
                        .withDefault("tools", array(tool), JSON.arrayNode()));
    }

    // ── 语义包 ─────────────────────────────────────────────────────────────

    public static Schema semanticPack() {
        Schema metaRef = string().regex(
                        "^erp\\.[a-z]+\\.[A-Za-z][A-Za-z0-9]*(@v\\d+)?$",
                        "元数据引用格式应为 erp.<域>.<对象>[@v版本]，例如 erp.pur.Supplier@v1");
        Schema field = object().field("ref", string().min(1))
                .optional("description", string())
                .optional("enumMeaning", record(string(), string()));
        Schema semanticObject =
                object().field("ref", metaRef).optional("description", string()).optional("fields", array(field));
        Schema relationship = object().field("from", string().min(1))
                .field("to", string().min(1))
                .field("verb", string().min(1))
                .field("cardinality", enumOf("one-to-one", "one-to-many", "many-to-one", "many-to-many"))
                .optional("notes", string());
        Schema metric = object().field("name", string().regex("^[a-z][a-z0-9_]*$", "指标名用小写下划线风格，例如 overdue_days"))
                .field("displayName", string().min(1))
                .field("definition", string().min(1))
                .optional("unit", string());
        Schema rule = object().field("id", ruleId())
                .optional("ref", string())
                .field("severity", enumOf("block", "warn", "info"))
                .optional("description", string())
                .field("remedy", string().min(1))
                .optional("check", object().field("tool", string().min(1)).field("when", string().min(1)));
        Schema verifiedTask = object().field(
                        "id", string().regex("^[A-Z][A-Z0-9]*-VT-\\d{3}$", "已验证任务编号格式应为 域-VT-三位数字"))
                .field("question", string().min(1))
                .field("expected_conclusion", string().min(1))
                .optional("fail_if", string())
                .optional("tools", array(string()));
        Schema permissions = object().withDefault("filter_dimensions", array(string()), JSON.arrayNode())
                .optional("inherit_from", string())
                .withDefault("disclose_filtered", bool(), JSON.booleanNode(true));
        return defineKind(
                "SemanticPack",
                object().optional("extends", assetRef())
                        .withDefault("objects", array(semanticObject), JSON.arrayNode())
                        .withDefault("relationships", array(relationship), JSON.arrayNode())
                        .withDefault("metrics", array(metric), JSON.arrayNode())
                        .withDefault("rules", array(rule), JSON.arrayNode())
                        .withDefault("agent_instructions", array(string().min(1)), JSON.arrayNode())
                        .withDefault("verified_tasks", array(verifiedTask), JSON.arrayNode())
                        .optional("permissions", permissions));
    }

    // ── 能力目录与接入 ─────────────────────────────────────────────────────

    private static ArrayNode defaultTriggers() {
        ArrayNode triggers = JSON.arrayNode();
        triggers.addObject().put("type", "assistant");
        return triggers;
    }

    public static Schema assistant() {
        Schema kpi = object().field("name", string().regex("^[a-z][a-z0-9_]*$", "指标名用小写下划线风格"))
                .field("displayName", string().min(1))
                .optional("unit", string())
                .optional("target", number())
                .withDefault("direction", enumOf("up", "down"), JSON.stringNode("up"));
        return defineKind(
                "Assistant",
                object().field("members", array(assetRef()).min(1))
                        .field("routing", routing())
                        .withDefault("kpis", array(kpi).max(4), JSON.arrayNode())
                        .withDefault("triggers", array(AgentSchemas.trigger()), defaultTriggers()));
    }

    public static Schema remoteAgent() {
        return defineKind(
                "RemoteAgent",
                object().field("connection", connectionAlias())
                        .withDefault(
                                "agentCardPath",
                                string().startsWith("/", "路径必须以 / 开头"),
                                JSON.stringNode("/.well-known/agent-card.json"))
                        .field("auth", object().field("type", enumOf("platform-service-token", "none")))
                        .field("routing", routing())
                        .withDefault("allowedCallers", array(assetRef()), JSON.arrayNode())
                        .optional("assistant", assetRef()));
    }

    public static Schema knowledgeSource() {
        return defineKind(
                "KnowledgeSource",
                object().field(
                                "documents",
                                array(object().field("title", string().min(1)).field("path", string().min(1)))
                                        .min(1)));
    }

    public static final List<String> ENVIRONMENTS = List.of("private", "dev", "test", "prod");

    public static Schema connection() {
        Schema binding = object().field("url", string().url("必须是完整的 URL"))
                .field(
                        "auth",
                        object().field(
                                        "type",
                                        enumOf(
                                                "none",
                                                "client-credentials",
                                                "token-exchange",
                                                "platform-service-token"))
                                .optional(
                                        "credentialRef",
                                        string().regex(
                                                        "^(env|vault):[A-Za-z0-9_./-]+$",
                                                        "凭据只能写引用，例如 env:DEMO_ERP_CLIENT_SECRET，不能写凭据本身")));
        return defineKind(
                "Connection",
                object().field("alias", connectionAlias())
                        .field("type", enumOf("bo-api", "mcp", "a2a", "http"))
                        .field("bindings", record(enumOf(ENVIRONMENTS), binding)));
    }

    public static Schema platformRuleSet() {
        Schema rule = object().field("id", ruleId())
                .field("name", string().min(1))
                .field("description", string().min(1))
                .field("instruction", string().min(1))
                .field("enforcement", enumOf("prompt", "guard", "both"));
        return defineKind("PlatformRuleSet", object().field("rules", array(rule).min(1)));
    }

    // ── 生命周期 ───────────────────────────────────────────────────────────

    public static final Map<String, List<String>> REQUIRED_ASSERTION_FIELDS = Map.of(
            "calledTool", List.of("tool"),
            "notCalledTool", List.of("tool"),
            "contains", List.of("text"),
            "notContains", List.of("text"),
            "numberRange", List.of("path"),
            "invariant", List.of("expression"),
            "judge", List.of("rubric"));

    public static Schema evalSuite() {
        Schema assertion = object().field(
                        "type",
                        enumOf(
                                "calledTool",
                                "notCalledTool",
                                "contains",
                                "notContains",
                                "numberRange",
                                "invariant",
                                "judge"))
                .optional("tool", string())
                .optional("text", string())
                .optional("path", string())
                .optional("min", number())
                .optional("max", number())
                .optional("expression", expression())
                .optional("rubric", string())
                .optional("description", string())
                .superRefine((v, ctx) -> {
                    String type = v.get("type").asString();
                    for (String field : REQUIRED_ASSERTION_FIELDS.get(type)) {
                        if (!v.has(field)) ctx.addIssue(field, type + " 断言必须写 " + field);
                    }
                });
        Schema evalCase = object().field("id", string().min(1))
                .field("user", string().min(1))
                .field("question", string().min(1))
                .optional("verifiedTask", string())
                .field("assertions", array(assertion).min(1));
        return defineKind(
                "EvalSuite",
                object().field("target", assetRef())
                        .withDefault("visibility", enumOf("public", "internal"), JSON.stringNode("internal"))
                        .withDefault("tier", enumOf("smoke", "full"), JSON.stringNode("full"))
                        .withDefault("minPassRate", number().min(0).max(1), JSON.numberNode(1))
                        .withDefault("cases", array(evalCase), JSON.arrayNode())
                        .optional("scenarios", string().min(1))
                        .superRefine((v, ctx) -> {
                            boolean noCases = !v.has("cases") || v.get("cases").isEmpty();
                            if (noCases && !v.has("scenarios")) ctx.addIssue("cases", "评测集至少要有一个用例或一段场景（scenarios）");
                        }));
    }

    public static Schema devRequest() {
        var defaultContext = JSON.objectNode();
        defaultContext.putArray("semanticObjects");
        defaultContext.putArray("rules");
        return defineKind(
                "DevRequest",
                object().field("requester", string().min(1))
                        .field("originalText", string().min(1))
                        .withDefault("supplements", array(string()), JSON.arrayNode())
                        .withDefault(
                                "context",
                                object().optional("assistant", assetRef())
                                        .withDefault("semanticObjects", array(string()), JSON.arrayNode())
                                        .withDefault("rules", array(string()), JSON.arrayNode()),
                                defaultContext)
                        .withDefault(
                                "status",
                                enumOf("submitted", "in-progress", "delivered", "rejected"),
                                JSON.stringNode("submitted")));
    }

    public static Schema solution() {
        return defineKind(
                "Solution", object().field("assets", array(pinnedAssetRef()).min(1)));
    }
}

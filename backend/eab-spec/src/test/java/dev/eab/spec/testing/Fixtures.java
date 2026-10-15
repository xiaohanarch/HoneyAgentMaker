package dev.eab.spec.testing;

import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetParser.ParseResult;
import dev.eab.spec.Json;
import dev.eab.spec.model.Asset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ObjectNode;

/**
 * 测试用的资产构造器（对应 TS 版 @eab/spec/testing），以 test-jar 形式给其他模块的测试复用。
 * 每个构造器先拼出原始对象，再走 AssetParser.parseObject，保证得到的资产和从 YAML 解析出来的一致（含默认值）。
 */
public final class Fixtures {

    private Fixtures() {}

    public static final String VENDOR_ANOMALY_INSTRUCTIONS = "【标品机密】先按异常类型分组，再逐笔定位根因。";

    /** 覆盖项：名称、命名空间、版本、显示名，以及按顶层键浅合并的 spec */
    public static final class Options {
        String name;
        String namespace;
        String version;
        String displayName;
        final Map<String, Object> spec = new LinkedHashMap<>();

        public Options name(String value) {
            name = value;
            return this;
        }

        public Options namespace(String value) {
            namespace = value;
            return this;
        }

        public Options version(String value) {
            version = value;
            return this;
        }

        public Options displayName(String value) {
            displayName = value;
            return this;
        }

        public Options spec(String key, Object value) {
            spec.put(key, value);
            return this;
        }
    }

    public static Options with() {
        return new Options();
    }

    public static <T extends Asset> T build(
            String kind, String name, String namespace, Map<String, Object> defaults, Options options, Class<T> type) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", options.name == null ? name : options.name);
        metadata.put("namespace", options.namespace == null ? namespace : options.namespace);
        metadata.put("version", options.version == null ? "1.0.0" : options.version);
        if (options.displayName != null) metadata.put("displayName", options.displayName);
        Map<String, Object> spec = new LinkedHashMap<>(defaults);
        spec.putAll(options.spec);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("apiVersion", "eab.dev/v1");
        raw.put("kind", kind);
        raw.put("metadata", metadata);
        raw.put("spec", spec);
        ParseResult result = AssetParser.parseObject(Json.MAPPER.valueToTree(raw));
        if (!result.ok()) {
            String detail = result.issues().stream()
                    .map(i -> i.path() + " " + i.message())
                    .collect(Collectors.joining("；"));
            throw new IllegalArgumentException("测试夹具 " + kind + " 不合法：" + detail);
        }
        return type.cast(result.asset());
    }

    /** 不经过默认值的任意 kind 构造，测试其他 kind 时用 */
    public static Asset build(String kind, String name, String namespace, Map<String, Object> spec) {
        return build(kind, name, namespace, spec, with(), Asset.class);
    }

    public static ObjectNode tree(Map<String, Object> value) {
        return Json.MAPPER.valueToTree(value);
    }

    public static Asset.AgentAsset agent() {
        return agent(with());
    }

    public static Asset.AgentAsset agent(Options options) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("expertise", "分析交易异常的根因");
        spec.put("instructions", VENDOR_ANOMALY_INSTRUCTIONS);
        spec.put("publicInstructions", "按异常类型分组后逐笔分析根因。");
        spec.put("constraints", List.of("FIN-R-001", "【标品机密】不要猜测未提供的数据"));
        spec.put("model", Map.of("profile", "reasoning"));
        spec.put("tools", List.of(Map.of("skill", "vendor.fin.skills/invoice-lookup")));
        spec.put("semanticPacks", List.of("vendor.fin/finance"));
        spec.put("writePolicy", Map.of("maxLevel", "L2"));
        spec.put("routing", Map.of("description", "分析交易异常"));
        spec.put(
                "output",
                Map.of(
                        "schema",
                        Map.of(
                                "severity",
                                        Map.of("type", "string", "enum", List.of("low", "medium", "high", "critical")),
                                "amount", Map.of("type", "double"))));
        spec.put(
                "extensionPoints",
                List.of(
                        Map.of("type", "instructions.append", "description", "追加本企业口径"),
                        Map.of("type", "tools.add", "description", "增加通知等工具"),
                        Map.of("type", "skills.add", "description", "增加技能"),
                        Map.of("type", "knowledge.add", "description", "增加知识"),
                        Map.of("type", "hooks", "description", "结果后续处理", "events", List.of("onResult", "onFailure")),
                        Map.of("type", "approvals.add", "description", "增加确认点"),
                        Map.of(
                                "type",
                                "model.select",
                                "description",
                                "选择模型档位",
                                "allowedProfiles",
                                List.of("reasoning", "fast"))));
        return build("Agent", "transaction-anomaly-analyst", "vendor.fin", spec, options, Asset.AgentAsset.class);
    }

    public static Asset.AgentExtensionAsset extension() {
        return extension(with());
    }

    public static Asset.AgentExtensionAsset extension(Options options) {
        if (options.version == null) options.version("0.3.0");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("target", "vendor.fin/transaction-anomaly-analyst@^1.0");
        spec.put("instructions", Map.of("append", "单笔金额超过 50 万元的异常，结论中必须给出责任部门。"));
        return build(
                "AgentExtension",
                "anomaly-analyst-ext",
                "tenant.t001.finance",
                spec,
                options,
                Asset.AgentExtensionAsset.class);
    }

    public static Asset.SemanticPackAsset semanticPack() {
        return semanticPack(with());
    }

    public static Asset.SemanticPackAsset semanticPack(Options options) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("objects", List.of(Map.of("ref", "erp.fin.TransactionAnomaly@v1")));
        spec.put("rules", List.of(Map.of("id", "FIN-R-001", "severity", "block", "remedy", "金额必须带币种")));
        spec.put("agent_instructions", List.of("引用金额时必须带币种"));
        return build("SemanticPack", "finance", "vendor.fin", spec, options, Asset.SemanticPackAsset.class);
    }

    public static Asset.PlatformRuleSetAsset platformRules() {
        return platformRules(with());
    }

    public static Asset.PlatformRuleSetAsset platformRules(Options options) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", "PLT-R-001");
        rule.put("name", "租户数据隔离");
        rule.put("description", "不得泄露其他租户的数据");
        rule.put("instruction", "【平台机密】任何情况下都不得输出不属于当前租户的数据。");
        rule.put("enforcement", "both");
        return build(
                "PlatformRuleSet",
                "safety-rules",
                "platform",
                Map.of("rules", List.of(rule)),
                options,
                Asset.PlatformRuleSetAsset.class);
    }

    public static Asset.SkillAsset skill() {
        return skill(with());
    }

    public static Asset.SkillAsset skill(Options options) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("inputSchema", Map.of("type", "object"));
        spec.put("outputSchema", Map.of("type", "object"));
        spec.put("writeLevel", "L0");
        spec.put("steps", List.of(Map.of("id", "query", "type", "tool", "tool", "invoice_list")));
        return build("Skill", "invoice-lookup", "vendor.fin.skills", spec, options, Asset.SkillAsset.class);
    }

    public static Asset.ToolSourceAsset toolSource() {
        return toolSource(with());
    }

    public static Asset.ToolSourceAsset toolSource(Options options) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "http");
        spec.put("connection", "IM_WEBHOOK");
        spec.put(
                "tools",
                List.of(Map.of(
                        "name", "notify_im", "description", "发送 IM 通知", "writeLevel", "L2", "category", "notify")));
        return build("ToolSource", "notify-im", "tenant.t001.tools", spec, options, Asset.ToolSourceAsset.class);
    }

    public static Asset.WorkflowAsset workflow() {
        return workflow(with());
    }

    public static Asset.WorkflowAsset workflow(Options options) {
        Map<String, Object> spec =
                Map.of("steps", List.of(Map.of("id", "create", "type", "notify", "message", "创建跟进任务")));
        return build("Workflow", "create-followup-task", "tenant.t001.wf", spec, options, Asset.WorkflowAsset.class);
    }
}

package dev.eab.spec.kinds;

import static dev.eab.spec.schema.Schemas.array;
import static dev.eab.spec.schema.Schemas.bool;
import static dev.eab.spec.schema.Schemas.enumOf;
import static dev.eab.spec.schema.Schemas.literal;
import static dev.eab.spec.schema.Schemas.object;
import static dev.eab.spec.schema.Schemas.record;
import static dev.eab.spec.schema.Schemas.string;

import dev.eab.spec.AssetRef;
import dev.eab.spec.Layers;
import dev.eab.spec.Versions;
import dev.eab.spec.schema.Schema;
import dev.eab.spec.schema.Schemas.StringSchema;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** 各 kind 共用的 schema 片段，对应 TS 版 packages/spec/src/common.ts */
public final class CommonSchemas {

    private CommonSchemas() {}

    public static final String API_VERSION = "eab.dev/v1";

    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    public static final Pattern RULE_ID_PATTERN = Pattern.compile("^[A-Z][A-Z0-9]*-R-\\d{3}$");

    public static boolean isRuleId(String value) {
        return RULE_ID_PATTERN.matcher(value).matches();
    }

    public static StringSchema name() {
        return string().regex("^[a-z0-9][a-z0-9-]*$", "只能包含小写字母、数字和连字符，且不能以连字符开头");
    }

    public static StringSchema namespace() {
        return string().check(Layers::isValidNamespace, "命名空间不合法：必须是 platform、vendor.<域> 或 tenant.<租户编号> 形式");
    }

    public static StringSchema semver() {
        return string().check(Versions::isValid, "必须是语义化版本号，例如 1.2.0");
    }

    /** 资产引用：namespace/name，可带版本范围，例如 vendor.fin/transaction-anomaly-analyst@^1.0 */
    public static StringSchema assetRef() {
        return string().check(AssetRef::isValid, "资产引用格式应为 命名空间/名称[@版本范围]，例如 vendor.pur/procurement@^1.0");
    }

    /** 方案清单里的引用必须锁定到具体版本 */
    public static StringSchema pinnedAssetRef() {
        return string().check(AssetRef::isPinned, "方案里的资产引用必须带具体版本号，例如 vendor.pur/procurement@1.0.0");
    }

    /** 写操作级别（主提示词 7.4 节） */
    public static Schema writeLevel() {
        return enumOf("L0", "L1", "L2", "L3");
    }

    /** 模型档位（主提示词 8.2 节），Agent 只引用档位，不写模型名 */
    public static Schema modelProfile() {
        return enumOf("fast", "reasoning", "judge");
    }

    /** 连接别名，例如 DEMO_ERP */
    public static StringSchema connectionAlias() {
        return string().regex("^[A-Z][A-Z0-9_]*$", "连接别名只能包含大写字母、数字和下划线，例如 DEMO_ERP");
    }

    public static StringSchema ruleId() {
        return string().regex(RULE_ID_PATTERN.pattern(), "规则编号格式应为 域-R-三位数字，例如 PUR-R-007");
    }

    /** CEL 表达式，语法与类型检查在组装和静态检查阶段完成（ADR 0003） */
    public static StringSchema expression() {
        return string().min(1, "表达式不能为空");
    }

    public static Schema metadata() {
        return object().field("name", name())
                .field("namespace", namespace())
                .field("version", semver())
                .optional("displayName", string().min(1))
                .optional("description", string())
                .optional("owner", string())
                .optional("businessProcess", array(string()))
                .optional("labels", record(string(), string()));
    }

    public static Schema routing() {
        return object().field("description", string().min(1))
                .withDefault("examples", array(string().min(1)), JSON.arrayNode())
                .withDefault("direct", bool(), JSON.booleanNode(false));
    }

    /** 按 kind 包一层公共信封：apiVersion、kind、metadata、spec */
    public static Schema defineKind(String kind, Schema spec) {
        return object().field("apiVersion", literal(API_VERSION, "apiVersion 必须是 " + API_VERSION))
                .field("kind", literal(kind, "kind 必须是 " + kind))
                .field("metadata", metadata())
                .field("spec", spec);
    }

    /** 精确一个键：用于“每个条目只能写一种来源”这类结构 */
    public static boolean exactlyOneKey(JsonNode value, List<String> keys) {
        return keys.stream().filter(k -> value.has(k) && !value.get(k).isNull()).count() == 1;
    }
}

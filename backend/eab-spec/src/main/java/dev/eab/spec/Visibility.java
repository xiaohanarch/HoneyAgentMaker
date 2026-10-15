package dev.eab.spec;

import dev.eab.spec.Layers.Layer;
import dev.eab.spec.kinds.CommonSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import dev.eab.spec.model.Asset.PlatformRuleSetAsset;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 三层资产的可见性与裁剪（主提示词 5.2 节、ADR 0004） */
public final class Visibility {

    private Visibility() {}

    public static final String VENDOR_INSTRUCTION_LABEL = "标品指令（不可见，运行时注入）";
    public static final String PLATFORM_RULE_LABEL = "平台安全规则原文（不可见）";

    /** 只有本层身份能写本层命名空间；租户只能写自己的命名空间 */
    public static boolean canWriteNamespace(Viewer viewer, String namespace) {
        try {
            Layer layer = Layers.layerOfNamespace(namespace);
            return switch (viewer) {
                case Viewer.Tenant t -> Layers.tenantIdOfNamespace(namespace)
                        .map(id -> id.equals(t.tenantId()))
                        .orElse(false);
                case Viewer.Vendor v -> layer == Layer.vendor;
                case Viewer.Platform p -> layer == Layer.platform;
            };
        } catch (Layers.InvalidNamespaceException e) {
            // 不合法的命名空间一律不可写，调用方不需要先校验
            return false;
        }
    }

    /**
     * 资产所在命名空间能引用哪些命名空间（防止通过引用越界读取）：
     * 平台层只能引用平台层；标品层能引用平台层和标品层；租户层能引用平台层、标品层和本租户。
     */
    public static boolean canReferenceNamespace(String fromNamespace, String toNamespace) {
        try {
            Layer from = Layers.layerOfNamespace(fromNamespace);
            Layer to = Layers.layerOfNamespace(toNamespace);
            if (to == Layer.platform) return true;
            if (to == Layer.vendor) return from != Layer.platform;
            return from == Layer.tenant
                    && Layers.tenantIdOfNamespace(fromNamespace).equals(Layers.tenantIdOfNamespace(toNamespace));
        } catch (Layers.InvalidNamespaceException e) {
            return false;
        }
    }

    /** 能否看到这个资产（裁剪前）：租户看不到其他租户和其他层的内部评测集；厂商看不到任何租户资产 */
    public static boolean canReadAsset(Viewer viewer, Asset asset) {
        Layer layer = asset.layer();
        if (viewer instanceof Viewer.Platform) return true;
        if (layer == Layer.tenant) {
            return viewer instanceof Viewer.Tenant t
                    && Layers.tenantIdOfNamespace(asset.metadata().namespace())
                            .map(id -> id.equals(t.tenantId()))
                            .orElse(false);
        }
        if (viewer instanceof Viewer.Vendor) return true;
        return !(asset instanceof EvalSuiteAsset suite && suite.spec().visibility().equals("internal"));
    }

    /**
     * 被隐藏的字段统一替换成这个占位对象；digest 只用于判断“是否已变更”。
     * 服务端应传入带密钥的摘要函数，否则短文本可能被字典比对猜出。
     */
    public record Redacted(boolean $redacted, String label, String digest) {
        public Redacted(String label, String digest) {
            this(true, label, digest);
        }
    }

    public static boolean isRedacted(JsonNode value) {
        return value != null && value.isObject() && value.path("$redacted").asBoolean(false);
    }

    public static Redacted redact(String text, String label, UnaryOperator<String> digest) {
        UnaryOperator<String> fn = digest == null ? Visibility::digestOf : digest;
        return new Redacted(label, fn.apply(text));
    }

    /** FNV-1a 32 位，与前端一致；只做变更检测，不是安全哈希，生产路径要用带密钥的摘要 */
    public static String digestOf(String text) {
        int hash = 0x811c9dc5;
        // 与 JS 的 charCodeAt 一致，按 UTF-16 码元计算
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x01000193;
        }
        return String.format("%08x", hash);
    }

    /**
     * 按查看者裁剪资产，返回 JSON 视图。只有租户查看标品层或平台层资产时需要裁剪：
     * Agent 的 instructions、additionalContext、自由文本约束隐藏，规则编号保留；平台规则只保留名称和说明。
     * 调用方应先用 canReadAsset 判断能否读取。
     */
    public static ObjectNode redactAsset(Asset asset, Viewer viewer, UnaryOperator<String> digest) {
        ObjectNode view = (ObjectNode) Json.toTree(asset);
        if (!(viewer instanceof Viewer.Tenant) || asset.layer() == Layer.tenant) return view;
        ObjectNode spec = (ObjectNode) view.get("spec");
        if (asset instanceof AgentAsset agent) {
            spec.set("instructions", hide(agent.spec().instructions(), VENDOR_INSTRUCTION_LABEL, digest));
            if (agent.spec().additionalContext() != null) {
                spec.set("additionalContext", hide(agent.spec().additionalContext(), VENDOR_INSTRUCTION_LABEL, digest));
            }
            ArrayNode constraints = spec.putArray("constraints");
            for (String c : agent.spec().constraints()) {
                if (CommonSchemas.isRuleId(c)) constraints.add(c);
                else constraints.add(hide(c, VENDOR_INSTRUCTION_LABEL, digest));
            }
        } else if (asset instanceof PlatformRuleSetAsset rules) {
            ArrayNode list = spec.putArray("rules");
            rules.spec().rules().forEach(rule -> {
                ObjectNode node = (ObjectNode) Json.toTree(rule);
                node.set("instruction", hide(rule.instruction(), PLATFORM_RULE_LABEL, digest));
                list.add(node);
            });
        }
        return view;
    }

    private static JsonNode hide(String text, String label, UnaryOperator<String> digest) {
        return Json.toTree(redact(text, label, digest));
    }
}

package dev.eab.spec;

import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.References.Reference;
import dev.eab.spec.kinds.CommonSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** 静态检查（主提示词 10.8.1 节），发布前运行，结果显示在发布面板上 */
public final class Lint {

    private Lint() {}

    public static final Map<String, String> RULES = new LinkedHashMap<>();

    static {
        RULES.put("no-hardcoded-endpoint", "资产里不得出现硬编码地址和凭据，只能引用连接别名");
        RULES.put("tool-write-level", "工具写操作级别必须声明，付款、过账类最高 L2");
        RULES.put("extension-point-declared", "扩展只能使用目标 Agent 已声明的扩展点");
        RULES.put("reference-resolvable", "引用的语义包规则、技能、工具必须存在且版本兼容");
        RULES.put("reference-scope", "只能引用平台层、标品层和本租户的资产");
    }

    public record LintIssue(String rule, String severity, String message, String path) {}

    /** statuses：引用解析时认为“存在”的状态，默认除已下线外都算 */
    public static List<LintIssue> lint(Asset asset, AssetResolver resolver, List<Status> statuses) {
        List<LintIssue> issues = new ArrayList<>();
        issues.addAll(hardcodedEndpoints(asset));
        issues.addAll(toolWriteLevels(asset));
        issues.addAll(extensionPoints(asset, resolver, statuses));
        issues.addAll(referenceScope(asset));
        issues.addAll(references(asset, resolver, statuses));
        return issues;
    }

    public static List<LintIssue> lint(Asset asset, AssetResolver resolver) {
        return lint(asset, resolver, AssetResolver.LIVE);
    }

    // ── reference-scope ───────────────────────────────────────────────────

    /** 引用范围在解析之前检查，越界的引用不进入解析，避免借报错信息探测其他租户的资产 */
    private static List<LintIssue> referenceScope(Asset asset) {
        return outOfScopeReferences(asset).stream()
                .map(r ->
                        new LintIssue("reference-scope", "error", "不能引用 " + r.ref() + "：只能引用平台层、标品层和本租户的资产", r.path()))
                .toList();
    }

    public static List<Reference> outOfScopeReferences(Asset asset) {
        return References.collect(asset).stream()
                .filter(r -> {
                    try {
                        return !Visibility.canReferenceNamespace(
                                asset.metadata().namespace(),
                                AssetRef.parse(r.ref()).namespace());
                    } catch (AssetRef.InvalidAssetRefException e) {
                        return false;
                    }
                })
                .toList();
    }

    // ── no-hardcoded-endpoint ─────────────────────────────────────────────

    private static final List<Pattern> ENDPOINT_PATTERNS = List.of(
            Pattern.compile("\\b(?:https?|wss?)://\\S+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{2,5})?\\b"),
            Pattern.compile("\\blocalhost:\\d{2,5}\\b", Pattern.CASE_INSENSITIVE));
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bghp_[A-Za-z0-9]{20,}"),
            Pattern.compile("\\bxox[bp]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"));
    private static final Pattern SECRET_KEY_NAMES = Pattern.compile(
            "^(password|passwd|secret|client_?secret|api_?key|access_?token|token)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDENTIAL_REF = Pattern.compile("^(env|vault):");

    private interface StringVisitor {
        void visit(String text, String path, String key);
    }

    private static void walkStrings(JsonNode value, String path, String key, StringVisitor visit) {
        if (value.isString()) {
            visit.visit(value.asString(), path, key);
        } else if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) walkStrings(value.get(i), path + "[" + i + "]", null, visit);
        } else if (value.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = value.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                walkStrings(entry.getValue(), child, entry.getKey(), visit);
            }
        }
    }

    private static List<LintIssue> hardcodedEndpoints(Asset asset) {
        List<LintIssue> issues = new ArrayList<>();
        walkStrings(Json.toTree(asset.spec()), "spec", null, (text, path, key) -> {
            // Connection 的环境绑定是唯一允许出现地址的地方
            if (asset instanceof Asset.ConnectionAsset && path.startsWith("spec.bindings.")) return;
            if (ENDPOINT_PATTERNS.stream().anyMatch(p -> p.matcher(text).find())) {
                issues.add(endpoint(path, "出现了硬编码地址，请改为引用连接别名"));
            } else if (SECRET_PATTERNS.stream().anyMatch(p -> p.matcher(text).find())) {
                issues.add(endpoint(path, "疑似包含凭据，凭据只能通过连接的 credentialRef 引用"));
            } else if (key != null
                    && SECRET_KEY_NAMES.matcher(key).matches()
                    && !CREDENTIAL_REF.matcher(text).find()) {
                issues.add(endpoint(path, "字段 " + key + " 看起来是凭据，只能写 env: 或 vault: 引用"));
            }
        });
        return issues;
    }

    private static LintIssue endpoint(String path, String message) {
        return new LintIssue("no-hardcoded-endpoint", "error", message, path);
    }

    // ── tool-write-level ──────────────────────────────────────────────────

    private static List<LintIssue> toolWriteLevels(Asset asset) {
        if (!(asset instanceof ToolSourceAsset source)) return List.of();
        List<LintIssue> issues = new ArrayList<>();
        var tools = source.spec().tools();
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i);
            String path = "spec.tools[" + i + "]";
            if (tool.writeLevel() == null) {
                issues.add(new LintIssue(
                        "tool-write-level", "error", "工具 " + tool.name() + " 没有声明写操作级别 writeLevel", path));
                continue;
            }
            boolean capped = "payment".equals(tool.category()) || "posting".equals(tool.category());
            if (capped && tool.writeLevel().equals("L3")) {
                issues.add(new LintIssue(
                        "tool-write-level",
                        "error",
                        "工具 " + tool.name() + " 属于付款或过账类，写操作级别最高只能到 L2",
                        path + ".writeLevel"));
            }
        }
        return issues;
    }

    // ── extension-point-declared ──────────────────────────────────────────

    private static List<LintIssue> extensionPoints(Asset asset, AssetResolver resolver, List<Status> statuses) {
        if (!(asset instanceof AgentExtensionAsset ext)) return List.of();
        return resolver.resolveOfKind(ext.spec().target(), AgentAsset.class, statuses)
                .map(target -> ext.spec().extensionPointViolations(target.spec().extensionPoints()).stream()
                        .map(v -> new LintIssue("extension-point-declared", "error", v.message(), v.path()))
                        .toList())
                // 目标不存在时由 reference-resolvable 报告
                .orElse(List.of());
    }

    // ── reference-resolvable ──────────────────────────────────────────────

    private static List<LintIssue> references(Asset asset, AssetResolver resolver, List<Status> statuses) {
        Set<String> outOfScope = new HashSet<>();
        outOfScopeReferences(asset).forEach(r -> outOfScope.add(r.path()));
        List<LintIssue> issues = new ArrayList<>();
        for (Reference reference : References.collect(asset)) {
            if (outOfScope.contains(reference.path())) continue;
            AssetRef ref = AssetRef.parse(reference.ref());
            var resolved = resolver.resolve(ref, statuses);
            if (resolved.isEmpty()) {
                String detail = ref.range() == null ? "不存在" : "不存在，或者没有满足 " + ref.range() + " 的版本";
                issues.add(refIssue("引用的资产 " + reference.ref() + " " + detail, reference.path()));
                continue;
            }
            AssetKind kind = AssetKind.of(resolved.get());
            if (!reference.kinds().isEmpty() && !reference.kinds().contains(kind)) {
                String expected = String.join(
                        " 或 ", reference.kinds().stream().map(Enum::name).toList());
                issues.add(refIssue(
                        "引用的资产 " + reference.ref() + " 是 " + kind.name() + "，这里需要 " + expected, reference.path()));
            }
        }
        issues.addAll(builtinTools(asset));
        issues.addAll(ruleIds(asset, resolver, statuses));
        return issues;
    }

    private static List<LintIssue> builtinTools(Asset asset) {
        if (!(asset instanceof AgentAsset agent)) return List.of();
        List<LintIssue> issues = new ArrayList<>();
        var tools = agent.spec().tools();
        for (int i = 0; i < tools.size(); i++) {
            var tool = tools.get(i).normalize();
            if (tool.type().equals("builtin")
                    && BuiltinTools.find(tool.builtinName()).isEmpty()) {
                issues.add(refIssue("内置工具 " + tool.builtinName() + " 不存在", "spec.tools[" + i + "].builtin"));
            }
        }
        return issues;
    }

    private static LintIssue refIssue(String message, String path) {
        return new LintIssue("reference-resolvable", "error", message, path);
    }

    /** 约束里引用的规则编号，必须能在相关语义包里找到 */
    private static List<LintIssue> ruleIds(Asset asset, AssetResolver resolver, List<Status> statuses) {
        List<String> constraints;
        List<String> packRefs = new ArrayList<>();
        String base;
        if (asset instanceof AgentAsset agent) {
            constraints = agent.spec().constraints();
            packRefs.addAll(agent.spec().semanticPacks());
            base = "spec.constraints";
        } else if (asset instanceof AgentExtensionAsset ext) {
            constraints = ext.spec().constraints() == null
                    ? List.of()
                    : ext.spec().constraints().append();
            resolver.resolveOfKind(ext.spec().target(), AgentAsset.class, statuses)
                    .ifPresent(target -> packRefs.addAll(target.spec().semanticPacks()));
            if (ext.spec().knowledge() != null)
                packRefs.addAll(ext.spec().knowledge().add());
            base = "spec.constraints.append";
        } else {
            return List.of();
        }
        Set<String> known = knownRuleIds(packRefs, resolver, statuses);
        List<LintIssue> issues = new ArrayList<>();
        for (int i = 0; i < constraints.size(); i++) {
            String c = constraints.get(i);
            if (CommonSchemas.isRuleId(c) && !known.contains(c)) {
                issues.add(refIssue("约束引用的规则 " + c + " 在所挂载的语义包里找不到", base + "[" + i + "]"));
            }
        }
        return issues;
    }

    private static Set<String> knownRuleIds(List<String> packRefs, AssetResolver resolver, List<Status> statuses) {
        Set<String> ids = new HashSet<>();
        for (String ref : packRefs) visitPack(ref, 0, resolver, statuses, ids);
        return ids;
    }

    private static void visitPack(
            String ref, int depth, AssetResolver resolver, List<Status> statuses, Set<String> ids) {
        if (!AssetRef.isValid(ref)) return;
        resolver.resolveOfKind(ref, SemanticPackAsset.class, statuses).ifPresent(pack -> {
            pack.spec().rules().forEach(rule -> ids.add(rule.id()));
            if (pack.spec().extendsRef() != null && depth < 5) {
                visitPack(pack.spec().extendsRef(), depth + 1, resolver, statuses, ids);
            }
        });
    }
}

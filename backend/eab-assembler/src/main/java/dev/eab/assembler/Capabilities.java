package dev.eab.assembler;

import dev.eab.assembler.EffectiveDefinition.EffectiveApproval;
import dev.eab.assembler.EffectiveDefinition.EffectiveModel;
import dev.eab.assembler.EffectiveDefinition.EffectiveTool;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.BuiltinTools;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.AgentExtensionSpec.ToolEntryOrRef;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.AgentSpec.ApprovalRule;
import dev.eab.spec.model.AgentSpec.NormalizedToolEntry;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 工具、确认点、模型档位、写级别的组装规则（主提示词 5.4 节第 2、3、5 条） */
public final class Capabilities {

    private Capabilities() {}

    /** 知识源作为文档检索工具暴露时的工具名 */
    public static String documentsToolName(String knowledgeSourceName) {
        return "kb_" + knowledgeSourceName.replace('-', '_');
    }

    record Expanded(List<EffectiveTool> tools, List<AssemblyIssue> issues) {
        static Expanded of(EffectiveTool tool) {
            return new Expanded(List.of(tool), List.of());
        }
    }

    record ToolResult(List<EffectiveTool> tools, List<AssemblyIssue> issues) {}

    private static Expanded unresolved(String ref, Asset owner, String path) {
        return new Expanded(List.of(), List.of(AssemblyIssue.error("REFERENCE_UNRESOLVED",
                "工具引用 " + ref + " 不存在、版本不满足，或者 kind 不对", owner.versionedRef(), path)));
    }

    private static Optional<Asset> resolve(String ref, AssetResolver resolver, List<Status> statuses) {
        return AssetRef.isValid(ref) ? resolver.resolve(ref, statuses) : Optional.empty();
    }

    /** 扩展里直接写的引用字符串，按被引用资产的 kind 归一化成工具条目 */
    private static Optional<NormalizedToolEntry> normalize(
            ToolEntryOrRef entry, AssetResolver resolver, List<Status> statuses) {
        if (entry instanceof ToolEntryOrRef.Entry e) return Optional.of(e.entry().normalize());
        String ref = ((ToolEntryOrRef.Ref) entry).ref();
        return resolve(ref, resolver, statuses).flatMap(asset -> switch (asset) {
            case Asset.ToolSourceAsset source -> Optional.of(new NormalizedToolEntry(
                    source.spec().type().equals("mcp") ? "mcp" : "api", ref, null, null));
            case Asset.SkillAsset ignored -> Optional.of(new NormalizedToolEntry("skill", ref, null, null));
            case Asset.AgentAsset ignored -> Optional.of(new NormalizedToolEntry("agent", ref, null, null));
            case Asset.RemoteAgentAsset ignored -> Optional.of(new NormalizedToolEntry("remoteAgent", ref, null, null));
            case Asset.KnowledgeSourceAsset ignored -> Optional.of(new NormalizedToolEntry("documents", ref, null, null));
            default -> Optional.empty();
        });
    }

    private static String descriptionOf(Asset asset) {
        var m = asset.metadata();
        if (m.description() != null) return m.description();
        return m.displayName() != null ? m.displayName() : m.name();
    }

    /** 把一个工具条目展开成暴露给模型的工具（工具来源可能包含多个工具） */
    private static Expanded expand(
            NormalizedToolEntry entry, Asset owner, String path, AssetResolver resolver, List<Status> statuses) {
        Layer layer = owner.layer();
        if (entry.type().equals("builtin")) {
            return BuiltinTools.find(entry.builtinName())
                    .map(b -> Expanded.of(new EffectiveTool(
                            b.name(), "builtin", Layer.platform, "platform/builtin", b.description(), b.writeLevel(), null)))
                    .orElseGet(() -> unresolved(entry.builtinName(), owner, path));
        }
        Optional<Asset> resolved = resolve(entry.ref(), resolver, statuses);
        if (resolved.isEmpty()) return unresolved(entry.ref(), owner, path);
        Asset asset = resolved.get();
        String source = asset.versionedRef();
        String description = descriptionOf(asset);
        return switch (entry.type()) {
            case "skill" -> asset instanceof Asset.SkillAsset skill
                    ? Expanded.of(new EffectiveTool(skill.metadata().name(), "skill", layer, source, description,
                            skill.spec().writeLevel(), null))
                    : unresolved(entry.ref(), owner, path);
            case "agent" -> asset instanceof AgentAsset agent
                    ? Expanded.of(new EffectiveTool(agent.metadata().name(), "agent", layer, source,
                            agent.spec().expertise(), agent.spec().writePolicy().maxLevel(), null))
                    : unresolved(entry.ref(), owner, path);
            case "remoteAgent" -> asset instanceof Asset.RemoteAgentAsset remote
                    ? Expanded.of(new EffectiveTool(remote.metadata().name(), "remoteAgent", layer, source,
                            remote.spec().routing().description(), null, null))
                    : unresolved(entry.ref(), owner, path);
            case "documents" -> asset instanceof Asset.KnowledgeSourceAsset kb
                    ? Expanded.of(new EffectiveTool(documentsToolName(kb.metadata().name()), "documents", layer, source,
                            "检索文档：" + description, "L0", null))
                    : unresolved(entry.ref(), owner, path);
            case "mcp", "api" -> {
                if (!(asset instanceof Asset.ToolSourceAsset toolSource)) yield unresolved(entry.ref(), owner, path);
                List<String> wanted = entry.tools();
                var defined = toolSource.spec().tools();
                List<String> missing = wanted == null
                        ? List.of()
                        : wanted.stream().filter(n -> defined.stream().noneMatch(t -> t.name().equals(n))).toList();
                if (!missing.isEmpty()) yield unresolved(entry.ref() + " 中的 " + String.join("、", missing), owner, path);
                List<EffectiveTool> tools = defined.stream()
                        .filter(t -> wanted == null || wanted.contains(t.name()))
                        .map(t -> new EffectiveTool(t.name(), entry.type(), layer, source, t.description(),
                                t.writeLevel(), t.category()))
                        .toList();
                yield new Expanded(tools, List.of());
            }
            default -> unresolved(entry.ref(), owner, path);
        };
    }

    /**
     * 工具集合 = 标品工具 ∪ 租户新增工具。租户工具与已有工具同名时报 TOOL_NAME_CONFLICT，发布会被拒绝。
     * 挂了语义包的 Agent 自动获得语义查询工具。
     */
    static ToolResult buildTools(
            AgentAsset agent,
            List<AgentExtensionAsset> extensions,
            boolean hasSemanticPacks,
            AssetResolver resolver,
            List<Status> statuses) {
        List<AssemblyIssue> issues = new ArrayList<>();
        List<EffectiveTool> base = new ArrayList<>();
        if (hasSemanticPacks) {
            for (var t : BuiltinTools.SEMANTIC) {
                base.add(new EffectiveTool(t.name(), "semantic", Layer.platform, "platform/semantic", t.description(),
                        t.writeLevel(), null));
            }
        }
        var vendorTools = agent.spec().tools();
        for (int i = 0; i < vendorTools.size(); i++) {
            Expanded e = expand(vendorTools.get(i).normalize(), agent, "spec.tools[" + i + "]", resolver, statuses);
            base.addAll(e.tools());
            issues.addAll(e.issues());
        }
        // 标品自身重复挂载同一个工具时去重
        Set<String> names = new LinkedHashSet<>();
        List<EffectiveTool> merged = new ArrayList<>();
        for (var tool : base) if (names.add(tool.name())) merged.add(tool);

        List<EffectiveTool> tenant = new ArrayList<>();
        for (var ext : extensions) {
            var spec = ext.spec();
            List<ToolEntryOrRef> added = spec.tools() == null ? List.of() : spec.tools().add();
            for (int i = 0; i < added.size(); i++) {
                String path = "spec.tools.add[" + i + "]";
                ToolEntryOrRef entry = added.get(i);
                Optional<NormalizedToolEntry> normalized = normalize(entry, resolver, statuses);
                Expanded e = normalized.isPresent()
                        ? expand(normalized.get(), ext, path, resolver, statuses)
                        : unresolved(entry instanceof ToolEntryOrRef.Ref r ? r.ref() : String.valueOf(entry), ext, path);
                tenant.addAll(e.tools());
                issues.addAll(e.issues());
            }
            List<String> skills = spec.skills() == null ? List.of() : spec.skills().add();
            for (int i = 0; i < skills.size(); i++) {
                Expanded e = expand(new NormalizedToolEntry("skill", skills.get(i), null, null), ext,
                        "spec.skills.add[" + i + "]", resolver, statuses);
                tenant.addAll(e.tools());
                issues.addAll(e.issues());
            }
            List<String> knowledge = spec.knowledge() == null ? List.of() : spec.knowledge().add();
            for (int i = 0; i < knowledge.size(); i++) {
                String ref = knowledge.get(i);
                boolean isKnowledgeSource = resolve(ref, resolver, statuses)
                        .filter(Asset.KnowledgeSourceAsset.class::isInstance)
                        .isPresent();
                if (!isKnowledgeSource) continue;
                Expanded e = expand(new NormalizedToolEntry("documents", ref, null, null), ext,
                        "spec.knowledge.add[" + i + "]", resolver, statuses);
                tenant.addAll(e.tools());
                issues.addAll(e.issues());
            }
        }
        for (var tool : tenant) {
            if (!names.add(tool.name())) {
                issues.add(AssemblyIssue.error("TOOL_NAME_CONFLICT",
                        "租户工具 " + tool.name() + "（" + tool.source() + "）与已有工具重名，不能发布", tool.source(), null));
                continue;
            }
            merged.add(tool);
        }
        return new ToolResult(merged, issues);
    }

    /** 确认点匹配工具：按工具名，或按工具所在资产（不带版本的引用） */
    public static boolean approvalMatchesTool(String approvalTool, EffectiveTool tool) {
        if (approvalTool.equals(tool.name())) return true;
        if (!AssetRef.isValid(approvalTool)) return false;
        return tool.source().split("@")[0].equals(approvalTool.split("@")[0]);
    }

    record ApprovalResult(List<EffectiveApproval> approvals, List<AssemblyIssue> issues) {}

    private static List<EffectiveApproval> fromAsset(Asset owner, List<ApprovalRule> rules) {
        return rules.stream()
                .map(r -> new EffectiveApproval(r.tool(), r.policy(), r.when(), r.approverRole(), owner.layer(),
                        owner.versionedRef()))
                .toList();
    }

    /**
     * 人工确认策略取最严格者。标品和租户的确认点都保留，运行时对匹配的规则取最严；
     * 租户对标品已要求确认的工具声明更宽松的无条件策略时，报 APPROVAL_RELAXED。
     */
    static ApprovalResult mergeApprovals(
            AgentAsset agent, List<AgentExtensionAsset> extensions, List<EffectiveTool> tools) {
        List<EffectiveApproval> vendor = fromAsset(agent, agent.spec().approvals());
        List<EffectiveApproval> tenant = new ArrayList<>();
        for (var ext : extensions) {
            if (ext.spec().approvals() != null) tenant.addAll(fromAsset(ext, ext.spec().approvals().add()));
        }
        List<EffectiveApproval> all = new ArrayList<>(vendor);
        all.addAll(tenant);

        List<AssemblyIssue> issues = new ArrayList<>();
        var env = Conditions.approvalEnvironment();
        for (var approval : all) {
            if (tools.stream().noneMatch(t -> approvalMatchesTool(approval.tool(), t))) {
                issues.add(AssemblyIssue.error("REFERENCE_UNRESOLVED",
                        "确认点指向的工具 " + approval.tool() + " 不在这个 Agent 的工具集合里", approval.source(), null));
            }
            if (approval.when() != null) {
                var check = Conditions.check(env, approval.when());
                if (!check.valid()) {
                    issues.add(AssemblyIssue.error("APPROVAL_CONDITION_INVALID",
                            "确认点条件 \"" + approval.when() + "\" 无效：" + check.reason(), approval.source(), null));
                }
            }
        }
        // 租户确认点只能更严格（主提示词 5.3 节）：不能声明 auto，也不能对标品已要求更严策略的工具声明更宽松的策略
        for (var t : tenant) {
            if (t.policy().equals("auto")) {
                issues.add(AssemblyIssue.error("APPROVAL_RELAXED",
                        "租户扩展不能把 " + t.tool() + " 的确认策略声明为 auto，确认点只能更严格", t.source(), null));
                continue;
            }
            List<EffectiveTool> affected = tools.stream().filter(tool -> approvalMatchesTool(t.tool(), tool)).toList();
            vendor.stream()
                    .filter(v -> v.when() == null
                            && AgentSpec.approvalStrictness(v.policy()) > AgentSpec.approvalStrictness(t.policy())
                            && affected.stream().anyMatch(tool -> approvalMatchesTool(v.tool(), tool)))
                    .findFirst()
                    .ifPresent(stricter -> issues.add(AssemblyIssue.error("APPROVAL_RELAXED",
                            "标品对 " + stricter.tool() + " 要求 " + stricter.policy() + "，租户扩展不能放宽为 " + t.policy(),
                            t.source(), null)));
        }
        return new ApprovalResult(all, issues);
    }

    record ModelResult(EffectiveModel model, List<AssemblyIssue> issues) {}

    /** 模型配置：租户只能在标品声明的范围内选择（主提示词 5.4 节第 5 条） */
    static ModelResult resolveModel(AgentAsset agent, List<AgentExtensionAsset> extensions) {
        EffectiveModel model = new EffectiveModel(
                agent.spec().model().profile(), agent.spec().model().temperature(), agent.layer());
        List<AssemblyIssue> issues = new ArrayList<>();
        var point = agent.spec().extensionPoints().stream()
                .filter(p -> p.type().equals("model.select"))
                .findFirst();
        for (var ext : extensions) {
            if (ext.spec().model() == null) continue;
            String profile = ext.spec().model().profile();
            // 标品没有开放 model.select 时，由扩展点检查报 EXTENSION_POINT_UNDECLARED
            if (point.isEmpty()) continue;
            List<String> allowed = point.get().allowedProfiles();
            if (allowed == null || !allowed.contains(profile)) {
                String list = allowed == null ? "（未开放选择）" : String.join("、", allowed);
                issues.add(AssemblyIssue.error("MODEL_NOT_ALLOWED", "模型档位 " + profile + " 不在标品允许的范围内：" + list,
                        ext.versionedRef(), "spec.model.profile"));
                continue;
            }
            model = new EffectiveModel(profile, model.temperature(), ext.layer());
        }
        return new ModelResult(model, issues);
    }

    /** 匹配某个工具的全部确认点，运行时先按 when 条件过滤，再用 strictestPolicy 取最严 */
    public static List<EffectiveApproval> matchingApprovals(List<EffectiveApproval> approvals, EffectiveTool tool) {
        return approvals.stream().filter(a -> approvalMatchesTool(a.tool(), tool)).toList();
    }

    /** 人工确认策略取最严格者，没有匹配时为 auto */
    public static String strictestPolicy(List<EffectiveApproval> approvals) {
        String strictest = "auto";
        for (var a : approvals) {
            if (AgentSpec.approvalStrictness(a.policy()) > AgentSpec.approvalStrictness(strictest)) strictest = a.policy();
        }
        return strictest;
    }

    private static final List<String> WRITE_LEVEL_ORDER = List.of("L0", "L1", "L2", "L3");

    /** 租户新增的工具写级别不能超过标品 Agent 的写级别上限，租户也不能抬高这个上限 */
    static List<AssemblyIssue> checkWriteLevels(AgentAsset agent, List<EffectiveTool> tools) {
        String maxLevel = agent.spec().writePolicy().maxLevel();
        int max = WRITE_LEVEL_ORDER.indexOf(maxLevel);
        return tools.stream()
                .filter(t -> t.layer() == Layer.tenant
                        && t.writeLevel() != null
                        && WRITE_LEVEL_ORDER.indexOf(t.writeLevel()) > max)
                .map(t -> AssemblyIssue.error("TOOL_WRITE_LEVEL_EXCEEDED",
                        "租户工具 " + t.name() + " 的写级别 " + t.writeLevel() + " 超过了 Agent 的上限 " + maxLevel,
                        t.source(), null))
                .toList();
    }
}

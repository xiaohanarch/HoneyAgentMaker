package dev.eab.assembler;

import dev.eab.assembler.EffectiveDefinition.Provenance;
import dev.eab.assembler.EffectiveDefinition.ResolvedTarget;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.AssetResolver.VersionInfo;
import dev.eab.spec.Versions;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.PlatformRuleSetAsset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 运行时组装器，把平台安全规则、标品定义、租户扩展组装成有效定义。
 * 规则见 docs/brief/02-主提示词.md 5.4 节，修改前先读，改完必须补测试。
 */
public final class Assembler {

    private Assembler() {}

    /**
     * @param agent 要组装的 Agent，namespace/name，可带版本范围
     * @param extensions 候选扩展：调用方取出该租户全部已发布的扩展，这里按 target 过滤
     * @param platformRules 平台安全规则，排在最前，不可覆盖
     * @param statuses 参与解析的资产状态，默认只用已发布版本；私有环境测试时可以带上草稿
     */
    public record Input(
            String agent,
            String tenantId,
            List<AgentExtensionAsset> extensions,
            List<PlatformRuleSetAsset> platformRules,
            AssetResolver resolver,
            List<Status> statuses) {

        public Input {
            extensions = extensions == null ? List.of() : List.copyOf(extensions);
            platformRules = platformRules == null ? List.of() : List.copyOf(platformRules);
            statuses = statuses == null ? AssetResolver.PUBLISHED : List.copyOf(statuses);
        }
    }

    public sealed interface Result {
        record Ok(EffectiveDefinition definition, List<AssemblyIssue> issues) implements Result {}

        record Failed(List<AssemblyIssue> issues) implements Result {}

        List<AssemblyIssue> issues();

        default boolean ok() {
            return this instanceof Ok;
        }

        default EffectiveDefinition definition() {
            if (this instanceof Ok success) return success.definition();
            throw new IllegalStateException("组装失败：" + issues());
        }
    }

    private static Result notFound(String message) {
        return new Result.Failed(List.of(AssemblyIssue.error("AGENT_NOT_FOUND", message, null, null)));
    }

    /** 纯函数：只读解析器，不抛异常，问题通过 issues 返回。存在 error 级问题时不产出有效定义 */
    public static Result assemble(Input input) {
        AssetRef requested;
        try {
            requested = AssetRef.parse(input.agent());
        } catch (AssetRef.InvalidAssetRefException e) {
            return notFound(e.getMessage());
        }
        List<AgentExtensionAsset> targeting = input.extensions().stream()
                .filter(ext -> {
                    AssetRef target = AssetRef.parse(ext.spec().target());
                    return target.namespace().equals(requested.namespace()) && target.name().equals(requested.name());
                })
                .toList();

        // 版本解析（第 6 条）：先按请求解析出当前生效的标品版本，再看每个扩展的 target 范围是否覆盖它
        List<String> candidates = input.resolver().listVersions(requested.namespace(), requested.name()).stream()
                .filter(v -> input.statuses().contains(v.status()))
                .map(VersionInfo::version)
                .toList();
        Optional<String> resolvedVersion =
                Versions.maxSatisfying(candidates, requested.range() == null ? "*" : requested.range());
        if (resolvedVersion.isEmpty()) return notFound("找不到 Agent " + input.agent() + " 的可用版本");
        String version = resolvedVersion.get();

        Optional<Asset> found = input.resolver().get(requested.namespace(), requested.name(), version);
        if (found.isEmpty() || !(found.get() instanceof AgentAsset agent)) return notFound(input.agent() + " 不是 Agent");

        // 范围不覆盖当前版本的扩展本次不生效，给出警告；不让一个过期扩展拖垮整个 Agent
        List<AgentExtensionAsset> extensions = new ArrayList<>();
        List<AssemblyIssue> issues = new ArrayList<>();
        for (var ext : targeting) {
            String range = rangeOf(ext);
            if (Versions.satisfies(version, range)) {
                extensions.add(ext);
            } else {
                issues.add(AssemblyIssue.warning("TARGET_UNRESOLVED",
                        "扩展的目标范围 " + range + " 不包含当前标品版本 " + version + "，本次未生效", ext.versionedRef(),
                        "spec.target"));
            }
        }

        // 模型档位越界由 resolveModel 报 MODEL_NOT_ALLOWED，这里不重复报
        for (var ext : extensions) {
            for (var v : ext.spec().extensionPointViolations(agent.spec().extensionPoints())) {
                if (v.path().equals("spec.model.profile")) continue;
                issues.add(AssemblyIssue.error("EXTENSION_POINT_UNDECLARED", v.message(), ext.versionedRef(), v.path()));
            }
        }

        var packs = Segments.resolveSemanticPacks(agent, extensions, input.resolver(), input.statuses());
        var segments = Segments.build(input.platformRules(), agent, packs.packs(), extensions);
        var tools = Capabilities.buildTools(agent, extensions, !packs.packs().isEmpty(), input.resolver(), input.statuses());
        var approvals = Capabilities.mergeApprovals(agent, extensions, tools.tools());
        var hooks = Hooks.merge(agent, extensions);
        var model = Capabilities.resolveModel(agent, extensions);

        issues.addAll(packs.issues());
        issues.addAll(segments.issues());
        issues.addAll(tools.issues());
        issues.addAll(approvals.issues());
        issues.addAll(Capabilities.checkWriteLevels(agent, tools.tools()));
        issues.addAll(hooks.issues());
        issues.addAll(model.issues());
        if (issues.stream().anyMatch(AssemblyIssue::isError)) return new Result.Failed(issues);

        List<String> packRefs = packs.packs().stream().map(Asset::versionedRef).toList();
        var spec = agent.spec();
        var provenance = new Provenance(
                "agent:" + agent.metadata().namespace() + "/" + agent.metadata().name(),
                input.tenantId(),
                agent.versionedRef(),
                extensions.isEmpty()
                        ? null
                        : new ResolvedTarget(extensions.stream().map(Assembler::rangeOf).toList(), version),
                extensions.stream().map(Asset::versionedRef).toList(),
                input.platformRules().stream().map(Asset::versionedRef).toList(),
                packRefs);
        var definition = new EffectiveDefinition(
                agent.metadata().name(),
                agent.metadata().displayName() == null ? agent.metadata().name() : agent.metadata().displayName(),
                spec.expertise(),
                segments.segments(),
                tools.tools(),
                approvals.approvals(),
                hooks.hooks(),
                model.model(),
                packRefs,
                spec.writePolicy(),
                spec.executionSteps() == null
                        ? new AgentSpec.ExecutionSteps(List.of(), List.of())
                        : spec.executionSteps(),
                spec.output(),
                spec.routing(),
                provenance);
        return new Result.Ok(definition, issues);
    }

    private static String rangeOf(AgentExtensionAsset ext) {
        String range = AssetRef.parse(ext.spec().target()).range();
        return range == null ? "*" : range;
    }
}

package dev.eab.platform.registry;

import dev.eab.assembler.Assembler;
import dev.eab.assembler.AssemblyIssue;
import dev.eab.assembler.DefinitionView;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.PlatformRuleSetAsset;
import java.util.List;
import tools.jackson.databind.node.ObjectNode;

/**
 * 从注册中心取出平台规则、标品 Agent 和租户扩展，组装出有效定义，并按查看者裁剪。
 * definition 是完整内容，只能交给运行时；对外只返回 view。
 */
public final class EffectiveAssembly {

    private EffectiveAssembly() {}

    /**
     * @param agent namespace/name，可带版本范围
     * @param tenantId 按哪个租户组装：叠加该租户已发布的扩展，并以该租户的可见范围解析资产
     * @param candidateExtensions 替换候选扩展，发布门禁试组装时传入“其他已发布扩展 + 待发布的扩展”
     * @param overlay 私有环境里叠加的工作区草稿（OverlayResolver），没有时为空
     */
    public record Request(
            String agent,
            String tenantId,
            List<Status> statuses,
            List<AgentExtensionAsset> candidateExtensions,
            List<Asset> overlay) {

        public Request(
                String agent, String tenantId, List<Status> statuses, List<AgentExtensionAsset> candidateExtensions) {
            this(agent, tenantId, statuses, candidateExtensions, null);
        }

        public static Request of(String agent, String tenantId) {
            return new Request(agent, tenantId, null, null, null);
        }

        public static Request withOverlay(String agent, String tenantId, List<Asset> overlay) {
            return new Request(agent, tenantId, null, null, overlay);
        }
    }

    /** 组装失败时的问题编码，比组装器多出 INTEGRITY_VIOLATION、PLATFORM_RULES_MISSING */
    public sealed interface Outcome {
        record Ok(EffectiveDefinition definition, ObjectNode view, List<AssemblyIssue> issues) implements Outcome {}

        record Failed(List<AssemblyIssue> issues) implements Outcome {}

        List<AssemblyIssue> issues();

        default boolean ok() {
            return this instanceof Ok;
        }
    }

    private static Outcome integrity(List<String> refs) {
        return new Outcome.Failed(refs.stream()
                .map(ref -> AssemblyIssue.error("INTEGRITY_VIOLATION", "资产 " + ref + " 完整性校验失败，已拒绝加载", ref, null))
                .toList());
    }

    /**
     * 资产解析的范围：给了 tenantId 时按该租户可见的范围（平台层、已发布的标品层、本租户），否则按查看者本人的范围。
     * 调用方负责先确认查看者有权以该租户的身份组装。
     * 平台规则缺失或任何资产完整性校验失败时，组装失败，而不是少一段规则继续运行。
     */
    public static Outcome assemble(RegistryService registry, Viewer viewer, Request request) {
        Viewer scope = request.tenantId() == null ? viewer : Viewer.tenant(request.tenantId());
        ScopedResolver scoped = registry.resolverFor(scope);
        var resolver = request.overlay() == null || request.overlay().isEmpty()
                ? (dev.eab.spec.AssetResolver) scoped
                : new OverlayResolver(scoped, request.overlay());

        var rules = registry.latestPublished(new AssetFilter("PlatformRuleSet", null, "platform", null, null), scope);
        if (!rules.rejected().isEmpty()) return integrity(rules.rejected());
        List<PlatformRuleSetAsset> platformRules = rules.assets().stream()
                .filter(PlatformRuleSetAsset.class::isInstance)
                .map(PlatformRuleSetAsset.class::cast)
                .toList();
        if (platformRules.isEmpty()) {
            return new Outcome.Failed(
                    List.of(AssemblyIssue.error("PLATFORM_RULES_MISSING", "没有可用的平台安全规则，拒绝组装", null, null)));
        }

        List<AgentExtensionAsset> extensions = List.of();
        if (request.candidateExtensions() != null) {
            extensions = request.candidateExtensions();
        } else if (request.tenantId() != null) {
            var published = registry.latestPublished(
                    new AssetFilter("AgentExtension", null, null, request.tenantId(), null), scope);
            if (!published.rejected().isEmpty()) return integrity(published.rejected());
            extensions = published.assets().stream()
                    .filter(AgentExtensionAsset.class::isInstance)
                    .map(AgentExtensionAsset.class::cast)
                    .toList();
        }

        Assembler.Result result = Assembler.assemble(new Assembler.Input(
                request.agent(), request.tenantId(), extensions, platformRules, resolver, request.statuses()));
        List<String> failures = scoped.integrityFailures();
        if (!failures.isEmpty()) return integrity(failures);
        if (!(result instanceof Assembler.Result.Ok success)) return new Outcome.Failed(result.issues());
        return new Outcome.Ok(
                success.definition(),
                DefinitionView.view(success.definition(), viewer, registry.signer()::digest),
                success.issues());
    }

    /** 包装一个资产列表里的扩展，供门禁试组装使用 */
    static List<AgentExtensionAsset> extensionsOf(List<Asset> assets) {
        return assets.stream()
                .filter(AgentExtensionAsset.class::isInstance)
                .map(AgentExtensionAsset.class::cast)
                .toList();
    }
}

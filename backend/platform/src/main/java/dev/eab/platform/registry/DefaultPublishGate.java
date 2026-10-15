package dev.eab.platform.registry;

import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.spec.AssetParser.AssetIssue;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Lint;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布门禁（主提示词 10.8.1、5.4 节）：
 * 所有资产发布前跑静态检查，error 级问题阻止发布；租户扩展还要用“该租户其他已发布扩展 + 本扩展”试组装目标 Agent，
 * 组装错误阻止发布（例如租户工具重名），本扩展自身的警告也阻止发布（例如钩子条件无效）。评测门禁在 P8 加入。
 */
public final class DefaultPublishGate implements RegistryService.PublishGate {

    @Override
    public List<AssetIssue> check(Asset asset, Viewer publisher, RegistryService registry) {
        List<AssetIssue> lint = Lint.lint(asset, registry.resolverFor(publisher)).stream()
                .filter(issue -> issue.severity().equals("error"))
                .map(issue -> new AssetIssue("LINT_" + issue.rule(), issue.message(), issue.path(), null, null))
                .toList();
        if (!(asset instanceof AgentExtensionAsset extension)
                || !(publisher instanceof Viewer.Tenant tenant)
                || !lint.isEmpty()) {
            return lint;
        }

        String self =
                extension.metadata().namespace() + "/" + extension.metadata().name();
        List<AgentExtensionAsset> candidates = new ArrayList<>(EffectiveAssembly.extensionsOf(registry.latestPublished(
                        new AssetFilter("AgentExtension", null, null, tenant.tenantId(), null), publisher)
                .assets()));
        candidates.removeIf(e -> (e.metadata().namespace() + "/" + e.metadata().name()).equals(self));
        candidates.add(extension);
        AssetRef target = AssetRef.parse(extension.spec().target());
        var outcome = EffectiveAssembly.assemble(
                registry,
                publisher,
                new EffectiveAssembly.Request(target.key(), tenant.tenantId(), AssetResolver.LIVE, candidates));
        String selfRef = extension.versionedRef();
        return outcome.issues().stream()
                .filter(issue -> issue.isError() || selfRef.equals(issue.source()))
                .map(issue -> new AssetIssue(
                        "ASSEMBLY_" + issue.code(),
                        issue.message(),
                        issue.path() == null ? "" : issue.path(),
                        null,
                        null))
                .toList();
    }
}

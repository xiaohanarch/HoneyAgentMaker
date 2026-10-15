package dev.eab.platform.cli;

import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Layers;
import dev.eab.spec.Lint;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * pnpm assets:validate
 * 校验仓库里的全部资产：schema、静态检查（主提示词 10.8.1 节），并试组装每个 Agent 及其租户扩展。
 * 有 error 级问题时以退出码 1 结束。
 */
public final class ValidateAssets {

    private ValidateAssets() {}

    record Problem(String where, String severity, String message) {}

    record Target(String agent, String tenantId) {}

    static List<Target> agentTargets(List<Asset> assets) {
        List<Target> targets = new ArrayList<>();
        for (Asset asset : assets) {
            if (asset instanceof AgentAsset) {
                targets.add(new Target(
                        asset.metadata().namespace() + "/" + asset.metadata().name(), null));
            }
        }
        for (Asset asset : assets) {
            if (!(asset instanceof AgentExtensionAsset ext)) continue;
            var tenantId = Layers.tenantIdOfNamespace(ext.metadata().namespace());
            tenantId.ifPresent(id ->
                    targets.add(new Target(AssetRef.parse(ext.spec().target()).key(), id)));
        }
        return targets;
    }

    public static int run(Path assetsRoot, PrintStream out) {
        var loaded = RepoRegistry.load(assetsRoot);
        var registry = loaded.services().registry();
        var latest = registry.latestPublished(AssetFilter.all(), null);
        var resolver = registry.resolverFor(Viewer.PLATFORM);

        List<Problem> problems = new ArrayList<>();
        loaded.seed().failed().forEach(f -> problems.add(new Problem(f.file(), "error", f.message())));
        latest.rejected().forEach(ref -> problems.add(new Problem(ref, "error", "完整性校验失败，已拒绝加载")));
        for (Asset asset : latest.assets()) {
            for (var issue : Lint.lint(asset, resolver)) {
                problems.add(new Problem(
                        asset.metadata().namespace() + "/" + asset.metadata().name() + " " + issue.path(),
                        issue.severity(),
                        "[" + issue.rule() + "] " + issue.message()));
            }
        }
        for (Target target : agentTargets(latest.assets())) {
            Viewer viewer = target.tenantId() == null ? Viewer.VENDOR : Viewer.tenant(target.tenantId());
            var outcome = EffectiveAssembly.assemble(
                    registry, viewer, EffectiveAssembly.Request.of(target.agent(), target.tenantId()));
            for (var issue : outcome.issues()) {
                problems.add(new Problem(
                        "组装 " + target.agent() + (target.tenantId() == null ? "" : " + " + target.tenantId()),
                        issue.severity(),
                        "[" + issue.code() + "] " + issue.message()));
            }
        }

        for (Problem p : problems) {
            String tag = p.severity().equals("error") ? Ansi.red("错误") : Ansi.yellow("警告");
            out.println(tag + " " + Ansi.dim(p.where()) + "  " + p.message());
        }
        long errors =
                problems.stream().filter(p -> p.severity().equals("error")).count();
        out.println("\n共 " + latest.assets().size() + " 个资产，" + errors + " 个错误，" + (problems.size() - errors) + " 个警告"
                + (errors == 0 ? Ansi.green("，校验通过") : ""));
        loaded.services().db().close();
        return errors == 0 ? 0 : 1;
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        System.exit(run(dev.eab.platform.PlatformProperties.repoRoot().resolve("assets"), System.out));
    }
}

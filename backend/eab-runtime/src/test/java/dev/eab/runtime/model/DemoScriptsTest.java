package dev.eab.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.AssetLoader;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.RepoPaths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** demo/scripts 下的全部剧本：格式合法、编号不重复、Agent 存在，示例话术能匹配到剧本 */
class DemoScriptsTest {

    static final List<Scripts.Script> SCRIPTS =
            Scripts.loadDirectory(RepoPaths.root().resolve("demo").resolve("scripts"));

    static final List<Asset.AgentAsset> AGENTS =
            AssetLoader.loadDirectory(RepoPaths.assets().resolve("vendor")).assets().stream()
                    .map(AssetLoader.LoadedAsset::asset)
                    .filter(Asset.AgentAsset.class::isInstance)
                    .map(Asset.AgentAsset.class::cast)
                    .toList();

    /** Studio 推荐用的平台身份，以及故事 A 在向导里新建、发布前没有资产文件的 Agent */
    static final Set<String> STUDIO_AGENTS =
            Set.of("platform/studio", "tenant.t001.agents/price-negotiation-assistant");

    @Test
    @DisplayName("剧本全部能加载，引用的 Agent 是仓库里的标品 Agent，或 Studio 的建模助手与故事 A 新建的 Agent")
    void loads() {
        Set<String> agents = AGENTS.stream()
                .map(a -> a.metadata().namespace() + "/" + a.metadata().name())
                .collect(Collectors.toCollection(java.util.HashSet::new));
        agents.addAll(STUDIO_AGENTS);
        assertThat(SCRIPTS).isNotEmpty();
        assertThat(SCRIPTS).allMatch(s -> agents.contains(s.agent()));
    }

    @Test
    @DisplayName("四个标品 Agent 各至少有一条剧本，并且它们的第一条示例话术能匹配到剧本")
    void everyVendorAgentCovered() {
        for (String agent : List.of(
                "vendor.pur/supplier-performance-analyst",
                "vendor.fin/transaction-anomaly-analyst",
                "vendor.fin/period-close-checker",
                "vendor.scm/inventory-risk-analyst")) {
            var asset = AGENTS.stream()
                    .filter(a -> (a.metadata().namespace() + "/" + a.metadata().name()).equals(agent))
                    .findFirst()
                    .orElseThrow();
            String example = asset.spec().routing().examples().getFirst();
            assertThat(Scripts.match(SCRIPTS, agent, "anyone", example))
                    .as(agent + "：" + example)
                    .isNotNull();
        }
    }
}

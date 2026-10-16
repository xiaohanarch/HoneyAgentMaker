package dev.eab.platform.registry;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.spec.Lint;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.testing.RepoPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** 仓库 assets/ 下的真实资产：全部合法、静态检查干净、可组装，且租户视角不泄露标品指令 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepoRegistryTest {

    static final Viewer T001 = Viewer.tenant("t001");

    PlatformServices services;
    AssetSeeder.Report seed;
    List<Asset> assets;

    /** 平台、标品，以及 tenants/ 下的每个租户快照 */
    static List<String> subdirectories(Path root) throws IOException {
        List<String> subs = new ArrayList<>(List.of("platform", "vendor"));
        Path tenants = root.resolve("tenants");
        if (Files.isDirectory(tenants)) {
            try (Stream<Path> dirs = Files.list(tenants)) {
                dirs.filter(Files::isDirectory).sorted().forEach(d -> subs.add("tenants/" + d.getFileName()));
            }
        }
        return subs;
    }

    @BeforeAll
    void loadRepository() throws IOException {
        services = TestPlatform.services();
        seed = AssetSeeder.seed(services.registry(), RepoPaths.assets(), subdirectories(RepoPaths.assets()));
        assets = services.registry().latestPublished(AssetFilter.all(), null).assets();
    }

    @Test
    @DisplayName("全部资产通过 schema 校验并签名入库，静态检查没有问题")
    void allAssetsPublished() {
        assertThat(seed.failed()).isEmpty();
        assertThat(assets).hasSizeGreaterThanOrEqualTo(25);
        var problems = assets.stream()
                .flatMap(a -> Lint.lint(a, services.registry().resolverFor(Viewer.PLATFORM)).stream()
                        .map(i -> a.metadata().name() + " " + i.path() + " " + i.message()))
                .toList();
        assertThat(problems).isEmpty();
    }

    @Test
    @DisplayName("三个语义包各有至少 5 个对象、3 条规则、2 个指标、5 条已验证任务（主提示词 13.1 节）")
    void semanticPacks() {
        var packs = assets.stream()
                .filter(a -> a instanceof SemanticPackAsset
                        && a.metadata().namespace().startsWith("vendor."))
                .map(SemanticPackAsset.class::cast)
                .toList();
        assertThat(packs).hasSize(3);
        for (var pack : packs) {
            assertThat(pack.spec().objects()).as(pack.metadata().name()).hasSizeGreaterThanOrEqualTo(5);
            assertThat(pack.spec().rules()).as(pack.metadata().name()).hasSizeGreaterThanOrEqualTo(3);
            assertThat(pack.spec().metrics()).as(pack.metadata().name()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(pack.spec().verifiedTasks()).as(pack.metadata().name()).hasSizeGreaterThanOrEqualTo(5);
        }
    }

    @Test
    @DisplayName("六个标品 Agent 都能组装，并且都带公开版指令")
    void agentsAssemble() {
        var agents = assets.stream()
                .filter(AgentAsset.class::isInstance)
                .map(AgentAsset.class::cast)
                .toList();
        assertThat(agents.stream().map(a -> a.metadata().name()).sorted())
                .containsExactly(
                        "inventory-risk-analyst",
                        "period-close-checker",
                        "procurement-policy-advisor",
                        "purchase-order-clerk",
                        "supplier-performance-analyst",
                        "transaction-anomaly-analyst");
        for (var agent : agents) {
            assertThat(agent.spec().publicInstructions())
                    .as(agent.metadata().name())
                    .isNotNull();
            var outcome = EffectiveAssembly.assemble(
                    services.registry(),
                    Viewer.VENDOR,
                    EffectiveAssembly.Request.of(
                            agent.metadata().namespace() + "/"
                                    + agent.metadata().name(),
                            null));
            assertThat(outcome.issues()).as(agent.metadata().name()).isEmpty();
        }
    }

    @Test
    @DisplayName("验收：交易异常解析 Agent + t001 扩展组装成功，扩展生效，版本解析记录在 provenance")
    void anomalyWithTenantExtension() {
        var outcome = (EffectiveAssembly.Outcome.Ok) EffectiveAssembly.assemble(
                services.registry(),
                T001,
                EffectiveAssembly.Request.of("vendor.fin/transaction-anomaly-analyst", "t001"));
        assertThat(outcome.issues()).isEmpty();
        var definition = outcome.definition();
        assertThat(definition.provenance().resolvedTarget().ranges()).containsExactly("^1.0");
        assertThat(definition.provenance().resolvedTarget().version()).isEqualTo("1.2.0");
        var notify = definition.tools().stream()
                .filter(t -> t.name().equals("notify_im"))
                .findFirst()
                .orElseThrow();
        assertThat(notify.layer().name()).isEqualTo("tenant");
        assertThat(notify.writeLevel()).isEqualTo("L2");
        assertThat(definition.hooks()).singleElement().satisfies(h -> {
            assertThat(h.event()).isEqualTo("onResult");
            assertThat(h.failurePolicy()).isEqualTo("isolate");
        });
    }

    @Test
    @DisplayName("租户视角的有效定义里找不到任何一段标品指令或平台规则原文")
    void noConfidentialLeak() {
        var outcome = (EffectiveAssembly.Outcome.Ok) EffectiveAssembly.assemble(
                services.registry(),
                T001,
                EffectiveAssembly.Request.of("vendor.fin/transaction-anomaly-analyst", "t001"));
        String serialized = outcome.view().toString();
        var secrets = outcome.definition().segments().stream()
                .filter(s -> s.confidential())
                .map(s -> s.text())
                .toList();
        assertThat(secrets).hasSizeGreaterThanOrEqualTo(7);
        for (String secret : secrets) {
            // 逐行比对，避免只因换行差异而漏检
            secret.lines()
                    .map(String::trim)
                    .filter(l -> l.length() >= 8)
                    .forEach(line -> assertThat(serialized).doesNotContain(line));
        }
    }
}

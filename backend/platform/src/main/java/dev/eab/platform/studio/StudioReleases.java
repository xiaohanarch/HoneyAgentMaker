package dev.eab.platform.studio;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.platform.registry.OverlayResolver;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Deployment;
import dev.eab.platform.studio.StudioStores.DeploymentStore;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.runtime.routing.Catalog;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Lint;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/**
 * 静态检查、发布与环境晋级（主提示词 10.8.1 节、第 12 节，ADR 0010）。发布把草稿写入注册中心并签名，部署到 dev；
 * 晋级 test 要求该版本已在 dev，晋级 prod 还要求静态检查没有错误、同一内容的评测通过。可以回滚到上一个版本。
 */
public final class StudioReleases {

    /** 场景描述与能力目录里已有场景的相似度达到它时告警，避免路由冲突 */
    static final double OVERLAP_THRESHOLD = 0.6;

    private final PlatformServices services;
    private final StudioService studio;
    private final DeploymentStore deployments;

    public StudioReleases(PlatformServices services, StudioService studio, DeploymentStore deployments) {
        this.services = services;
        this.studio = studio;
        this.deployments = deployments;
    }

    public DeploymentStore deployments() {
        return deployments;
    }

    // ── 静态检查 ──

    public record LintIssue(String rule, String severity, String message, String path) {}

    public record LintReport(boolean ok, List<LintIssue> issues) {}

    public LintReport lint(DemoUser user, Draft draft) {
        Asset agent = StudioService.parse(draft.yaml(), "草稿");
        List<Asset> overlay = new ArrayList<>(List.of(agent));
        if (draft.evalYaml() != null) overlay.add(StudioService.parse(draft.evalYaml(), "评测集"));
        return lint(user.tenantId(), agent, overlay);
    }

    LintReport lint(String tenantId, Asset agent, List<Asset> overlay) {
        var resolver = new OverlayResolver(services.registry().resolverFor(Viewer.tenant(tenantId)), overlay);
        List<LintIssue> issues = new ArrayList<>();
        for (var i : Lint.lint(agent, resolver, AssetResolver.LIVE)) {
            issues.add(new LintIssue(i.rule(), i.severity(), i.message(), i.path()));
        }
        String ref = agent.metadata().namespace() + "/" + agent.metadata().name();
        var assembled = EffectiveAssembly.assemble(
                services.registry(),
                Viewer.tenant(tenantId),
                EffectiveAssembly.Request.withOverlay(ref, tenantId, overlay));
        for (var i : assembled.issues()) {
            issues.add(new LintIssue(
                    "assembly", i.isError() ? "error" : "warning", i.code() + " " + i.message(), i.path()));
        }
        if (agent instanceof AgentAsset a) issues.addAll(routingOverlap(tenantId, a));
        boolean ok = issues.stream().noneMatch(i -> i.severity().equals("error"));
        return new LintReport(ok, issues);
    }

    /** 场景描述和示例话术与能力目录里已有条目过于相近时告警（字的二元组 Jaccard 相似度） */
    List<LintIssue> routingOverlap(String tenantId, AgentAsset agent) {
        if (agent.spec().routing() == null) return List.of();
        String self = agent.metadata().namespace() + "/" + agent.metadata().name();
        List<String> mine = new ArrayList<>();
        if (agent.spec().routing().description() != null)
            mine.add(agent.spec().routing().description());
        if (agent.spec().routing().examples() != null)
            mine.addAll(agent.spec().routing().examples());
        var published = services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        List<LintIssue> issues = new ArrayList<>();
        Set<String> reported = new HashSet<>();
        for (Catalog.Entry entry : Catalog.build(published)) {
            if (entry.ref().equals(self) || reported.contains(entry.ref())) continue;
            for (String text : mine) {
                for (String other : entry.texts()) {
                    double similarity = similarity(text, other);
                    if (similarity >= OVERLAP_THRESHOLD) {
                        reported.add(entry.ref());
                        issues.add(new LintIssue(
                                "routing-overlap",
                                "warning",
                                "场景“" + text + "”与 " + entry.displayName() + " 的“" + other + "”过于相近（相似度 "
                                        + Math.round(similarity * 100) / 100.0 + "），可能造成路由冲突",
                                "spec.routing"));
                        break;
                    }
                }
                if (reported.contains(entry.ref())) break;
            }
        }
        return issues;
    }

    static double similarity(String a, String b) {
        Set<String> x = bigrams(a);
        Set<String> y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        Set<String> common = new HashSet<>(x);
        common.retainAll(y);
        return (double) common.size() / union.size();
    }

    static Set<String> bigrams(String text) {
        String s = text.replaceAll("[\\s，。、,.!！?？“”\"]+", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) grams.add(s.substring(i, i + 2));
        return grams;
    }

    // ── 发布 ──

    public record PublishResult(String ref, String version, Deployment deployment, boolean unchanged) {}

    public PublishResult publish(DemoUser user, Draft draft, String changeNote) {
        Asset agent = StudioService.parse(draft.yaml(), "草稿");
        if (draft.evalYaml() == null) throw new StudioException(409, "NO_EVALS", "发布前需要评测集，请先生成评测集初稿");
        Asset suite = StudioService.parse(draft.evalYaml(), "评测集");
        LintReport report = lint(user.tenantId(), agent, List.of(agent, suite));
        if (!report.ok()) throw new StudioException(409, "LINT_FAILED", "静态检查有错误，不能发布", report.issues());

        String namespace = agent.metadata().namespace();
        String name = agent.metadata().name();
        String ref = namespace + "/" + name;
        var resolver = services.registry().resolverFor(Viewer.tenant(user.tenantId()));
        var versions = resolver.listVersions(namespace, name);
        String hash = StudioService.contentHash(agent);
        if (!versions.isEmpty()) {
            var latest = versions.getFirst();
            Optional<Asset> existing = resolver.get(namespace, name, latest.version());
            if (existing.isPresent()
                    && StudioService.contentHash(existing.get()).equals(hash)
                    && latest.status() == AssetResolver.Status.published) {
                Deployment dev = deployments
                        .active(user.tenantId(), "dev", namespace, name)
                        .filter(d -> d.version().equals(latest.version()))
                        .orElseGet(() -> deployments.deploy(
                                user.tenantId(), "dev", namespace, name, latest.version(), user.id(), "发布"));
                return new PublishResult(ref, latest.version(), dev, true);
            }
        }
        String version = nextVersion(
                agent.metadata().version(),
                versions.stream().map(AssetResolver.VersionInfo::version).toList());

        ObjectNode agentTree = StudioDraftComposer.read(draft.yaml());
        ((ObjectNode) agentTree.path("metadata")).put("version", version);
        ObjectNode suiteTree = StudioDraftComposer.read(draft.evalYaml());
        ((ObjectNode) suiteTree.path("metadata")).put("version", version);
        String agentYaml = StudioDraftComposer.write(agentTree);
        String suiteYaml = StudioDraftComposer.write(suiteTree);
        Viewer viewer = Viewer.tenant(user.tenantId());
        String note = changeNote == null || changeNote.isBlank() ? "Studio 发布" : changeNote;
        String suiteNs = suite.metadata().namespace();
        String suiteName = suite.metadata().name();
        // 先都写成草稿再发布：Agent 引用评测集，评测集又以 Agent 为测试对象
        put(viewer, agentYaml, note);
        boolean suiteExists = services.registry()
                .resolverFor(viewer)
                .get(suiteNs, suiteName, version)
                .isPresent();
        if (!suiteExists) put(viewer, suiteYaml, note);
        publishStatus(viewer, namespace, name, version);
        if (!suiteExists) publishStatus(viewer, suiteNs, suiteName, version);

        Deployment dev = deployments.deploy(user.tenantId(), "dev", namespace, name, version, user.id(), note);
        studio.drafts().update(draft, namespace, name, Math.max(draft.step(), 13), agentYaml, suiteYaml);
        studio.drafts().markPublished(draft, version);
        services.audit()
                .append(NewAuditEvent.of(user.id(), "studio.publish", ref + "@" + version, "allow", "发布并部署到 dev")
                        .tenantId(user.tenantId()));
        return new PublishResult(ref, version, dev, false);
    }

    /** 新版本号：草稿写的版本号比已有版本都高时用它，否则在最高版本上升一个补丁号 */
    static String nextVersion(String draftVersion, List<String> existing) {
        if (existing.isEmpty()) return draftVersion;
        String max = existing.stream().max(Versions::compare).orElseThrow();
        if (Versions.compare(draftVersion, max) > 0) return draftVersion;
        String[] parts = max.split("[.-]");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private void put(Viewer viewer, String yaml, String note) {
        var result = services.registry().put(viewer, yaml, note);
        if (!result.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "写入注册中心失败：" + result.error().message(),
                    result.error().issues());
        }
    }

    private void publishStatus(Viewer viewer, String namespace, String name, String version) {
        var result = services.registry().setStatus(viewer, namespace, name, version, AssetResolver.Status.published);
        if (!result.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "发布失败：" + result.error().message(),
                    result.error().issues());
        }
    }

    // ── 晋级与回滚 ──

    public record Gate(String name, boolean passed, String detail) {}

    public record PromoteResult(Deployment deployment, List<Gate> gates) {}

    public PromoteResult promote(DemoUser user, String ref, String version, String environment) {
        String[] parts = split(ref);
        String previous = switch (environment) {
            case "test" -> "dev";
            case "prod" -> "test";
            default -> throw new StudioException(400, "BAD_REQUEST", "只能晋级到 test 或 prod");
        };
        List<Gate> gates = new ArrayList<>();
        boolean inPrevious = deployments
                .active(user.tenantId(), previous, parts[0], parts[1])
                .filter(d -> d.version().equals(version))
                .isPresent();
        gates.add(new Gate(
                "已在 " + previous,
                inPrevious,
                inPrevious ? version + " 已部署在 " + previous : version + " 还没有部署到 " + previous));
        if (environment.equals("prod")) gates.addAll(prodGates(user.tenantId(), parts[0], parts[1], version));
        if (gates.stream().anyMatch(g -> !g.passed())) {
            services.audit()
                    .append(NewAuditEvent.of(
                                    user.id(),
                                    "studio.promote",
                                    ref + "@" + version,
                                    "deny",
                                    "晋级 " + environment + " 的门禁未通过")
                            .tenantId(user.tenantId()));
            throw new StudioException(409, "GATE_BLOCKED", "晋级 " + environment + " 的门禁未通过", gates);
        }
        Deployment deployment =
                deployments.deploy(user.tenantId(), environment, parts[0], parts[1], version, user.id(), "晋级");
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(), "studio.promote", ref + "@" + version, "allow", "晋级到 " + environment)
                        .tenantId(user.tenantId()));
        return new PromoteResult(deployment, gates);
    }

    List<Gate> prodGates(String tenantId, String namespace, String name, String version) {
        List<Gate> gates = new ArrayList<>();
        Optional<Asset> asset =
                services.registry().resolverFor(Viewer.tenant(tenantId)).get(namespace, name, version);
        if (asset.isEmpty()) {
            gates.add(new Gate("版本存在", false, namespace + "/" + name + "@" + version + " 不存在"));
            return gates;
        }
        LintReport report = lint(tenantId, asset.get(), List.of());
        long errors = report.issues().stream()
                .filter(i -> i.severity().equals("error"))
                .count();
        gates.add(new Gate("静态检查", report.ok(), report.ok() ? "没有错误" : errors + " 个错误"));
        var passed = studio.evalRuns()
                .latestPassed(tenantId, namespace + "/" + name, StudioService.contentHash(asset.get()));
        gates.add(new Gate(
                "评测门禁",
                passed.isPresent(),
                passed.map(r -> "评测 " + r.id() + " 通过（" + r.passed() + "/" + r.total() + "）")
                        .orElse("没有与该版本内容一致的评测通过记录，请先运行评测集")));
        return gates;
    }

    public Deployment rollback(DemoUser user, String ref, String environment) {
        if (!StudioStores.ENVIRONMENTS.contains(environment))
            throw new StudioException(400, "BAD_REQUEST", "未知的环境 " + environment);
        String[] parts = split(ref);
        Deployment deployment = deployments
                .rollback(user.tenantId(), environment, parts[0], parts[1], user.id())
                .orElseThrow(() -> new StudioException(409, "NOTHING_TO_ROLLBACK", environment + " 里没有可以回滚的上一个版本"));
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                "studio.rollback",
                                ref,
                                "allow",
                                environment + " 回滚到 " + deployment.version())
                        .tenantId(user.tenantId()));
        return deployment;
    }

    public Map<String, Object> deploymentsOf(DemoUser user, String ref) {
        String[] parts = split(ref);
        Map<String, Object> environments = new LinkedHashMap<>();
        for (String env : StudioStores.ENVIRONMENTS) {
            deployments.active(user.tenantId(), env, parts[0], parts[1]).ifPresent(d -> environments.put(env, d));
        }
        return Map.of(
                "environments", environments, "history", deployments.history(user.tenantId(), parts[0], parts[1]));
    }

    static String[] split(String ref) {
        int slash = ref == null ? -1 : ref.indexOf('/');
        if (slash <= 0 || slash == ref.length() - 1)
            throw new StudioException(400, "BAD_REQUEST", "ref 写作 namespace/name");
        return new String[] {ref.substring(0, slash), ref.substring(slash + 1)};
    }
}

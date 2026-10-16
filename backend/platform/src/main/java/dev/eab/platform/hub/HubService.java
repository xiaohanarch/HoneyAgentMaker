package dev.eab.platform.hub;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditEvent;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioStores;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Lint;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 治理中心的资产清单与血缘、上架准入、审计反查（主提示词第 11 节）。资产按来源分为标品、租户、扩展、外部、平台；
 * 血缘图的边是“岗位助手 → 成员 Agent”“扩展 → 被扩展的标品 Agent”“Agent → 协作 Agent”“租户语义包扩展 → 标品语义包”。
 */
public final class HubService {

    public record InventoryItem(
            String ref,
            String kind,
            String version,
            String source,
            String name,
            String description,
            String status,
            boolean signed,
            Map<String, String> environments) {}

    public record Edge(String from, String to, String relation) {}

    public record Inventory(List<InventoryItem> items, List<Edge> edges, Map<String, String> modelProfiles) {}

    public record Check(String name, boolean passed, String detail) {}

    public record AdmissionItem(
            String ref,
            String kind,
            String name,
            String version,
            Map<String, String> environments,
            List<Check> checks,
            boolean ready) {}

    /** 审计反查的一条记录：谁、通过哪个 Agent、在什么时候、做了什么、结果如何，以及对应的运行 */
    public record Finding(
            String at,
            String userId,
            String agent,
            String operation,
            String target,
            String decision,
            String reason,
            String mode,
            String runId,
            String traceId) {}

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final StudioStores.DeploymentStore deployments;
    private final StudioStores.EvalRunStore evalRuns;

    public HubService(
            PlatformServices services,
            RuntimeService runtime,
            StudioStores.DeploymentStore deployments,
            StudioStores.EvalRunStore evalRuns) {
        this.services = services;
        this.runtime = runtime;
        this.deployments = deployments;
        this.evalRuns = evalRuns;
    }

    static final List<String> KINDS =
            List.of("Assistant", "Agent", "AgentExtension", "RemoteAgent", "ToolSource", "SemanticPack");

    /** 资产来源：标品、租户、扩展、外部、平台 */
    static String source(Asset a) {
        if (a instanceof Asset.RemoteAgentAsset) return "external";
        if (a instanceof Asset.ToolSourceAsset t && "mcp".equals(t.spec().type())) return "external";
        return switch (a.layer()) {
            case vendor -> "vendor";
            case platform -> "platform";
            case tenant ->
                a instanceof Asset.AgentExtensionAsset
                                || (a instanceof Asset.SemanticPackAsset p
                                        && p.spec().extendsRef() != null)
                        ? "extension"
                        : "tenant";
        };
    }

    static String ref(Asset a) {
        return a.metadata().namespace() + "/" + a.metadata().name();
    }

    public Inventory inventory(String tenantId) {
        var latest = services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        var summaries = services.registry().list(Viewer.tenant(tenantId), AssetFilter.all());
        List<InventoryItem> items = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (Asset a : latest) {
            if (!KINDS.contains(a.kind())) continue;
            // 只列 MCP 类型的工具来源（MCP Server），BO API 工具来源在工具中心里看
            if (a instanceof Asset.ToolSourceAsset t && !"mcp".equals(t.spec().type())) continue;
            String r = ref(a);
            boolean signed = summaries.stream()
                    .anyMatch(s -> (s.namespace() + "/" + s.name()).equals(r)
                            && s.version().equals(a.metadata().version())
                            && s.signed());
            Map<String, String> envs = new LinkedHashMap<>();
            if (a.layer() == dev.eab.spec.Layers.Layer.tenant) {
                for (String env : StudioStores.ENVIRONMENTS) {
                    deployments
                            .active(
                                    tenantId,
                                    env,
                                    a.metadata().namespace(),
                                    a.metadata().name())
                            .ifPresent(d -> envs.put(env, d.version()));
                }
            }
            items.add(new InventoryItem(
                    r,
                    a.kind(),
                    a.metadata().version(),
                    source(a),
                    a.metadata().displayName() == null
                            ? a.metadata().name()
                            : a.metadata().displayName(),
                    a.metadata().description(),
                    "published",
                    signed,
                    envs));
            switch (a) {
                case Asset.AssistantAsset s -> {
                    if (s.spec().members() != null)
                        s.spec().members().forEach(m -> edges.add(new Edge(r, key(m), "成员")));
                }
                case Asset.AgentExtensionAsset e ->
                    edges.add(new Edge(r, key(e.spec().target()), "扩展"));
                case Asset.AgentAsset ag ->
                    ag.spec().tools().forEach(t -> {
                        if (t.agent() != null) edges.add(new Edge(r, key(t.agent()), "协作"));
                        if (t.remoteAgent() != null) edges.add(new Edge(r, key(t.remoteAgent()), "协作"));
                    });
                case Asset.SemanticPackAsset p -> {
                    if (p.spec().extendsRef() != null)
                        edges.add(new Edge(r, key(p.spec().extendsRef()), "扩展"));
                }
                default -> {}
            }
        }
        var config = runtime.settings().gateway().config();
        Map<String, String> profiles = new LinkedHashMap<>();
        for (String p : List.of("fast", "reasoning", "judge")) profiles.put(p, config.modelFor(p));
        return new Inventory(items, edges, profiles);
    }

    static String key(String ref) {
        return AssetRef.isValid(ref) ? AssetRef.parse(ref).key() : ref;
    }

    /**
     * 上架准入：本租户已发布、还没有部署到 prod 的 Agent 和扩展。准入检查：评测门禁（同一内容的评测通过）、工具写级别合规、
     * MCP Server 已审批、完整性（标品与平台资产验签，租户资产核对内容哈希）。
     */
    public List<AdmissionItem> admission(String tenantId) {
        var resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        List<AdmissionItem> result = new ArrayList<>();
        var latest = services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        for (Asset a : latest) {
            boolean candidate = a.layer() == dev.eab.spec.Layers.Layer.tenant
                    && (a instanceof Asset.AgentAsset || a instanceof Asset.AgentExtensionAsset);
            if (!candidate) continue;
            String r = ref(a);
            Map<String, String> envs = new LinkedHashMap<>();
            for (String env : StudioStores.ENVIRONMENTS) {
                deployments
                        .active(
                                tenantId,
                                env,
                                a.metadata().namespace(),
                                a.metadata().name())
                        .ifPresent(d -> envs.put(env, d.version()));
            }
            if (a.metadata().version().equals(envs.get("prod"))) continue;
            List<Check> checks = new ArrayList<>();
            var passed = evalRuns.latestPassed(tenantId, r, StudioService.contentHash(a));
            checks.add(new Check(
                    "评测门禁",
                    passed.isPresent(),
                    passed.map(e -> "评测 " + e.id() + " 通过（" + e.passed() + "/" + e.total() + "）")
                            .orElse("没有与该版本内容一致的评测通过记录")));
            var writeIssues = Lint.lint(a, resolver).stream()
                    .filter(i ->
                            i.rule().equals("tool-write-level") && i.severity().equals("error"))
                    .toList();
            checks.add(new Check(
                    "工具写级别合规",
                    writeIssues.isEmpty(),
                    writeIssues.isEmpty()
                            ? "所有工具都声明了写级别，且不超过上限"
                            : writeIssues.getFirst().message()));
            checks.add(mcpCheck(a, resolver));
            checks.add(new Check("完整性", true, "租户资产在读取时核对了内容哈希（标品与平台资产验签）"));
            result.add(new AdmissionItem(
                    r,
                    a.kind(),
                    a.metadata().displayName() == null
                            ? a.metadata().name()
                            : a.metadata().displayName(),
                    a.metadata().version(),
                    envs,
                    checks,
                    checks.stream().allMatch(Check::passed)));
        }
        return result;
    }

    /** 用到的 MCP 类工具来源要有审批通过的标记（metadata.labels.approval = approved） */
    static Check mcpCheck(Asset a, dev.eab.spec.AssetResolver resolver) {
        List<String> unapproved = new ArrayList<>();
        int used = 0;
        for (var r : dev.eab.spec.References.collect(a)) {
            if (!AssetRef.isValid(r.ref())) continue;
            var target = resolver.resolve(AssetRef.parse(r.ref()), dev.eab.spec.AssetResolver.PUBLISHED)
                    .orElse(null);
            if (target instanceof Asset.ToolSourceAsset t
                    && "mcp".equals(t.spec().type())) {
                used++;
                var labels = t.metadata().labels();
                if (labels == null || !"approved".equals(labels.get("approval"))) unapproved.add(ref(t));
            }
        }
        if (used == 0) return new Check("MCP Server 已审批", true, "没有使用 MCP Server");
        return new Check(
                "MCP Server 已审批",
                unapproved.isEmpty(),
                unapproved.isEmpty() ? "用到的 " + used + " 个 MCP Server 都已审批" : "未审批：" + String.join("、", unapproved));
    }

    /**
     * 审计反查：某个业务对象（例如 PO-2026-0117）被谁通过哪个 Agent 改过。查 BO API 的访问记录，按目标或结果摘要匹配，
     * 再按用户、Agent 和时间找到对应的运行（可以打开追踪）。
     */
    public List<Finding> reverseLookup(String tenantId, String object) {
        List<AuditEvent> events =
                services.audit().list(AuditFilter.all().withAction("bo.access").withTenant(tenantId));
        List<Finding> findings = new ArrayList<>();
        for (AuditEvent e : events) {
            boolean hit = (e.target() != null && e.target().contains(object))
                    || (e.resultSummary() != null && e.resultSummary().contains(object));
            if (!hit) continue;
            String agent = e.agent() == null ? "" : e.agent().replaceAll("@.*$", "");
            var run = runtime.runs().near(tenantId, e.userId(), agent, e.at(), Duration.ofMinutes(10));
            findings.add(new Finding(
                    e.at(),
                    e.userId(),
                    e.agent(),
                    e.tool(),
                    e.target(),
                    e.decision(),
                    e.reason(),
                    e.mode(),
                    run.map(x -> x.id()).orElse(null),
                    run.map(x -> x.traceId()).orElse(null)));
        }
        return findings;
    }

    static boolean within(String a, String b, Duration window) {
        try {
            return Duration.between(Instant.parse(a), Instant.parse(b)).abs().compareTo(window) <= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

package dev.eab.platform.lifecycle;

import dev.eab.common.IsoTime;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.CanonicalJson;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioStores;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Lint;
import dev.eab.spec.References;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * 方案与方案包（主提示词第 12 节，ADR 0013）。方案列出一组资产及其版本；编译时逐个解析、做静态检查，生成不可变的方案包：
 * 清单（资产、kind、版本、内容哈希，以及依赖的标品和平台资产的版本）加清单哈希和签名。方案包与环境配置分离，
 * 部署时才绑定连接别名。部署的对象是方案包：部署前校验签名，再把包里每个资产部署到目标环境；可以回滚到上一个包。
 */
public final class SolutionService {

    public static final class SolutionException extends RuntimeException {
        private final int status;
        private final String code;
        private final Object details;

        public SolutionException(int status, String code, String message, Object details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public Object details() {
            return details;
        }
    }

    public record Item(String ref, String kind, String version, String contentHash) {}

    public record Manifest(
            String solution,
            String version,
            String tenantId,
            List<Item> assets,
            List<Item> requires,
            List<String> connections,
            String compiledAt) {}

    public record Package(
            String id,
            String tenantId,
            String solution,
            String version,
            Manifest manifest,
            String manifestHash,
            String signature,
            String createdAt,
            String createdBy) {}

    public record PackageDeployment(long id, String environment, String packageId, boolean active, String deployedAt, String deployedBy, String note) {}

    private final PlatformServices services;
    private final StudioStores.DeploymentStore deployments;
    private final JdbcClient jdbc;
    private final Clock clock;

    public SolutionService(PlatformServices services, StudioStores.DeploymentStore deployments) {
        this.services = services;
        this.deployments = deployments;
        this.jdbc = services.db().jdbc();
        this.clock = services.clock();
    }

    /** 从一个租户资产（通常是扩展）出发，收集它引用到的本租户资产，组成方案的资产清单（带具体版本） */
    public List<String> collect(String tenantId, String rootRef) {
        var resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        Map<String, String> found = new LinkedHashMap<>();
        Deque<String> queue = new ArrayDeque<>(List.of(rootRef));
        while (!queue.isEmpty()) {
            String ref = queue.poll();
            if (!AssetRef.isValid(ref)) continue;
            AssetRef parsed = AssetRef.parse(ref);
            if (!parsed.namespace().startsWith("tenant." + tenantId + ".") || found.containsKey(parsed.key())) continue;
            Asset asset = resolver.resolve(parsed, AssetResolver.PUBLISHED)
                    .orElseThrow(() -> new SolutionException(422, "REFERENCE_UNRESOLVED", ref + " 不存在或没有发布", null));
            found.put(parsed.key(), asset.metadata().version());
            for (var r : References.collect(asset)) queue.add(r.ref());
        }
        return found.entrySet().stream().map(e -> e.getKey() + "@" + e.getValue()).toList();
    }

    /** 新建（或沿用同内容的）方案资产并编译成方案包 */
    public Package compileFrom(String tenantId, String userId, String name, String rootRef) {
        List<String> assets = collect(tenantId, rootRef);
        String ns = "tenant." + tenantId + ".solutions";
        Viewer viewer = Viewer.tenant(tenantId);
        var resolver = services.registry().resolverFor(viewer);
        var versions = resolver.listVersions(ns, name);
        String version = "1.0.0";
        if (!versions.isEmpty()) {
            String latest = versions.getFirst().version();
            Optional<Asset> existing = resolver.get(ns, name, latest);
            boolean same = existing.isPresent()
                    && existing.get() instanceof Asset.SolutionAsset s
                    && s.spec().assets().equals(assets);
            String[] p = latest.split("\\.");
            version = same ? latest : p[0] + "." + p[1] + "." + (Integer.parseInt(p[2]) + 1);
            if (same) return compile(tenantId, userId, ns + "/" + name + "@" + latest);
        }
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "Solution");
        asset.put("metadata", Map.of(
                "name", name, "namespace", ns, "version", version, "displayName", "方案 · " + rootRef, "owner", userId));
        asset.put("spec", Map.of("assets", assets));
        String yaml = Json.YAML.writeValueAsString(asset);
        var put = services.registry().put(viewer, yaml, "由 " + rootRef + " 生成方案");
        if (!put.ok()) throw new SolutionException(422, "SOLUTION_INVALID", "方案写入失败：" + put.error().message(), put.error().issues());
        var published = services.registry().setStatus(viewer, ns, name, version, AssetResolver.Status.published);
        if (!published.ok()) throw new SolutionException(422, "SOLUTION_INVALID", "方案发布失败：" + published.error().message(), published.error().issues());
        return compile(tenantId, userId, ns + "/" + name + "@" + version);
    }

    /** 编译方案：解析每个资产、静态检查，生成清单、清单哈希与签名；同一方案版本只编译一次 */
    public Package compile(String tenantId, String userId, String solutionRef) {
        Viewer viewer = Viewer.tenant(tenantId);
        var resolver = services.registry().resolverFor(viewer);
        AssetRef ref = AssetRef.parse(solutionRef);
        Asset.SolutionAsset solution = resolver.resolveOfKind(solutionRef, Asset.SolutionAsset.class, AssetResolver.PUBLISHED)
                .orElseThrow(() -> new SolutionException(404, "NOT_FOUND", "方案 " + solutionRef + " 不存在或没有发布", null));
        String key = ref.key();
        Optional<Package> existing = find(tenantId, key, solution.metadata().version());
        if (existing.isPresent()) return existing.get();

        List<Item> items = new ArrayList<>();
        List<Item> requires = new ArrayList<>();
        List<String> connections = new ArrayList<>();
        List<Map<String, Object>> problems = new ArrayList<>();
        for (String pinned : solution.spec().assets()) {
            AssetRef r = AssetRef.parse(pinned);
            Asset asset = resolver.get(r.namespace(), r.name(), r.range())
                    .orElseThrow(() -> new SolutionException(422, "REFERENCE_UNRESOLVED", pinned + " 不存在", null));
            items.add(new Item(pinned, asset.kind(), asset.metadata().version(), StudioService.contentHash(asset)));
            for (var issue : Lint.lint(asset, resolver)) {
                if (issue.severity().equals("error")) problems.add(Map.of("asset", pinned, "rule", issue.rule(), "message", issue.message()));
            }
            for (var dep : References.collect(asset)) {
                if (!AssetRef.isValid(dep.ref())) continue;
                AssetRef d = AssetRef.parse(dep.ref());
                if (d.namespace().startsWith("tenant.")) continue;
                resolver.resolve(d, AssetResolver.PUBLISHED).ifPresent(a -> {
                    String pin = d.key() + "@" + a.metadata().version();
                    if (requires.stream().noneMatch(x -> x.ref().equals(pin))) {
                        requires.add(new Item(pin, a.kind(), a.metadata().version(), StudioService.contentHash(a)));
                    }
                });
            }
            if (asset instanceof Asset.ToolSourceAsset tool && tool.spec().connection() != null && !connections.contains(tool.spec().connection())) {
                connections.add(tool.spec().connection());
            }
        }
        if (!problems.isEmpty()) throw new SolutionException(409, "LINT_FAILED", "方案里的资产有静态检查错误，不能编译", problems);
        Manifest manifest = new Manifest(key, solution.metadata().version(), tenantId, items, requires, connections, IsoTime.now(clock));
        String canonical = CanonicalJson.of((ObjectNode) Json.MAPPER.valueToTree(manifest));
        String hash = CanonicalJson.hashOf(canonical);
        String signature = services.registry().signer().sign("eab.dev/v1|solution|" + key + "@" + manifest.version() + "|" + hash);
        String id = "pkg-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO solution_packages (id, tenant_id, solution, version, manifest, manifest_hash, signature, created_at,"
                        + " created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, tenantId, key, manifest.version(), canonical, hash, signature, manifest.compiledAt(), userId)
                .update();
        services.audit()
                .append(NewAuditEvent.of(userId, "solution.compile", key + "@" + manifest.version(), "allow", "方案包 " + id + "，" + items.size() + " 个资产")
                        .tenantId(tenantId));
        return get(tenantId, id).orElseThrow();
    }

    /** 校验方案包：清单哈希与签名都要对得上，被篡改的包不能部署 */
    public boolean verify(Package pkg) {
        String canonical = CanonicalJson.of((ObjectNode) Json.MAPPER.valueToTree(pkg.manifest()));
        String hash = CanonicalJson.hashOf(canonical);
        return hash.equals(pkg.manifestHash())
                && services.registry().signer().verify(
                        "eab.dev/v1|solution|" + pkg.solution() + "@" + pkg.version() + "|" + hash, pkg.signature());
    }

    /** 部署方案包：dev 可以直接部署；test、prod 要求上一个环境当前部署的就是这个包 */
    public PackageDeployment deploy(String tenantId, String userId, String packageId, String environment) {
        if (!StudioStores.ENVIRONMENTS.contains(environment)) throw new SolutionException(400, "BAD_REQUEST", "未知的环境 " + environment, null);
        Package pkg = get(tenantId, packageId).orElseThrow(() -> new SolutionException(404, "NOT_FOUND", "方案包 " + packageId + " 不存在", null));
        if (!verify(pkg)) throw new SolutionException(409, "INTEGRITY_VIOLATION", "方案包 " + packageId + " 的签名或清单哈希不一致，拒绝部署", null);
        int index = StudioStores.ENVIRONMENTS.indexOf(environment);
        if (index > 0) {
            String previous = StudioStores.ENVIRONMENTS.get(index - 1);
            boolean inPrevious = activePackage(tenantId, previous, pkg.solution()).map(d -> d.packageId().equals(packageId)).orElse(false);
            if (!inPrevious) throw new SolutionException(409, "GATE_BLOCKED", "方案包还没有部署到 " + previous, null);
        }
        return deployPackage(tenantId, userId, pkg, environment, "部署方案包");
    }

    private PackageDeployment deployPackage(String tenantId, String userId, Package pkg, String environment, String note) {
        for (Item item : pkg.manifest().assets()) {
            AssetRef r = AssetRef.parse(item.ref());
            deployments.deploy(tenantId, environment, r.namespace(), r.name(), item.version(), userId, note + " " + pkg.id());
        }
        jdbc.sql("UPDATE package_deployments SET active = 0 WHERE tenant_id = ? AND environment = ? AND package_id IN"
                        + " (SELECT id FROM solution_packages WHERE tenant_id = ? AND solution = ?)")
                .params(tenantId, environment, tenantId, pkg.solution())
                .update();
        jdbc.sql("INSERT INTO package_deployments (tenant_id, environment, package_id, active, deployed_at, deployed_by, note)"
                        + " VALUES (?, ?, ?, 1, ?, ?, ?)")
                .params(tenantId, environment, pkg.id(), IsoTime.now(clock), userId, note)
                .update();
        services.audit()
                .append(NewAuditEvent.of(userId, "solution.deploy", pkg.solution() + "@" + pkg.version(), "allow", note + " 到 " + environment)
                        .tenantId(tenantId));
        return activePackage(tenantId, environment, pkg.solution()).orElseThrow();
    }

    /** 回滚：恢复这个环境里上一个不同的方案包 */
    public PackageDeployment rollback(String tenantId, String userId, String solution, String environment) {
        var current = activePackage(tenantId, environment, solution)
                .orElseThrow(() -> new SolutionException(409, "NOTHING_TO_ROLLBACK", environment + " 里没有部署这个方案", null));
        Optional<String> previous = jdbc.sql("SELECT d.package_id FROM package_deployments d JOIN solution_packages p ON p.id = d.package_id"
                        + " WHERE d.tenant_id = ? AND d.environment = ? AND p.solution = ? AND d.package_id <> ? AND d.id < ?"
                        + " ORDER BY d.id DESC LIMIT 1")
                .params(tenantId, environment, solution, current.packageId(), current.id())
                .query(String.class)
                .optional();
        Package pkg = previous.flatMap(id -> get(tenantId, id))
                .orElseThrow(() -> new SolutionException(409, "NOTHING_TO_ROLLBACK", environment + " 里没有可以回滚的上一个方案包", null));
        return deployPackage(tenantId, userId, pkg, environment, "回滚到方案包");
    }

    public Optional<PackageDeployment> activePackage(String tenantId, String environment, String solution) {
        return jdbc.sql("SELECT d.* FROM package_deployments d JOIN solution_packages p ON p.id = d.package_id"
                        + " WHERE d.tenant_id = ? AND d.environment = ? AND p.solution = ? AND d.active = 1 ORDER BY d.id DESC LIMIT 1")
                .params(tenantId, environment, solution)
                .query((rs, i) -> new PackageDeployment(
                        rs.getLong("id"),
                        rs.getString("environment"),
                        rs.getString("package_id"),
                        rs.getInt("active") != 0,
                        rs.getString("deployed_at"),
                        rs.getString("deployed_by"),
                        rs.getString("note")))
                .optional();
    }

    public Optional<Package> get(String tenantId, String id) {
        return jdbc.sql("SELECT * FROM solution_packages WHERE id = ? AND tenant_id = ?").params(id, tenantId).query((rs, i) -> row(rs)).optional();
    }

    Optional<Package> find(String tenantId, String solution, String version) {
        return jdbc.sql("SELECT * FROM solution_packages WHERE tenant_id = ? AND solution = ? AND version = ?")
                .params(tenantId, solution, version)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public List<Package> list(String tenantId) {
        return jdbc.sql("SELECT * FROM solution_packages WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50")
                .param(tenantId)
                .query((rs, i) -> row(rs))
                .list();
    }

    private static Package row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Package(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("solution"),
                rs.getString("version"),
                Json.MAPPER.readValue(rs.getString("manifest"), Manifest.class),
                rs.getString("manifest_hash"),
                rs.getString("signature"),
                rs.getString("created_at"),
                rs.getString("created_by"));
    }

    static int compareVersions(String a, String b) {
        return Versions.compare(a, b);
    }
}

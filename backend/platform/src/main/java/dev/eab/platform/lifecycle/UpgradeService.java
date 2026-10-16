package dev.eab.platform.lifecycle;

import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.eval.Assertions.CaseResult;
import dev.eab.eval.SuiteRunner.SuiteResult;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.assist.NotificationStore;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Json;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 标品升级模拟与自动回归（主提示词第 12 节、故事 F）：先对依赖该标品 Agent 的每个租户扩展运行一次扩展评测集作为基线，
 * 再以厂商身份签名发布新版本，重新组装（记下组装问题，例如租户钩子条件因字段改名失效）并运行同一批评测集，
 * 基线通过、升级后失败的用例就是回归。结果记入 upgrade_regressions，通知租户的扩展负责人和租户管理员。
 */
public final class UpgradeService {

    /** 演示控制台“发布标品新版本”的预置升级 */
    public record Preset(String agent, String fromVersion, String toVersion, String resource, String summary) {}

    public static final List<Preset> PRESETS = List.of(new Preset(
            "vendor.fin/transaction-anomaly-analyst",
            "1.2.0",
            "1.3.0",
            "upgrades/transaction-anomaly-analyst-1.3.0.yaml",
            "修改指令、新增 ap_invoice_list 工具；输出结构里的 severity 改名为 riskLevel"));

    /**
     * 一个租户扩展的回归结果。
     *
     * @param regressions 基线通过、升级后失败的用例
     * @param issues 升级后组装出现的问题（警告也列出）
     */
    public record Regression(
            String id,
            String at,
            String agent,
            String fromVersion,
            String toVersion,
            String tenantId,
            String extension,
            String suite,
            String status,
            Map<String, Object> baseline,
            Map<String, Object> after,
            List<String> regressions,
            List<String> issues,
            String triggeredBy) {}

    public static final class UpgradeException extends RuntimeException {
        public UpgradeException(String message) {
            super(message);
        }
    }

    private final PlatformServices services;
    private final EvalService evals;
    private final NotificationStore notifications;
    private final JdbcClient jdbc;
    private final Clock clock;

    public UpgradeService(PlatformServices services, EvalService evals, NotificationStore notifications) {
        this.services = services;
        this.evals = evals;
        this.notifications = notifications;
        this.jdbc = services.db().jdbc();
        this.clock = services.clock();
    }

    static String readResource(String path) {
        try (InputStream in = UpgradeService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new UpgradeException("找不到升级资源 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UpgradeException("读取升级资源 " + path + " 失败：" + e.getMessage());
        }
    }

    /** 当前最新的已发布版本 */
    Optional<String> latestVendorVersion(String agent) {
        var ref = AssetRef.parse(agent);
        return services.registry()
                .resolverFor(Viewer.VENDOR)
                .resolveVersion(ref, dev.eab.spec.AssetResolver.PUBLISHED);
    }

    /** 依赖该标品 Agent 的租户扩展，以及它们的评测集 */
    record Dependent(String tenantId, AgentExtensionAsset extension, List<EvalSuiteAsset> suites) {}

    List<Dependent> dependents(String agent, String toVersion) {
        List<Dependent> result = new ArrayList<>();
        for (var tenant : DemoDirectory.TENANTS) {
            Viewer viewer = Viewer.tenant(tenant.id());
            var extensions = services.registry()
                    .latestPublished(new AssetFilter("AgentExtension", null, "tenant", tenant.id(), null), viewer)
                    .assets();
            var resolver = services.registry().resolverFor(viewer);
            for (Asset a : extensions) {
                if (!(a instanceof AgentExtensionAsset ext)) continue;
                AssetRef target = AssetRef.parse(ext.spec().target());
                if (!target.key().equals(agent)) continue;
                if (target.range() != null && !Versions.satisfies(toVersion, target.range())) continue;
                List<EvalSuiteAsset> suites = new ArrayList<>();
                if (ext.spec().evals() != null) {
                    for (String suiteRef : ext.spec().evals().suites()) {
                        resolver.resolveOfKind(suiteRef, EvalSuiteAsset.class, dev.eab.spec.AssetResolver.PUBLISHED)
                                .ifPresent(suites::add);
                    }
                }
                result.add(new Dependent(tenant.id(), ext, suites));
            }
        }
        return result;
    }

    /** 模拟一次标品升级，返回各租户扩展的回归结果 */
    public List<Regression> simulate(Preset preset, String triggeredBy) {
        String current = latestVendorVersion(preset.agent()).orElseThrow(() -> new UpgradeException(preset.agent() + " 没有已发布的版本"));
        if (Versions.compare(current, preset.toVersion()) >= 0) {
            throw new UpgradeException(preset.agent() + " 已经是 " + current + "，不能再升到 " + preset.toVersion() + "；需要重新演示时先重置演示数据");
        }
        List<Dependent> dependents = dependents(preset.agent(), preset.toVersion());
        Map<String, SuiteResult> baseline = new LinkedHashMap<>();
        for (Dependent d : dependents) {
            for (EvalSuiteAsset suite : d.suites()) baseline.put(key(d, suite), evals.run(d.tenantId(), suite, triggeredBy).result());
        }

        String yaml = readResource(preset.resource());
        var parsed = AssetParser.parseYaml(yaml);
        if (!parsed.ok()) throw new UpgradeException("升级资源不合法：" + parsed.issues());
        var published = services.registry().publishAsset(Viewer.VENDOR, parsed.asset(), yaml);
        if (!published.ok()) throw new UpgradeException("发布 " + preset.toVersion() + " 失败：" + published.error().message());
        services.audit()
                .append(NewAuditEvent.of("vendor", "upgrade.publish", preset.agent() + "@" + preset.toVersion(), "allow", preset.summary()));

        List<Regression> results = new ArrayList<>();
        for (Dependent d : dependents) {
            var assembled = EffectiveAssembly.assemble(
                    services.registry(), Viewer.tenant(d.tenantId()), EffectiveAssembly.Request.of(preset.agent(), d.tenantId()));
            List<String> issues = assembled.issues().stream()
                    .map(i -> (i.isError() ? "错误 " : "警告 ") + i.code() + "：" + i.message())
                    .toList();
            for (EvalSuiteAsset suite : d.suites()) {
                SuiteResult before = baseline.get(key(d, suite));
                SuiteResult after = evals.run(d.tenantId(), suite, triggeredBy).result();
                Set<String> passedBefore = before.cases().stream().filter(CaseResult::passed).map(CaseResult::id).collect(Collectors.toSet());
                List<String> regressions = after.cases().stream()
                        .filter(c -> !c.passed() && passedBefore.contains(c.id()))
                        .map(CaseResult::id)
                        .toList();
                String status = regressions.isEmpty() ? "passed" : "failed";
                results.add(record(preset, current, d, suite, status, before, after, regressions, issues, triggeredBy));
            }
            if (d.suites().isEmpty()) {
                results.add(record(preset, current, d, null, issues.stream().anyMatch(i -> i.startsWith("错误")) ? "failed" : "passed",
                        null, null, List.of(), issues, triggeredBy));
            }
        }
        notifyTenants(preset, results);
        return results;
    }

    private static String key(Dependent d, EvalSuiteAsset suite) {
        return d.tenantId() + "|" + d.extension().metadata().name() + "|" + suite.metadata().name();
    }

    static Map<String, Object> summary(SuiteResult r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        m.put("passed", r.passed());
        m.put("total", r.total());
        m.put("cases", r.cases().stream()
                .map(c -> Map.of(
                        "id", c.id(),
                        "passed", c.passed(),
                        "failures", c.assertions().stream().filter(a -> !a.passed()).map(a -> a.type() + "：" + a.detail()).toList()))
                .toList());
        return m;
    }

    private Regression record(
            Preset preset,
            String fromVersion,
            Dependent d,
            EvalSuiteAsset suite,
            String status,
            SuiteResult before,
            SuiteResult after,
            List<String> regressions,
            List<String> issues,
            String triggeredBy) {
        String id = "upg-" + UUID.randomUUID().toString().substring(0, 8);
        String at = IsoTime.now(clock);
        String extension = d.extension().metadata().namespace() + "/" + d.extension().metadata().name() + "@" + d.extension().metadata().version();
        String suiteRef = suite == null ? "" : suite.metadata().namespace() + "/" + suite.metadata().name();
        jdbc.sql("INSERT INTO upgrade_regressions (id, at, agent, from_version, to_version, tenant_id, extension, suite, status,"
                        + " baseline, after, regressions, issues, triggered_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        id, at, preset.agent(), fromVersion, preset.toVersion(), d.tenantId(), extension, suiteRef, status,
                        Json.MAPPER.writeValueAsString(summary(before)),
                        Json.MAPPER.writeValueAsString(summary(after)),
                        Json.MAPPER.writeValueAsString(regressions),
                        Json.MAPPER.writeValueAsString(issues),
                        triggeredBy)
                .update();
        return new Regression(id, at, preset.agent(), fromVersion, preset.toVersion(), d.tenantId(), extension, suiteRef, status,
                summary(before), summary(after), regressions, issues, triggeredBy);
    }

    /** 回归失败时通知租户：扩展的负责人和租户管理员 */
    void notifyTenants(Preset preset, List<Regression> results) {
        for (Regression r : results) {
            if (!r.status().equals("failed")) continue;
            String title = "标品升级回归未通过：" + preset.agent() + " " + r.fromVersion() + " → " + r.toVersion();
            String body = "扩展 " + r.extension() + " 的评测集 " + r.suite() + " 有 " + r.regressions().size() + " 个用例在升级后失败："
                    + String.join("、", r.regressions())
                    + (r.issues().isEmpty() ? "" : "。组装问题：" + String.join("；", r.issues()));
            List<String> recipients = new ArrayList<>();
            DemoDirectory.USERS.stream()
                    .filter(u -> u.tenantId().equals(r.tenantId()) && u.roles().contains("tenant-admin"))
                    .forEach(u -> recipients.add(u.id()));
            String owner = owner(r.tenantId(), r.extension());
            if (owner != null && !recipients.contains(owner)) recipients.add(owner);
            for (String user : recipients) notifications.add(r.tenantId(), user, title, body, "upgrade:" + r.id(), null, null);
        }
    }

    private String owner(String tenantId, String extension) {
        var ref = AssetRef.parse(extension);
        return services.registry()
                .resolverFor(Viewer.tenant(tenantId))
                .get(ref.namespace(), ref.name(), ref.range())
                .map(a -> a.metadata().owner())
                .orElse(null);
    }

    public List<Regression> list(String tenantId) {
        String sql = "SELECT * FROM upgrade_regressions" + (tenantId == null ? "" : " WHERE tenant_id = ?") + " ORDER BY at DESC LIMIT 50";
        var spec = jdbc.sql(sql);
        if (tenantId != null) spec = spec.param(tenantId);
        return spec.query((rs, i) -> new Regression(
                        rs.getString("id"),
                        rs.getString("at"),
                        rs.getString("agent"),
                        rs.getString("from_version"),
                        rs.getString("to_version"),
                        rs.getString("tenant_id"),
                        rs.getString("extension"),
                        rs.getString("suite"),
                        rs.getString("status"),
                        readMap(rs.getString("baseline")),
                        readMap(rs.getString("after")),
                        readList(rs.getString("regressions")),
                        readList(rs.getString("issues")),
                        rs.getString("triggered_by")))
                .list();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readMap(String json) {
        return json == null || json.equals("null") ? null : Json.MAPPER.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    static List<String> readList(String json) {
        return json == null ? List.of() : Json.MAPPER.readValue(json, List.class);
    }
}

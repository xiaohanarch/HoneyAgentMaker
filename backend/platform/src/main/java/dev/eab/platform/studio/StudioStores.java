package dev.eab.platform.studio;

import dev.eab.common.IsoTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Studio 的三张表：工作区草稿、环境部署、评测运行（ADR 0010） */
public final class StudioStores {

    private StudioStores() {}

    /** 环境按晋级顺序排列 */
    public static final List<String> ENVIRONMENTS = List.of("dev", "test", "prod");

    // ── 工作区草稿 ──

    /**
     * @param yaml 资产草稿，内容可改
     * @param evalYaml 评测集草稿，没有时为 null
     * @param publishedVersion 最近一次发布到注册中心的版本，没发布过为 null
     */
    public record Draft(
            String id,
            String tenantId,
            String owner,
            String kind,
            String namespace,
            String name,
            String intent,
            int step,
            String yaml,
            String evalYaml,
            String publishedVersion,
            String createdAt,
            String updatedAt) {

        public String ref() {
            return namespace + "/" + name;
        }
    }

    public static final class DraftStore {
        private final JdbcClient jdbc;
        private final Clock clock;

        public DraftStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        public Draft create(
                String tenantId, String owner, String kind, String namespace, String name, String intent, String yaml) {
            String id = "drf-" + UUID.randomUUID().toString().substring(0, 8);
            String now = IsoTime.now(clock);
            jdbc.sql("INSERT INTO studio_drafts (id, tenant_id, owner, kind, namespace, name, intent, step, yaml,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)")
                    .params(id, tenantId, owner, kind, namespace, name, intent, yaml, now, now)
                    .update();
            return get(tenantId, id).orElseThrow();
        }

        /** 只能读到本租户的草稿 */
        public Optional<Draft> get(String tenantId, String id) {
            return jdbc.sql("SELECT * FROM studio_drafts WHERE id = ? AND tenant_id = ?")
                    .params(id, tenantId)
                    .query((rs, i) -> new Draft(
                            rs.getString("id"),
                            rs.getString("tenant_id"),
                            rs.getString("owner"),
                            rs.getString("kind"),
                            rs.getString("namespace"),
                            rs.getString("name"),
                            rs.getString("intent"),
                            rs.getInt("step"),
                            rs.getString("yaml"),
                            rs.getString("eval_yaml"),
                            rs.getString("published_version"),
                            rs.getString("created_at"),
                            rs.getString("updated_at")))
                    .optional();
        }

        public List<Draft> listByTenant(String tenantId, int limit) {
            return jdbc.sql("SELECT id FROM studio_drafts WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ?")
                    .params(tenantId, limit)
                    .query(String.class)
                    .list()
                    .stream()
                    .map(id -> get(tenantId, id).orElseThrow())
                    .toList();
        }

        public Draft update(Draft draft, String namespace, String name, int step, String yaml, String evalYaml) {
            jdbc.sql("UPDATE studio_drafts SET namespace = ?, name = ?, step = ?, yaml = ?, eval_yaml = ?, updated_at = ?"
                            + " WHERE id = ? AND tenant_id = ?")
                    .params(namespace, name, step, yaml, evalYaml, IsoTime.now(clock), draft.id(), draft.tenantId())
                    .update();
            return get(draft.tenantId(), draft.id()).orElseThrow();
        }

        public void markPublished(Draft draft, String version) {
            jdbc.sql("UPDATE studio_drafts SET published_version = ?, updated_at = ? WHERE id = ? AND tenant_id = ?")
                    .params(version, IsoTime.now(clock), draft.id(), draft.tenantId())
                    .update();
        }

        public boolean delete(String tenantId, String id) {
            return jdbc.sql("DELETE FROM studio_drafts WHERE id = ? AND tenant_id = ?")
                            .params(id, tenantId)
                            .update()
                    > 0;
        }
    }

    // ── 环境部署 ──

    public record Deployment(
            long id,
            String tenantId,
            String environment,
            String namespace,
            String name,
            String version,
            boolean active,
            String deployedAt,
            String deployedBy,
            String note) {

        public String ref() {
            return namespace + "/" + name;
        }
    }

    public static final class DeploymentStore {
        private final JdbcClient jdbc;
        private final Clock clock;

        public DeploymentStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        private static Deployment row(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new Deployment(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getString("environment"),
                    rs.getString("namespace"),
                    rs.getString("name"),
                    rs.getString("version"),
                    rs.getInt("active") != 0,
                    rs.getString("deployed_at"),
                    rs.getString("deployed_by"),
                    rs.getString("note"));
        }

        /** 某个环境里当前生效的版本 */
        public Optional<Deployment> active(String tenantId, String environment, String namespace, String name) {
            return jdbc.sql("SELECT * FROM deployments WHERE tenant_id = ? AND environment = ? AND namespace = ?"
                            + " AND name = ? AND active = 1 ORDER BY id DESC LIMIT 1")
                    .params(tenantId, environment, namespace, name)
                    .query((rs, i) -> row(rs))
                    .optional();
        }

        /** 本租户某个环境里全部生效的部署 */
        public List<Deployment> activeIn(String tenantId, String environment) {
            return jdbc.sql("SELECT * FROM deployments WHERE tenant_id = ? AND environment = ? AND active = 1"
                            + " ORDER BY namespace, name")
                    .params(tenantId, environment)
                    .query((rs, i) -> row(rs))
                    .list();
        }

        public List<Deployment> history(String tenantId, String namespace, String name) {
            return jdbc.sql("SELECT * FROM deployments WHERE tenant_id = ? AND namespace = ? AND name = ?"
                            + " ORDER BY id DESC LIMIT 50")
                    .params(tenantId, namespace, name)
                    .query((rs, i) -> row(rs))
                    .list();
        }

        /** 部署一个版本：同环境原来生效的记录失效 */
        public Deployment deploy(
                String tenantId, String environment, String namespace, String name, String version, String by, String note) {
            jdbc.sql("UPDATE deployments SET active = 0 WHERE tenant_id = ? AND environment = ? AND namespace = ?"
                            + " AND name = ?")
                    .params(tenantId, environment, namespace, name)
                    .update();
            jdbc.sql("INSERT INTO deployments (tenant_id, environment, namespace, name, version, active, deployed_at,"
                            + " deployed_by, note) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?)")
                    .params(tenantId, environment, namespace, name, version, IsoTime.now(clock), by, note)
                    .update();
            return active(tenantId, environment, namespace, name).orElseThrow();
        }

        /** 回滚到上一个不同的版本；没有可回滚的版本时返回空 */
        public Optional<Deployment> rollback(String tenantId, String environment, String namespace, String name, String by) {
            Optional<Deployment> current = active(tenantId, environment, namespace, name);
            if (current.isEmpty()) return Optional.empty();
            Optional<String> previous = jdbc.sql("SELECT version FROM deployments WHERE tenant_id = ? AND environment = ?"
                            + " AND namespace = ? AND name = ? AND version <> ? AND id < ? ORDER BY id DESC LIMIT 1")
                    .params(tenantId, environment, namespace, name, current.get().version(), current.get().id())
                    .query(String.class)
                    .optional();
            return previous.map(version ->
                    deploy(tenantId, environment, namespace, name, version, by, "回滚自 " + current.get().version()));
        }
    }

    // ── 评测运行 ──

    /**
     * @param targetHash 被测资产（草稿或已发布版本）的内容哈希
     * @param status running、passed、failed
     * @param result 每个用例的结果（JSON）
     */
    public record EvalRun(
            String id,
            String tenantId,
            String suite,
            String target,
            String targetHash,
            String startedBy,
            String startedAt,
            String finishedAt,
            int total,
            int passed,
            double minPassRate,
            String status,
            String result) {}

    public static final class EvalRunStore {
        private final JdbcClient jdbc;
        private final Clock clock;

        public EvalRunStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        private static EvalRun row(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new EvalRun(
                    rs.getString("id"),
                    rs.getString("tenant_id"),
                    rs.getString("suite"),
                    rs.getString("target"),
                    rs.getString("target_hash"),
                    rs.getString("started_by"),
                    rs.getString("started_at"),
                    rs.getString("finished_at"),
                    rs.getInt("total"),
                    rs.getInt("passed"),
                    rs.getDouble("min_pass_rate"),
                    rs.getString("status"),
                    rs.getString("result"));
        }

        public EvalRun start(
                String tenantId, String suite, String target, String targetHash, String startedBy, double minPassRate) {
            String id = "evr-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.sql("INSERT INTO eval_runs (id, tenant_id, suite, target, target_hash, started_by, started_at,"
                            + " min_pass_rate, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running')")
                    .params(id, tenantId, suite, target, targetHash, startedBy, IsoTime.now(clock), minPassRate)
                    .update();
            return get(tenantId, id).orElseThrow();
        }

        public EvalRun finish(EvalRun run, int total, int passed, String status, String result) {
            jdbc.sql("UPDATE eval_runs SET finished_at = ?, total = ?, passed = ?, status = ?, result = ?"
                            + " WHERE id = ? AND tenant_id = ?")
                    .params(IsoTime.now(clock), total, passed, status, result, run.id(), run.tenantId())
                    .update();
            return get(run.tenantId(), run.id()).orElseThrow();
        }

        public Optional<EvalRun> get(String tenantId, String id) {
            return jdbc.sql("SELECT * FROM eval_runs WHERE id = ? AND tenant_id = ?")
                    .params(id, tenantId)
                    .query((rs, i) -> row(rs))
                    .optional();
        }

        /** 同一内容最近一次通过的评测，晋级 prod 的门禁据此判断 */
        public Optional<EvalRun> latestPassed(String tenantId, String target, String targetHash) {
            return jdbc.sql("SELECT * FROM eval_runs WHERE tenant_id = ? AND target = ? AND target_hash = ?"
                            + " AND status = 'passed' ORDER BY started_at DESC LIMIT 1")
                    .params(tenantId, target, targetHash)
                    .query((rs, i) -> row(rs))
                    .optional();
        }

        public List<EvalRun> listByTarget(String tenantId, String target, int limit) {
            return jdbc.sql("SELECT * FROM eval_runs WHERE tenant_id = ? AND target = ? ORDER BY started_at DESC LIMIT ?")
                    .params(tenantId, target, limit)
                    .query((rs, i) -> row(rs))
                    .list();
        }
    }
}

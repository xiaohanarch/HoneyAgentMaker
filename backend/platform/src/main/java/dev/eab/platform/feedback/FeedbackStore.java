package dev.eab.platform.feedback;

import dev.eab.common.IsoTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 经验回流的反馈与候选规则（ADR 0013） */
public final class FeedbackStore {

    /**
     * @param candidate 候选规则或候选已验证任务（JSON）
     * @param decidedBy 候选由谁生成：model（模型）或 rule（模型不可用时按反馈原文生成）
     * @param status pending、approved、rejected
     * @param packRef 审核通过后写入的租户语义包扩展，namespace/name@version
     */
    public record Feedback(
            String id,
            String tenantId,
            String userId,
            String runId,
            String traceId,
            String agent,
            String utterance,
            String reason,
            String candidate,
            String decidedBy,
            String status,
            String reviewer,
            String reviewNote,
            String packRef,
            String createdAt,
            String reviewedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public FeedbackStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Feedback insert(
            String tenantId,
            String userId,
            String runId,
            String traceId,
            String agent,
            String utterance,
            String reason,
            String candidate,
            String decidedBy) {
        String id = "fb-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("INSERT INTO feedback (id, tenant_id, user_id, run_id, trace_id, agent, utterance, reason, candidate,"
                        + " decided_by, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)")
                .params(
                        id,
                        tenantId,
                        userId,
                        runId,
                        traceId,
                        agent,
                        utterance,
                        reason,
                        candidate,
                        decidedBy,
                        IsoTime.now(clock))
                .update();
        return get(tenantId, id).orElseThrow();
    }

    private static Feedback row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Feedback(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("run_id"),
                rs.getString("trace_id"),
                rs.getString("agent"),
                rs.getString("utterance"),
                rs.getString("reason"),
                rs.getString("candidate"),
                rs.getString("decided_by"),
                rs.getString("status"),
                rs.getString("reviewer"),
                rs.getString("review_note"),
                rs.getString("pack_ref"),
                rs.getString("created_at"),
                rs.getString("reviewed_at"));
    }

    public Optional<Feedback> get(String tenantId, String id) {
        return jdbc.sql("SELECT * FROM feedback WHERE id = ? AND tenant_id = ?")
                .params(id, tenantId)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public List<Feedback> list(String tenantId, String status, int limit) {
        String sql = "SELECT * FROM feedback WHERE tenant_id = ?" + (status == null ? "" : " AND status = ?")
                + " ORDER BY created_at DESC LIMIT ?";
        var spec = jdbc.sql(sql).param(tenantId);
        if (status != null) spec = spec.param(status);
        return spec.param(limit).query((rs, i) -> row(rs)).list();
    }

    /** 只能审核还在待审的反馈；返回是否改到了 */
    public boolean review(
            String tenantId, String id, String status, String reviewer, String note, String packRef, String candidate) {
        return jdbc.sql("UPDATE feedback SET status = ?, reviewer = ?, review_note = ?, pack_ref = ?, candidate = ?,"
                                + " reviewed_at = ? WHERE id = ? AND tenant_id = ? AND status = 'pending'")
                        .params(status, reviewer, note, packRef, candidate, IsoTime.now(clock), id, tenantId)
                        .update()
                > 0;
    }
}

package dev.eab.platform.audit;

import dev.eab.common.IsoTime;
import dev.eab.spec.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/** 审计存储的 SQLite 实现 */
public final class JdbcAuditStore implements AuditStore {

    private static final int DEFAULT_LIMIT = 100;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcAuditStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public AuditEvent append(NewAuditEvent e) {
        long id = jdbc.sql("""
                        INSERT INTO audit_events (at, actor, action, target, decision, reason, detail, tenant_id, user_id,
                          agent, extensions, session_id, mode, checkpoint, tool, data_scope, rule_id, result_summary)
                        VALUES (:at, :actor, :action, :target, :decision, :reason, :detail, :tenantId, :userId,
                          :agent, :extensions, :sessionId, :mode, :checkpoint, :tool, :dataScope, :ruleId, :resultSummary)
                        RETURNING id
                        """)
                .param("at", IsoTime.now(clock))
                .param("actor", e.actor())
                .param("action", e.action())
                .param("target", e.target())
                .param("decision", e.decision())
                .param("reason", e.reason())
                .param("detail", e.detail() == null ? null : Json.MAPPER.writeValueAsString(e.detail()))
                .param("tenantId", e.tenantId())
                .param("userId", e.userId())
                .param("agent", e.agent())
                .param("extensions", e.extensions() == null ? null : Json.MAPPER.writeValueAsString(e.extensions()))
                .param("sessionId", e.sessionId())
                .param("mode", e.mode())
                .param("checkpoint", e.checkpoint())
                .param("tool", e.tool())
                .param("dataScope", e.dataScope())
                .param("ruleId", e.ruleId())
                .param("resultSummary", e.resultSummary())
                .query(Long.class)
                .single();
        return jdbc.sql("SELECT * FROM audit_events WHERE id = ?")
                .param(id)
                .query(JdbcAuditStore::map)
                .single();
    }

    @Override
    public List<AuditEvent> list(AuditFilter f) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_events WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        exact(sql, params, "action", f.action());
        exact(sql, params, "decision", f.decision());
        exact(sql, params, "tenant_id", f.tenantId());
        exact(sql, params, "user_id", f.userId());
        exact(sql, params, "tool", f.tool());
        exact(sql, params, "checkpoint", f.checkpoint());
        if (f.agent() != null) {
            sql.append(" AND agent LIKE ? ESCAPE '\\'");
            params.add(escapeLike(f.agent()) + "%");
        }
        if (f.target() != null) {
            sql.append(" AND target LIKE ? ESCAPE '\\'");
            params.add("%" + escapeLike(f.target()) + "%");
        }
        if (f.from() != null) {
            sql.append(" AND at >= ?");
            params.add(f.from());
        }
        if (f.to() != null) {
            sql.append(" AND at <= ?");
            params.add(f.to());
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(f.limit() == null ? DEFAULT_LIMIT : f.limit());
        return jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcAuditStore::map)
                .list();
    }

    private static void exact(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" = ?");
        params.add(value);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static AuditEvent map(ResultSet rs, int row) throws SQLException {
        String detail = rs.getString("detail");
        String extensions = rs.getString("extensions");
        return new AuditEvent(
                rs.getLong("id"),
                rs.getString("at"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target"),
                rs.getString("decision"),
                rs.getString("reason"),
                detail == null ? null : Json.MAPPER.readValue(detail, new TypeReference<Map<String, Object>>() {}),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("agent"),
                extensions == null ? null : Json.MAPPER.readValue(extensions, new TypeReference<List<String>>() {}),
                rs.getString("session_id"),
                rs.getString("mode"),
                rs.getString("checkpoint"),
                rs.getString("tool"),
                rs.getString("data_scope"),
                rs.getString("rule_id"),
                rs.getString("result_summary"));
    }
}

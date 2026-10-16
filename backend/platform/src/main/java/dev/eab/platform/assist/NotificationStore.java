package dev.eab.platform.assist;

import dev.eab.common.IsoTime;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 统一入口的通知区：业务事件触发的受托运行结果推送给委托人 */
public final class NotificationStore {

    /**
     * @param source 来源，例如 event:delivery.overdue
     */
    public record Notification(
            long id,
            String at,
            String tenantId,
            String userId,
            String title,
            String body,
            String source,
            String runId,
            String traceId,
            boolean read) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public long add(
            String tenantId, String userId, String title, String body, String source, String runId, String traceId) {
        return jdbc.sql("INSERT INTO notifications (at, tenant_id, user_id, title, body, source, run_id, trace_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")
                .params(IsoTime.now(clock), tenantId, userId, title, body, source, runId, traceId)
                .query(Long.class)
                .single();
    }

    public List<Notification> list(String tenantId, String userId, int limit) {
        return jdbc.sql("SELECT * FROM notifications WHERE tenant_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?")
                .params(tenantId, userId, limit)
                .query((rs, i) -> new Notification(
                        rs.getLong("id"),
                        rs.getString("at"),
                        rs.getString("tenant_id"),
                        rs.getString("user_id"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("source"),
                        rs.getString("run_id"),
                        rs.getString("trace_id"),
                        rs.getInt("read") != 0))
                .list();
    }

    /** 只能标记本人的通知 */
    public boolean markRead(String tenantId, String userId, long id) {
        return jdbc.sql("UPDATE notifications SET read = 1 WHERE id = ? AND tenant_id = ? AND user_id = ?")
                        .params(id, tenantId, userId)
                        .update()
                > 0;
    }
}

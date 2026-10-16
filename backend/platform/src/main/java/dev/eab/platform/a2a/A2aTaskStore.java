package dev.eab.platform.a2a;

import dev.eab.common.IsoTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A2A 出站调用的任务记录：多轮时按会话续接对方的 contextId、taskId；异步任务的推送回调按回调编号和令牌核对。
 * 回调令牌只存哈希。
 */
public final class A2aTaskStore {

    public record A2aTask(
            String id,
            String tenantId,
            String userId,
            String sessionId,
            String runId,
            String remote,
            String taskId,
            String contextId,
            String state,
            String text,
            String callbackTokenHash,
            String createdAt,
            String updatedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public A2aTaskStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static A2aTask row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new A2aTask(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("run_id"),
                rs.getString("remote"),
                rs.getString("task_id"),
                rs.getString("context_id"),
                rs.getString("state"),
                rs.getString("text"),
                rs.getString("callback_token_hash"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    public void insert(A2aTask task) {
        jdbc.sql(
                        "INSERT INTO a2a_tasks (id, tenant_id, user_id, session_id, run_id, remote, task_id, context_id, state,"
                                + " text, callback_token_hash, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        task.id(),
                        task.tenantId(),
                        task.userId(),
                        task.sessionId(),
                        task.runId(),
                        task.remote(),
                        task.taskId(),
                        task.contextId(),
                        task.state(),
                        task.text(),
                        task.callbackTokenHash(),
                        task.createdAt(),
                        task.updatedAt())
                .update();
    }

    public Optional<A2aTask> get(String id) {
        return jdbc.sql("SELECT * FROM a2a_tasks WHERE id = ?")
                .param(id)
                .query((rs, i) -> row(rs))
                .optional();
    }

    /** 同一会话里对同一个远程 Agent 最近一次停在 input-required 的任务，下一句话按它续接 */
    public Optional<A2aTask> pendingInput(String tenantId, String userId, String sessionId, String remote) {
        return jdbc.sql("SELECT * FROM a2a_tasks WHERE tenant_id = ? AND user_id = ? AND session_id = ? AND remote = ?"
                        + " ORDER BY updated_at DESC, rowid DESC LIMIT 1")
                .params(tenantId, userId, sessionId, remote)
                .query((rs, i) -> row(rs))
                .optional()
                .filter(t -> "input-required".equals(t.state()));
    }

    /** 同一会话里最近一次 A2A 调用停在 input-required 时返回它（不限远程 Agent），统一入口据此把下一句话交回同一个外部 Agent */
    public Optional<A2aTask> pendingInSession(String tenantId, String userId, String sessionId) {
        return jdbc.sql("SELECT * FROM a2a_tasks WHERE tenant_id = ? AND user_id = ? AND session_id = ?"
                        + " ORDER BY updated_at DESC, rowid DESC LIMIT 1")
                .params(tenantId, userId, sessionId)
                .query((rs, i) -> row(rs))
                .optional()
                .filter(t -> "input-required".equals(t.state()));
    }

    public void update(String id, String state, String text) {
        jdbc.sql("UPDATE a2a_tasks SET state = ?, text = ?, updated_at = ? WHERE id = ?")
                .params(state, text, IsoTime.now(clock), id)
                .update();
    }

    /** 回调令牌核对，常量时间比较 */
    public boolean tokenMatches(A2aTask task, String token) {
        if (token == null || token.isBlank()) return false;
        return MessageDigest.isEqual(
                hash(token).getBytes(StandardCharsets.UTF_8),
                task.callbackTokenHash().getBytes(StandardCharsets.UTF_8));
    }
}

package dev.eab.platform.runtime;

import dev.eab.common.IsoTime;
import dev.eab.runtime.Ports;
import dev.eab.runtime.RunContext;
import dev.eab.spec.Json;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.TraceStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/** 运行时在平台数据库里的存储：追踪、运行、会话与长期记忆、风险告警、IM 渠道消息 */
public final class RuntimeStores {

    private RuntimeStores() {}

    /** 追踪：span 按 (traceId, spanId) 存放，挂起时写入的 suspended 状态在恢复后被最终状态覆盖 */
    public static final class JdbcTraceStore implements TraceStore {
        private final JdbcClient jdbc;

        public JdbcTraceStore(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public synchronized void upsert(SpanRecord span) {
            Optional<SpanRecord> existing = get(span.traceId(), span.spanId());
            SpanRecord merged = SpanRecord.merge(existing.orElse(null), span);
            if (merged == null) return;
            jdbc.sql("INSERT OR REPLACE INTO trace_spans (trace_id, span_id, parent_span_id, name, kind, start_time, end_time,"
                            + " status, status_message, attributes, prompt_segments, tenant_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            merged.traceId(),
                            merged.spanId(),
                            merged.parentSpanId(),
                            merged.name(),
                            merged.kind().name(),
                            merged.startTime(),
                            merged.endTime(),
                            merged.status().name(),
                            merged.statusMessage(),
                            Json.MAPPER.writeValueAsString(merged.attributes()),
                            merged.promptSegments() == null ? null : Json.MAPPER.writeValueAsString(merged.promptSegments()),
                            merged.tenantId())
                    .update();
        }

        private Optional<SpanRecord> get(String traceId, String spanId) {
            return jdbc.sql("SELECT * FROM trace_spans WHERE trace_id = ? AND span_id = ?")
                    .params(traceId, spanId)
                    .query((rs, i) -> map(rs))
                    .optional();
        }

        @Override
        public List<SpanRecord> list(String traceId) {
            return jdbc.sql("SELECT * FROM trace_spans WHERE trace_id = ? ORDER BY start_time, rowid")
                    .param(traceId)
                    .query((rs, i) -> map(rs))
                    .list();
        }

        private static SpanRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
            String segments = rs.getString("prompt_segments");
            return new SpanRecord(
                    rs.getString("trace_id"),
                    rs.getString("span_id"),
                    rs.getString("parent_span_id"),
                    rs.getString("name"),
                    SpanRecord.SpanKind.valueOf(rs.getString("kind")),
                    rs.getString("start_time"),
                    rs.getString("end_time"),
                    SpanRecord.SpanStatus.valueOf(rs.getString("status")),
                    rs.getString("status_message"),
                    Json.MAPPER.readValue(rs.getString("attributes"), new TypeReference<Map<String, Object>>() {}),
                    segments == null
                            ? null
                            : Json.MAPPER.readValue(segments, new TypeReference<List<SpanRecord.PromptSegment>>() {}),
                    rs.getString("tenant_id"));
        }
    }

    /**
     * 一次运行。
     *
     * @param status running、suspended、completed、failed
     * @param journal 运行日志（JSON），挂起后恢复时从这里重放
     * @param result 结束时的结果（JSON），运行中为 null
     */
    public record RunRecord(
            String id,
            String traceId,
            String sessionId,
            String tenantId,
            String userId,
            String agent,
            String mode,
            String delegationId,
            String utterance,
            String status,
            String journal,
            String result,
            String createdAt,
            String updatedAt) {}

    public static final class RunStore {
        private final JdbcClient jdbc;
        private final Clock clock;

        public RunStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        public void insert(RunRecord run) {
            jdbc.sql("INSERT INTO runs (id, trace_id, session_id, tenant_id, user_id, agent, mode, delegation_id, utterance,"
                            + " status, journal, result, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            run.id(), run.traceId(), run.sessionId(), run.tenantId(), run.userId(), run.agent(), run.mode(),
                            run.delegationId(), run.utterance(), run.status(), run.journal(), run.result(),
                            run.createdAt(), run.updatedAt())
                    .update();
        }

        public void update(String id, String status, String journal, String result) {
            jdbc.sql("UPDATE runs SET status = ?, journal = ?, result = ?, updated_at = ? WHERE id = ?")
                    .params(status, journal, result, IsoTime.now(clock), id)
                    .update();
        }

        public Optional<RunRecord> get(String id) {
            return jdbc.sql("SELECT * FROM runs WHERE id = ?")
                    .param(id)
                    .query((rs, i) -> new RunRecord(
                            rs.getString("id"),
                            rs.getString("trace_id"),
                            rs.getString("session_id"),
                            rs.getString("tenant_id"),
                            rs.getString("user_id"),
                            rs.getString("agent"),
                            rs.getString("mode"),
                            rs.getString("delegation_id"),
                            rs.getString("utterance"),
                            rs.getString("status"),
                            rs.getString("journal"),
                            rs.getString("result"),
                            rs.getString("created_at"),
                            rs.getString("updated_at")))
                    .optional();
        }

        /** 按追踪编号找运行，读取追踪时据此判断权限 */
        public Optional<RunRecord> byTrace(String traceId) {
            return jdbc.sql("SELECT id FROM runs WHERE trace_id = ? LIMIT 1")
                    .param(traceId)
                    .query(String.class)
                    .optional()
                    .flatMap(this::get);
        }
    }

    /** 会话记忆与长期记忆（主提示词 8.5 节） */
    public static final class MemoryStore implements Ports.Memory {
        /** 注入提示词的会话轮数上限 */
        static final int SESSION_TURNS = 6;

        private final JdbcClient jdbc;
        private final Clock clock;

        public MemoryStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        public record MemoryItem(long id, String agent, String text, String createdAt) {}

        @Override
        public List<String> longTerm(String tenantId, String userId, String agent) {
            return jdbc.sql("SELECT text FROM memories WHERE tenant_id = ? AND user_id = ? AND agent = ? ORDER BY id")
                    .params(tenantId, userId, agent)
                    .query(String.class)
                    .list();
        }

        public List<MemoryItem> list(String tenantId, String userId, String agent) {
            String sql = "SELECT * FROM memories WHERE tenant_id = ? AND user_id = ?" + (agent == null ? "" : " AND agent = ?")
                    + " ORDER BY id";
            List<Object> params = new ArrayList<>(List.of(tenantId, userId));
            if (agent != null) params.add(agent);
            return jdbc.sql(sql)
                    .params(params)
                    .query((rs, i) -> new MemoryItem(
                            rs.getLong("id"), rs.getString("agent"), rs.getString("text"), rs.getString("created_at")))
                    .list();
        }

        public MemoryItem add(String tenantId, String userId, String agent, String text) {
            String at = IsoTime.now(clock);
            long id = jdbc.sql("INSERT INTO memories (tenant_id, user_id, agent, text, created_at) VALUES (?, ?, ?, ?, ?)"
                            + " RETURNING id")
                    .params(tenantId, userId, agent, text, at)
                    .query(Long.class)
                    .single();
            return new MemoryItem(id, agent, text, at);
        }

        /** 只清除本人的记忆；agent 为空时清除本人在全部 Agent 上的记忆 */
        public int clear(String tenantId, String userId, String agent) {
            String sql = "DELETE FROM memories WHERE tenant_id = ? AND user_id = ?" + (agent == null ? "" : " AND agent = ?");
            List<Object> params = new ArrayList<>(List.of(tenantId, userId));
            if (agent != null) params.add(agent);
            return jdbc.sql(sql).params(params).update();
        }

        @Override
        public List<List<String>> session(String sessionId) {
            List<List<String>> turns = jdbc.sql("SELECT utterance, reply FROM session_messages WHERE session_id = ?"
                            + " ORDER BY id DESC LIMIT ?")
                    .params(sessionId, SESSION_TURNS)
                    .query((rs, i) -> List.of(rs.getString("utterance"), rs.getString("reply")))
                    .list();
            List<List<String>> ordered = new ArrayList<>(turns);
            java.util.Collections.reverse(ordered);
            return ordered;
        }

        @Override
        public void appendSession(String sessionId, String tenantId, String userId, String agent, String utterance, String reply) {
            jdbc.sql("INSERT INTO session_messages (session_id, tenant_id, user_id, agent, utterance, reply, at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(sessionId, tenantId, userId, agent, utterance, reply, IsoTime.now(clock))
                    .update();
        }
    }

    /** 监督层的风险告警与写操作记录 */
    public static final class AlertStore implements Ports.Alerts {
        private final JdbcClient jdbc;
        private final Clock clock;

        public AlertStore(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        public record Alert(
                long id, String at, String tenantId, String userId, String agent, String runId, String type, String severity,
                String message, Map<String, Object> detail) {}

        @Override
        public void raise(RunContext context, String type, String severity, String message, Map<String, Object> detail) {
            jdbc.sql("INSERT INTO risk_alerts (at, tenant_id, user_id, agent, run_id, type, severity, message, detail)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(
                            IsoTime.now(clock),
                            context.tenantId(),
                            context.user().id(),
                            context.agent(),
                            context.runId(),
                            type,
                            severity,
                            message,
                            Json.MAPPER.writeValueAsString(detail))
                    .update();
        }

        public List<Alert> list(String tenantId, int limit) {
            String sql = "SELECT * FROM risk_alerts" + (tenantId == null ? "" : " WHERE tenant_id = :tenant")
                    + " ORDER BY id DESC LIMIT :limit";
            var spec = jdbc.sql(sql).param("limit", limit);
            if (tenantId != null) spec = spec.param("tenant", tenantId);
            return spec.query((rs, i) -> new Alert(
                            rs.getLong("id"),
                            rs.getString("at"),
                            rs.getString("tenant_id"),
                            rs.getString("user_id"),
                            rs.getString("agent"),
                            rs.getString("run_id"),
                            rs.getString("type"),
                            rs.getString("severity"),
                            rs.getString("message"),
                            Json.MAPPER.readValue(rs.getString("detail"), new TypeReference<Map<String, Object>>() {})))
                    .list();
        }

        @Override
        public int recentWrites(String sessionId, long windowSeconds) {
            String since = IsoTime.format(clock.instant().minus(Duration.ofSeconds(windowSeconds)));
            return jdbc.sql("SELECT COUNT(*) FROM write_log WHERE session_id = ? AND at >= ?")
                    .params(sessionId, since)
                    .query(Integer.class)
                    .single();
        }

        @Override
        public void recordWrite(String sessionId, String tool) {
            jdbc.sql("INSERT INTO write_log (session_id, tool, at) VALUES (?, ?, ?)")
                    .params(sessionId, tool, IsoTime.now(clock))
                    .update();
        }
    }

    /**
     * 模拟的 IM 渠道（主提示词 8.6 节）：工作流的 notify 步骤和 IM_WEBHOOK 连接上的通知工具都写到这里，
     * 演示界面 /channels/im（P4）从这里读取。结构上对应真实 IM 的 webhook 适配器。
     */
    public static final class ImChannel implements Ports.Notifier {
        public static final String CONNECTION = "IM_WEBHOOK";

        private final JdbcClient jdbc;
        private final Clock clock;

        public ImChannel(JdbcClient jdbc, Clock clock) {
            this.jdbc = jdbc;
            this.clock = clock;
        }

        public record ImMessage(long id, String at, String tenantId, String channel, String recipient, String message, String runId) {}

        @Override
        public void notify(RunContext context, String channel, String message) {
            post(context.tenantId(), channel, null, message, context.runId());
        }

        public void post(String tenantId, String channel, String recipient, String message, String runId) {
            jdbc.sql("INSERT INTO im_messages (at, tenant_id, channel, recipient, message, run_id) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(IsoTime.now(clock), tenantId, channel, recipient, message, runId)
                    .update();
        }

        public List<ImMessage> list(String tenantId, int limit) {
            return jdbc.sql("SELECT * FROM im_messages WHERE tenant_id = ? ORDER BY id DESC LIMIT ?")
                    .params(tenantId, limit)
                    .query((rs, i) -> new ImMessage(
                            rs.getLong("id"),
                            rs.getString("at"),
                            rs.getString("tenant_id"),
                            rs.getString("channel"),
                            rs.getString("recipient"),
                            rs.getString("message"),
                            rs.getString("run_id")))
                    .list();
        }
    }
}

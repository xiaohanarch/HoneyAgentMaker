package dev.eab.platform.events;

import dev.eab.platform.IsoTime;
import dev.eab.spec.Json;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/** 业务事件（主提示词 6.4 节）：持久化后在进程内广播，订阅方（P4 的触发器）按类型监听 */
public final class EventStore {

    public static final List<String> BUSINESS_EVENT_TYPES = List.of(
            "delivery.overdue", "inventory.below_safety_stock", "transaction.anomaly_created", "period.close_check_failed");

    public record BusinessEvent(long id, String receivedAt, String source, String type, String tenantId, Map<String, Object> payload) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private final List<Consumer<BusinessEvent>> listeners = new CopyOnWriteArrayList<>();

    public EventStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public BusinessEvent append(String source, String type, String tenantId, Map<String, Object> payload) {
        String receivedAt = IsoTime.now(clock);
        long id = jdbc.sql("INSERT INTO business_events (received_at, source, type, tenant_id, payload) VALUES (?, ?, ?, ?, ?) RETURNING id")
                .params(receivedAt, source, type, tenantId, Json.MAPPER.writeValueAsString(payload))
                .query(Long.class)
                .single();
        BusinessEvent stored = new BusinessEvent(id, receivedAt, source, type, tenantId, payload);
        listeners.forEach(l -> l.accept(stored));
        return stored;
    }

    public List<BusinessEvent> list(String tenantId, int limit) {
        String sql = "SELECT * FROM business_events" + (tenantId == null ? "" : " WHERE tenant_id = :tenantId")
                + " ORDER BY id DESC LIMIT :limit";
        var spec = jdbc.sql(sql).param("limit", limit);
        if (tenantId != null) spec = spec.param("tenantId", tenantId);
        return spec.query((rs, row) -> new BusinessEvent(
                        rs.getLong("id"),
                        rs.getString("received_at"),
                        rs.getString("source"),
                        rs.getString("type"),
                        rs.getString("tenant_id"),
                        Json.MAPPER.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() {})))
                .list();
    }

    /** 返回取消订阅的函数 */
    public Runnable subscribe(Consumer<BusinessEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}

package dev.eab.platform.identity;

import dev.eab.common.IsoTime;
import dev.eab.spec.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/** 受托自主运行的委托：登记、撤销、核实（主提示词 7.2 节） */
public final class DelegationStore {

    public record Delegation(
            String id,
            String tenantId,
            String delegatorUserId,
            String agent,
            List<String> scope,
            String validFrom,
            String validUntil,
            String revokedAt,
            String revokedBy,
            String note,
            String createdBy,
            String createdAt) {}

    public record NewDelegation(
            String tenantId,
            String delegatorUserId,
            String agent,
            List<String> scope,
            String validFrom,
            String validUntil,
            String note,
            String createdBy) {}

    /** @param reason not-found、revoked、not-yet-valid、expired 之一；有效时为 null */
    public record Status(boolean active, String reason, Delegation delegation) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public DelegationStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Delegation create(NewDelegation input) {
        // 128 位随机编号，不能被猜测
        return create(input, "DLG-" + UUID.randomUUID().toString().replace("-", "").toUpperCase());
    }

    public Delegation create(NewDelegation input, String id) {
        jdbc.sql("""
                        INSERT INTO delegations (id, tenant_id, delegator_user_id, agent, scope, valid_from, valid_until,
                          revoked_at, revoked_by, note, created_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                        """)
                .params(id, input.tenantId(), input.delegatorUserId(), input.agent(),
                        Json.MAPPER.writeValueAsString(input.scope()), input.validFrom(), input.validUntil(), input.note(),
                        input.createdBy(), IsoTime.now(clock))
                .update();
        return get(id).orElseThrow();
    }

    public Optional<Delegation> get(String id) {
        return jdbc.sql("SELECT * FROM delegations WHERE id = ?").param(id).query(DelegationStore::map).optional();
    }

    public List<Delegation> listByTenant(String tenantId) {
        return jdbc.sql("SELECT * FROM delegations WHERE tenant_id = ? ORDER BY created_at DESC")
                .param(tenantId)
                .query(DelegationStore::map)
                .list();
    }

    /** 撤销即时生效：之后的令牌交换和 BO API 核实都会失败。已撤销的委托不能再改写撤销人和时间 */
    public Optional<Delegation> revoke(String id, String revokedBy) {
        jdbc.sql("UPDATE delegations SET revoked_at = ?, revoked_by = ? WHERE id = ? AND revoked_at IS NULL")
                .params(IsoTime.now(clock), revokedBy, id)
                .update();
        return get(id);
    }

    public Status status(String id) {
        Optional<Delegation> found = get(id);
        if (found.isEmpty()) return new Status(false, "not-found", null);
        Delegation d = found.get();
        String now = IsoTime.now(clock);
        if (d.revokedAt() != null) return new Status(false, "revoked", d);
        if (now.compareTo(d.validFrom()) < 0) return new Status(false, "not-yet-valid", d);
        if (now.compareTo(d.validUntil()) > 0) return new Status(false, "expired", d);
        return new Status(true, null, d);
    }

    private static Delegation map(ResultSet rs, int row) throws SQLException {
        return new Delegation(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("delegator_user_id"),
                rs.getString("agent"),
                Json.MAPPER.readValue(rs.getString("scope"), new TypeReference<List<String>>() {}),
                rs.getString("valid_from"),
                rs.getString("valid_until"),
                rs.getString("revoked_at"),
                rs.getString("revoked_by"),
                rs.getString("note"),
                rs.getString("created_by"),
                rs.getString("created_at"));
    }
}

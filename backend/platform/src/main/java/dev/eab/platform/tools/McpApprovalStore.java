package dev.eab.platform.tools;

import dev.eab.common.IsoTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** MCP Server 登记的审批状态（主提示词 8.3、10.7 节，ADR 0011）：pending、approved、rejected */
public final class McpApprovalStore {

    public record Approval(
            long id,
            String tenantId,
            String source,
            String status,
            String requestedBy,
            String requestedAt,
            String decidedBy,
            String decidedAt,
            String note) {

        public boolean approved() {
            return "approved".equals(status);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public McpApprovalStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private static Approval row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Approval(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getString("source"),
                rs.getString("status"),
                rs.getString("requested_by"),
                rs.getString("requested_at"),
                rs.getString("decided_by"),
                rs.getString("decided_at"),
                rs.getString("note"));
    }

    /** 提交审批；同一个工具来源重新提交时回到待审批 */
    public Approval request(String tenantId, String source, String requestedBy) {
        String now = IsoTime.now(clock);
        int updated = jdbc.sql(
                        "UPDATE mcp_approvals SET status = 'pending', requested_by = ?, requested_at = ?, decided_by = NULL,"
                                + " decided_at = NULL, note = NULL WHERE tenant_id = ? AND source = ?")
                .params(requestedBy, now, tenantId, source)
                .update();
        if (updated == 0) {
            jdbc.sql(
                            "INSERT INTO mcp_approvals (tenant_id, source, status, requested_by, requested_at) VALUES (?, ?, 'pending', ?, ?)")
                    .params(tenantId, source, requestedBy, now)
                    .update();
        }
        return find(tenantId, source).orElseThrow();
    }

    public Optional<Approval> find(String tenantId, String source) {
        return jdbc.sql("SELECT * FROM mcp_approvals WHERE tenant_id = ? AND source = ?")
                .params(tenantId, source)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public Optional<Approval> get(String tenantId, long id) {
        return jdbc.sql("SELECT * FROM mcp_approvals WHERE tenant_id = ? AND id = ?")
                .params(tenantId, id)
                .query((rs, i) -> row(rs))
                .optional();
    }

    public List<Approval> list(String tenantId) {
        return jdbc.sql("SELECT * FROM mcp_approvals WHERE tenant_id = ? ORDER BY id DESC")
                .param(tenantId)
                .query((rs, i) -> row(rs))
                .list();
    }

    public Approval decide(Approval approval, boolean approved, String decidedBy, String note) {
        jdbc.sql(
                        "UPDATE mcp_approvals SET status = ?, decided_by = ?, decided_at = ?, note = ? WHERE id = ? AND tenant_id = ?")
                .params(
                        approved ? "approved" : "rejected",
                        decidedBy,
                        IsoTime.now(clock),
                        note,
                        approval.id(),
                        approval.tenantId())
                .update();
        return get(approval.tenantId(), approval.id()).orElseThrow();
    }

    /** 某个工具来源是否已审批通过 */
    public boolean approved(String tenantId, String source) {
        return find(tenantId, source).map(Approval::approved).orElse(false);
    }
}

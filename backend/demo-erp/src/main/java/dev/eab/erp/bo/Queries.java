package dev.eab.erp.bo;

import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.Organization;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** BO API、Open API、页面共用的查询 */
public final class Queries {

    private Queries() {}

    public static String orgName(String tenantId, String orgId) {
        return DemoDirectory.findTenant(tenantId)
                .flatMap(t -> t.purchasingOrgs().stream()
                        .filter(o -> o.id().equals(orgId))
                        .findFirst())
                .map(Organization::name)
                .orElse(orgId);
    }

    public static String companyName(String tenantId, String companyId) {
        return DemoDirectory.findTenant(tenantId)
                .flatMap(t -> t.companies().stream()
                        .filter(o -> o.id().equals(companyId))
                        .findFirst())
                .map(Organization::name)
                .orElse(companyId);
    }

    /**
     * 订单的交付逾期情况。
     *
     * @param overdueDays 逾期天数：未交付的行按今天算，已交付的行按实际交付日期算，取订单行最大值（语义包指标 overdue_days）
     * @param nextPlannedDate 下一个未交付行的计划交期，全部交付时为 null
     */
    public record PoDeliveryStatus(long overdueDays, String nextPlannedDate) {}

    /** 计算订单的交付逾期情况，口径与采购语义包的 overdue_days 指标一致 */
    public static Map<String, PoDeliveryStatus> deliveryStatus(
            ErpDb db, String tenantId, List<String> poIds, Instant today) {
        Map<String, PoDeliveryStatus> result = new LinkedHashMap<>();
        if (poIds.isEmpty()) return result;
        String todayIso = IsoTime.date(today);
        var lines = Rows.list(
                db.jdbc(),
                "SELECT * FROM po_lines WHERE tenant_id = ? AND po_id IN " + Rows.in(poIds.size()),
                Rows.params(tenantId, poIds));
        var delivered = Rows.list(
                db.jdbc(),
                "SELECT * FROM deliveries WHERE tenant_id = ? AND po_id IN " + Rows.in(poIds.size()),
                Rows.params(tenantId, poIds));
        for (String poId : poIds) {
            long overdueDays = 0;
            String nextPlannedDate = null;
            for (var line : lines) {
                if (!poId.equals(line.get("poId"))) continue;
                var records = delivered.stream()
                        .filter(d -> line.get("id").equals(d.get("poLineId")))
                        .toList();
                double quantity = records.stream()
                        .mapToDouble(d -> Rows.num(d, "quantity"))
                        .sum();
                Optional<String> lastActual = records.stream()
                        .map(d -> Rows.str(d, "actualDate"))
                        .filter(d -> d != null)
                        .sorted()
                        .reduce((a, b) -> b);
                String planned = Rows.str(line, "plannedDeliveryDate");
                String end =
                        quantity >= Rows.num(line, "quantity") && lastActual.isPresent() ? lastActual.get() : todayIso;
                overdueDays = Math.max(overdueDays, IsoTime.daysBetween(planned, end));
                if (quantity < Rows.num(line, "quantity")
                        && (nextPlannedDate == null || planned.compareTo(nextPlannedDate) < 0)) {
                    nextPlannedDate = planned;
                }
            }
            result.put(poId, new PoDeliveryStatus(Math.max(0, overdueDays), nextPlannedDate));
        }
        return result;
    }

    public static Map<String, String> supplierNames(ErpDb db, String tenantId) {
        Map<String, String> names = new HashMap<>();
        for (var row : Rows.list(db.jdbc(), "SELECT id, name FROM suppliers WHERE tenant_id = ?", tenantId)) {
            names.put(Rows.str(row, "id"), Rows.str(row, "name"));
        }
        return names;
    }

    public static Optional<Map<String, Object>> findPurchaseOrder(ErpDb db, String tenantId, String poId) {
        return Rows.one(db.jdbc(), "SELECT * FROM purchase_orders WHERE tenant_id = ? AND id = ?", tenantId, poId);
    }
}

package dev.eab.erp.seed;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import dev.eab.erp.seed.SeedGenerator.Story;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 对应 TS 版 seed.test.ts */
class SeederTest {

    static final Instant TODAY = Instant.parse("2026-10-15T00:00:00Z");

    static List<Map<String, Object>> table(Map<String, List<Map<String, Object>>> data, String name) {
        return data.get(name);
    }

    @Test
    @DisplayName("同一天重复生成结果完全相同")
    void deterministic() {
        assertThat(SeedGenerator.generate("t001", TODAY)).isEqualTo(SeedGenerator.generate("t001", TODAY));
    }

    @Test
    @DisplayName("规模符合主提示词 6.2 节")
    void sizes() {
        var data = SeedGenerator.generate("t001", TODAY);
        assertThat(table(data, "suppliers")).hasSize(40);
        assertThat(table(data, "purchaseOrders")).hasSize(200);
        assertThat(table(data, "materials")).hasSize(100);
        assertThat(table(data, "transactionAnomalies")).hasSize(30);
        assertThat(table(data, "deliveries").size()).isBetween(401, 649);
    }

    @Test
    @DisplayName("百润精密属于华东，PO-2026-0117 计划交期在 120 天前且未交付")
    void storyPurchaseOrder() {
        var data = SeedGenerator.generate("t001", TODAY);
        var supplier = table(data, "suppliers").stream()
                .filter(s -> Story.SUPPLIER_NAME.equals(s.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(supplier).containsEntry("id", "S-0001").containsEntry("purchasingOrg", "P-EAST");
        var po = table(data, "purchaseOrders").stream()
                .filter(p -> Story.PO_ID.equals(p.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(po)
                .containsEntry("supplierId", "S-0001")
                .containsEntry("status", "open")
                .containsEntry("companyId", "C100");
        var line = table(data, "poLines").stream()
                .filter(l -> Story.PO_ID.equals(l.get("poId")))
                .findFirst()
                .orElseThrow();
        assertThat(line).containsEntry("plannedDeliveryDate", "2026-06-17");
        assertThat(table(data, "deliveries").stream().filter(d -> Story.PO_ID.equals(d.get("poId"))))
                .isEmpty();
    }

    @Test
    @DisplayName("华东和华南的供应商数量明显不同")
    void supplierSplit() {
        var suppliers = table(SeedGenerator.generate("t001", TODAY), "suppliers");
        assertThat(suppliers.stream().filter(s -> "P-EAST".equals(s.get("purchasingOrg"))))
                .hasSize(26);
        assertThat(suppliers.stream().filter(s -> "P-SOUTH".equals(s.get("purchasingOrg"))))
                .hasSize(14);
    }

    @Test
    @DisplayName("本月有金额超过 50 万的高风险异常，资产核算有 3 个未通过的检查项")
    void financeStory() {
        var data = SeedGenerator.generate("t001", TODAY);
        long big = table(data, "transactionAnomalies").stream()
                .filter(a -> List.of("high", "critical").contains(a.get("severity"))
                        && Rows.num(a, "amount") > 500_000
                        && Rows.str(a, "detectedAt").compareTo("2026-10-01") >= 0
                        && "C100".equals(a.get("companyId")))
                .count();
        assertThat(big).isGreaterThanOrEqualTo(2);
        assertThat(table(data, "closeCheckItems").stream()
                        .filter(c -> "C100".equals(c.get("companyId")) && "failed".equals(c.get("status"))))
                .hasSize(3);
    }

    @Test
    @DisplayName("百润精密最近一次询价是 RFQ-2026-0058，有 3 行报价")
    void latestRfq() {
        var data = SeedGenerator.generate("t001", TODAY);
        var latest = table(data, "rfqs").stream()
                .filter(r -> "S-0001".equals(r.get("supplierId")))
                .max(Comparator.comparing(r -> Rows.str(r, "issuedAt")))
                .orElseThrow();
        assertThat(latest.get("id")).isEqualTo(Story.LATEST_RFQ_ID);
        var prices = table(data, "quotations").stream()
                .filter(q -> Story.LATEST_RFQ_ID.equals(q.get("rfqId")))
                .map(q -> Rows.num(q, "unitPrice"))
                .toList();
        assertThat(prices).containsExactly(46.5, 45.8, 47.2);
    }

    @Test
    @DisplayName("t002 使用另一套供应商名称，没有百润精密")
    void otherTenantNames() {
        Set<Object> t001 = table(SeedGenerator.generate("t001", TODAY), "suppliers").stream()
                .map(s -> s.get("name"))
                .collect(Collectors.toSet());
        var t002 = table(SeedGenerator.generate("t002", TODAY), "suppliers").stream()
                .map(s -> s.get("name"))
                .toList();
        assertThat(t002).doesNotContain(Story.SUPPLIER_NAME);
        assertThat(t002.stream().filter(t001::contains).count()).isLessThan(5);
    }

    @Test
    @DisplayName("写入数据库可重复执行，按租户隔离")
    void seedIsRepeatable() {
        try (ErpDb db = ErpDb.open(":memory:")) {
            assertThat(Seeder.isSeeded(db)).isFalse();
            Seeder.seed(db, TODAY);
            Seeder.seed(db, TODAY);
            assertThat(Seeder.isSeeded(db)).isTrue();
            assertThat(Rows.list(db.jdbc(), "SELECT * FROM suppliers WHERE tenant_id = 't001'"))
                    .hasSize(40);
            assertThat(Rows.list(
                            db.jdbc(),
                            "SELECT * FROM suppliers WHERE tenant_id = 't002' AND name = ?",
                            Story.SUPPLIER_NAME))
                    .isEmpty();
        }
    }
}

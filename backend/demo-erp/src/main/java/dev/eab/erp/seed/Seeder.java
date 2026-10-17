package dev.eab.erp.seed;

import dev.eab.directory.DemoDirectory;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import java.time.Instant;
import java.util.List;

/** 写入演示数据（pnpm seed:reset、演示控制台的“重置”）：清空后按 today 重新生成全部租户的数据 */
public final class Seeder {

    private Seeder() {}

    /** 运行期产生的数据，重置时一并清空 */
    private static final List<String> RUNTIME_TABLES = List.of(
            "delivery_date_changes", "followup_tasks", "purchase_requisitions", "idempotency_keys", "access_log");

    public static boolean isSeeded(ErpDb db) {
        return db.jdbc().sql("SELECT COUNT(*) FROM suppliers").query(Long.class).single() > 0;
    }

    public static void seed(ErpDb db, Instant today) {
        db.inTransaction(() -> {
            for (String table : SeedGenerator.TABLES)
                db.jdbc().sql("DELETE FROM " + Rows.snake(table)).update();
            for (String table : RUNTIME_TABLES)
                db.jdbc().sql("DELETE FROM " + table).update();
            for (var tenant : DemoDirectory.TENANTS) {
                var data = SeedGenerator.generate(tenant.id(), today);
                data.forEach((table, rows) -> rows.forEach(row -> Rows.insert(db.jdbc(), Rows.snake(table), row)));
            }
        });
    }
}

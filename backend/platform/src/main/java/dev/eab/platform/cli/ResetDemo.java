package dev.eab.platform.cli;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.identity.DelegationStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * pnpm seed:reset 的平台部分：清空审计、业务事件、委托、租户设置，并重新预置演示委托。
 * 资产与密钥保留（标品资产仍然有效，签名不变）。服务运行中也可以执行。DemoERP 部分见 demo-erp 的 ResetErp。
 */
public final class ResetDemo {

    private ResetDemo() {}

    static final List<String> TABLES = List.of("audit_events", "business_events", "delegations", "tenant_settings");

    public static void reset(Path dataDir, Clock clock) {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            db.inTransaction(() -> TABLES.forEach(
                    table -> db.jdbc().sql("DELETE FROM " + table).update()));
            PlatformServices.ensureDemoDefaults(new DelegationStore(db.jdbc(), clock), clock);
        }
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        Path dataDir = DataDirs.resolve();
        reset(dataDir, Clock.systemUTC());
        System.out.println("平台演示数据已重置：" + dataDir);
    }
}

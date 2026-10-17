package dev.eab.platform.cli;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.identity.DelegationStore;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * pnpm seed:reset 的平台部分：清空审计、业务事件、委托、租户设置和运行期数据（运行、追踪、记忆、告警、需求方案），撤回
 * 演示中写入的资产（模拟的标品升级、经验回流的租户语义包扩展、生成的方案、扩展需求），并重新预置演示委托。
 * 其余资产与密钥保留（标品资产仍然有效，签名不变）。服务运行中也可以执行。DemoERP 部分见 demo-erp 的 ResetErp。
 */
public final class ResetDemo {

    private ResetDemo() {}

    /** 运行期数据：审计、业务事件、委托、租户设置，以及运行、追踪、记忆、告警、IM 消息、需求方案 */
    static final List<String> TABLES = List.of(
            "audit_events",
            "business_events",
            "delegations",
            "tenant_settings",
            "runs",
            "trace_spans",
            "session_messages",
            "memories",
            "risk_alerts",
            "write_log",
            "im_messages",
            "notifications",
            "studio_drafts",
            "request_plans",
            "deployments",
            "eval_runs",
            "feedback",
            "upgrade_regressions",
            "package_deployments",
            "solution_packages",
            "run_metrics",
            "business_metrics");

    /**
     * 演示过程中写进注册中心、重新演示前要撤回的资产：模拟的标品升级（故事 F）、经验回流写入的租户语义包扩展、
     * 治理中心生成的方案、扩展需求（故事 E）。其他资产保留。
     */
    static final List<String> DEMO_ASSETS = List.of(
            "DELETE FROM assets WHERE namespace = 'vendor.fin' AND name = 'transaction-anomaly-analyst' AND version = '1.3.0'",
            "DELETE FROM assets WHERE namespace LIKE 'tenant.%.semantic'",
            "DELETE FROM assets WHERE namespace LIKE 'tenant.%.solutions'",
            "DELETE FROM assets WHERE kind = 'DevRequest'");

    public static void reset(Path dataDir, Clock clock) {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            db.inTransaction(() -> {
                TABLES.forEach(table -> db.jdbc().sql("DELETE FROM " + table).update());
                DEMO_ASSETS.forEach(sql -> db.jdbc().sql(sql).update());
            });
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

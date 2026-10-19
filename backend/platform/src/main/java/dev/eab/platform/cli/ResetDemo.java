package dev.eab.platform.cli;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.identity.DelegationStore;
import dev.eab.platform.registry.AssetSeeder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * pnpm seed:reset 的平台部分：清空审计、业务事件、委托、租户设置和运行期数据（运行、追踪、记忆、告警、需求方案），
 * 撤回演示中发布的全部租户资产（故事 B 的扩展、故事 E 的完成态、经验回流的语义包、生成的方案、扩展需求、
 * 故事 F 起点与完成态发布的快照）和模拟的标品升级，并重新预置演示委托。租户的基础设施（连接、工具来源、
 * 工作流、远程 Agent）与标品资产、密钥保留；每次重置后注册中心都回到首次启动的状态，
 * 各故事可以任意顺序、任意次数重演。服务运行中也可以执行。DemoERP 部分见 demo-erp 的 ResetErp。
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
     * 演示过程中写进注册中心、重新演示前要撤回的资产：模拟的标品升级（故事 F），以及演示中途发布的全部
     * 租户资产，按命名空间整组撤回。只有 {@link AssetSeeder#TENANT_INFRA} 对应的命名空间保留（理由见其
     * Javadoc）；启动入库只覆盖这些基础设施，所以其余租户资产必然来自演示过程，整组撤回即回到首次启动状态。
     */
    static final List<String> DEMO_ASSETS = demoAssets();

    private static List<String> demoAssets() {
        StringBuilder rollbackTenant = new StringBuilder("DELETE FROM assets WHERE namespace LIKE 'tenant.%'");
        for (String infra : AssetSeeder.TENANT_INFRA)
            rollbackTenant.append(" AND namespace NOT LIKE '%.").append(infra).append("'");
        return List.of(
                "DELETE FROM assets WHERE namespace = 'vendor.fin' AND name = 'transaction-anomaly-analyst'"
                        + " AND version = '1.3.0'",
                rollbackTenant.toString());
    }

    public static void reset(Path dataDir, Clock clock) {
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            db.inTransaction(() -> {
                TABLES.forEach(table -> db.jdbc().sql("DELETE FROM " + table).update());
                DEMO_ASSETS.forEach(sql -> db.jdbc().sql(sql).update());
            });
            PlatformServices.ensureDemoDefaults(new DelegationStore(db.jdbc(), clock), clock);
        }
    }

    /** 演示控制台（HTTP）经平台服务自身的连接执行的重置，语义与 {@link #reset(Path, Clock)} 完全一致 */
    public static void reset(PlatformServices services) {
        services.db().inTransaction(() -> {
            TABLES.forEach(
                    table -> services.db().jdbc().sql("DELETE FROM " + table).update());
            DEMO_ASSETS.forEach(sql -> services.db().jdbc().sql(sql).update());
        });
        PlatformServices.ensureDemoDefaults(services.delegations(), services.clock());
    }

    /**
     * 故事 F 与完成态发布快照前，清空该租户已发布的全部资产。快照文件是完成态的唯一权威，
     * 演示中途经 Studio 发布的扩展（故事 B）会与快照扩展给标品挂载同名工具，组装门禁
     * （TOOL_NAME_CONFLICT）会拒绝重播；先清空再整树重播，完成态回到确定状态。
     * 启动入库的基础设施（连接、工具来源、工作流、远程 Agent）也在快照树里，会一并重建。
     */
    public static void deleteTenantAssets(PlatformServices services, String tenantId) {
        services.db()
                .inTransaction(() -> services.db()
                        .jdbc()
                        .sql("DELETE FROM assets WHERE namespace LIKE 'tenant.' || ? || '.%'")
                        .param(tenantId)
                        .update());
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        Path dataDir = DataDirs.resolve();
        reset(dataDir, Clock.systemUTC());
        System.out.println("平台演示数据已重置：" + dataDir);
    }
}

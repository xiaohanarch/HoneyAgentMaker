package dev.eab.platform.cli;

import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.hub.HistorySimulator;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * pnpm data:simulate：向平台数据库灌入过去 30 天的模拟运行指标和流程、业务指标（演示租户 t001），让治理中心的图表有内容。
 * 可以重复执行，每次替换上一次的模拟数据；服务运行中也可以执行。
 */
public final class SimulateHistory {

    private SimulateHistory() {}

    public static void main(String[] args) {
        Ansi.quietLogs();
        Path dataDir = DataDirs.resolve();
        try (PlatformDb db = PlatformDb.open(dataDir.resolve("platform.db").toString())) {
            HistorySimulator.Report[] report = new HistorySimulator.Report[1];
            db.inTransaction(() -> report[0] = HistorySimulator.generate(db.jdbc(), "t001", LocalDate.now(ZoneOffset.UTC)));
            System.out.println("已生成过去 30 天的模拟数据：运行指标 " + report[0].runs() + " 条，流程与业务指标 " + report[0].metricPoints() + " 个点（" + dataDir + "）");
        }
    }
}

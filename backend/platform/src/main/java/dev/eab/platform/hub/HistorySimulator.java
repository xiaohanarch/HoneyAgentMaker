package dev.eab.platform.hub;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * “生成历史数据”（主提示词第 11 节）：灌入过去 30 天的模拟运行指标和流程、业务指标，让治理中心的图表有内容。
 * 用固定的随机种子，结果可复现；重复执行时先删掉上一次的模拟数据，真实运行产生的指标不受影响。
 */
public final class HistorySimulator {

    private HistorySimulator() {}

    static final int DAYS = 30;
    static final long SEED = 20261016L;

    /**
     * 一个 Agent 的模拟参数。
     *
     * @param latencyMs 典型耗时
     * @param confirmRate 需要人工确认的运行比例（写操作类 Agent 较高）
     */
    record Profile(
            String agent, String user, int dailyRuns, long latencyMs, double failRate, double confirmRate, int tools) {}

    static final List<Profile> PROFILES = List.of(
            new Profile("vendor.pur/supplier-performance-analyst", "zhangmin", 14, 9_000, 0.04, 0.0, 4),
            new Profile("vendor.pur/purchase-order-clerk", "zhangmin", 6, 3_500, 0.03, 0.85, 2),
            new Profile("vendor.pur/procurement-policy-advisor", "liqiang", 8, 4_000, 0.02, 0.0, 2),
            new Profile("vendor.scm/inventory-risk-analyst", "sunkg", 10, 8_000, 0.05, 0.15, 4),
            new Profile("vendor.fin/transaction-anomaly-analyst", "wangfang", 9, 11_000, 0.06, 0.2, 3),
            new Profile("vendor.fin/period-close-checker", "wangfang", 4, 5_000, 0.03, 0.0, 1));

    /** 流程指标与业务结果：起点、终点（30 天内线性改善加一点噪声） */
    record Trend(String layer, String metric, String agent, double start, double end, double noise) {}

    static final List<Trend> TRENDS = List.of(
            new Trend("process", "negotiation_adoption_rate", "vendor.pur/supplier-performance-analyst", 48, 71, 4),
            new Trend("process", "anomaly_close_hours", "vendor.fin/transaction-anomaly-analyst", 38, 21, 3),
            new Trend("process", "on_time_delivery_rate", null, 82, 90, 1.5),
            new Trend("process", "close_readiness_rate", "vendor.fin/period-close-checker", 62, 88, 3),
            new Trend("business", "negotiation_savings", null, 2.1, 5.8, 0.8),
            new Trend("business", "inventory_turnover_days", "vendor.scm/inventory-risk-analyst", 58, 47, 1.5),
            new Trend("business", "slow_moving_ratio", "vendor.scm/inventory-risk-analyst", 8.6, 6.1, 0.4));

    public record Report(int runs, int metricPoints) {}

    public static Report generate(JdbcClient jdbc, String tenantId, LocalDate today) {
        Random random = new Random(SEED + tenantId.hashCode());
        jdbc.sql("DELETE FROM run_metrics WHERE tenant_id = ? AND simulated = 1")
                .param(tenantId)
                .update();
        jdbc.sql("DELETE FROM business_metrics WHERE tenant_id = ?")
                .param(tenantId)
                .update();
        int runs = 0;
        int points = 0;
        for (int d = DAYS; d >= 1; d--) {
            LocalDate day = today.minusDays(d);
            boolean weekend = day.getDayOfWeek().getValue() >= 6;
            double progress = (DAYS - d) / (double) DAYS;
            for (Profile p : PROFILES) {
                int count = Math.max(
                        0, (int) Math.round(p.dailyRuns() * (weekend ? 0.3 : 1) * (0.7 + 0.6 * random.nextDouble())));
                for (int i = 0; i < count; i++) {
                    insertRun(jdbc, tenantId, day, p, random, progress, runs++);
                }
            }
            for (Trend t : TRENDS) {
                double value = t.start() + (t.end() - t.start()) * progress + (random.nextGaussian() * t.noise());
                jdbc.sql(
                                "INSERT INTO business_metrics (tenant_id, day, layer, metric, agent, value) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(
                                tenantId,
                                day.toString(),
                                t.layer(),
                                t.metric(),
                                t.agent(),
                                Math.round(value * 10) / 10.0)
                        .update();
                points++;
            }
        }
        return new Report(runs, points);
    }

    private static void insertRun(
            JdbcClient jdbc, String tenantId, LocalDate day, Profile p, Random random, double progress, int n) {
        // 失败率随时间略降（标品和扩展在迭代），耗时服从偏态分布，少数长尾
        boolean failed = random.nextDouble() < p.failRate() * (1.2 - 0.5 * progress);
        double factor = Math.exp(random.nextGaussian() * 0.45);
        long latency = (long) (p.latencyMs() * factor);
        long in = 1_200 + random.nextInt(6_000);
        long out = 150 + random.nextInt(900);
        double cost = Math.round((in * 3 + out * 15) / 1_000_000.0 * 10_000) / 10_000.0;
        int tools = Math.max(1, p.tools() + random.nextInt(3) - 1);
        int toolErrors = failed ? 1 : (random.nextDouble() < 0.03 ? 1 : 0);
        int confirmations = random.nextDouble() < p.confirmRate() ? 1 : 0;
        String at = day.atTime(8 + random.nextInt(11), random.nextInt(60), random.nextInt(60))
                .toInstant(ZoneOffset.UTC)
                .toString();
        jdbc.sql(
                        "INSERT INTO run_metrics (run_id, tenant_id, agent, user_id, at, status, latency_ms, input_tokens, output_tokens,"
                                + " cost_usd, tool_calls, tool_errors, confirmations, simulated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)")
                .params(
                        "sim-" + tenantId + "-" + day + "-" + n,
                        tenantId,
                        p.agent(),
                        p.user(),
                        at,
                        failed ? "failed" : "completed",
                        latency,
                        in,
                        out,
                        cost,
                        tools,
                        toolErrors,
                        confirmations)
                .update();
    }
}

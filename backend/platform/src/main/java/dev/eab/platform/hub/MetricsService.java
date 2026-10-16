package dev.eab.platform.hub;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeStores.JdbcTraceStore;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.SpanRecord.SpanStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 运行指标（主提示词第 11 节）。每次运行在 run_metrics 里有一行：由运行记录和追踪汇总（耗时、token、成本、工具调用、
 * 工具出错、人工确认），查询前补齐还没汇总的运行；“生成历史数据”灌入的模拟记录标记为 simulated。
 * 指标分三层：技术指标（目标完成率、工具调用正确率、人工介入率、失败 trace、P50/P95 延迟、token 与成本），
 * 流程指标与业务结果（演示里是模拟数据，记在 business_metrics），以及岗位助手的 KPI 卡片。
 */
public final class MetricsService {

    /** 一行运行指标 */
    public record RunMetric(
            String runId,
            String tenantId,
            String agent,
            String userId,
            String at,
            String status,
            long latencyMs,
            long inputTokens,
            long outputTokens,
            double costUsd,
            int toolCalls,
            int toolErrors,
            int confirmations,
            boolean simulated) {}

    /**
     * 一个 Agent 的技术指标。
     *
     * @param toolCorrectness 工具调用正确率（评测口径）：最近一次评测的通过率，没有评测时为 null
     */
    public record AgentMetrics(
            String agent,
            String name,
            int runs,
            double completionRate,
            Double toolCorrectness,
            double interventionRate,
            int failedTraces,
            long p50Ms,
            long p95Ms,
            long inputTokens,
            long outputTokens,
            double costUsd) {}

    public record DailyPoint(String day, int runs, int failed, long p95Ms, double costUsd) {}

    public record SeriesPoint(String day, double value) {}

    public record Series(
            String metric, String label, String unit, String agent, List<SeriesPoint> points, Double latest) {}

    public record Kpi(
            String name,
            String displayName,
            String unit,
            Double target,
            String direction,
            Double value,
            Double previous) {}

    public record AssistantKpis(String ref, String name, List<Kpi> kpis) {}

    public record Overview(
            int days,
            List<AgentMetrics> agents,
            List<DailyPoint> daily,
            List<Series> process,
            List<Series> business,
            List<AssistantKpis> assistants) {}

    /** 流程指标与业务结果的展示名和单位 */
    static final Map<String, String[]> LABELS = Map.of(
            "negotiation_adoption_rate", new String[] {"谈判建议采纳率", "%"},
            "anomaly_close_hours", new String[] {"异常处理时长", "小时"},
            "negotiation_savings", new String[] {"谈判节省金额", "万元"},
            "inventory_turnover_days", new String[] {"库存周转天数", "天"},
            "on_time_delivery_rate", new String[] {"准时交付率", "%"},
            "close_readiness_rate", new String[] {"结账就绪率", "%"},
            "slow_moving_ratio", new String[] {"呆滞库存占比", "%"});

    private final PlatformServices services;
    private final JdbcTraceStore traces;
    private final JdbcClient jdbc;

    public MetricsService(PlatformServices services, JdbcTraceStore traces) {
        this.services = services;
        this.traces = traces;
        this.jdbc = services.db().jdbc();
    }

    /** 把已经结束、还没汇总的运行写进 run_metrics；返回补了几行 */
    public int collect() {
        List<Map<String, Object>> pending = jdbc.sql("SELECT r.* FROM runs r LEFT JOIN run_metrics m ON m.run_id = r.id"
                        + " WHERE m.run_id IS NULL AND r.status IN ('completed', 'failed')")
                .query()
                .listOfRows();
        for (Map<String, Object> run : pending)
            insert(summarize(run, traces.list(String.valueOf(run.get("trace_id")))));
        return pending.size();
    }

    static RunMetric summarize(Map<String, Object> run, List<SpanRecord> spans) {
        long latency = 0;
        try {
            latency = Instant.parse(String.valueOf(run.get("updated_at"))).toEpochMilli()
                    - Instant.parse(String.valueOf(run.get("created_at"))).toEpochMilli();
        } catch (RuntimeException e) {
            // 时间格式异常时记 0
        }
        long in = 0;
        long out = 0;
        int tools = 0;
        int toolErrors = 0;
        int confirmations = 0;
        for (SpanRecord s : spans) {
            in += number(s.attributes().get("gen_ai.usage.input_tokens"));
            out += number(s.attributes().get("gen_ai.usage.output_tokens"));
            if (s.kind() == SpanKind.tool || s.kind() == SpanKind.skill) {
                tools++;
                if (s.status() == SpanStatus.error
                        || Boolean.FALSE.equals(s.attributes().get("eab.tool.ok"))) toolErrors++;
            }
            if (s.kind() == SpanKind.confirmation) confirmations++;
        }
        double cost = 0;
        if (run.get("result") instanceof String result && !result.isBlank()) {
            Object c = Json.MAPPER.readValue(result, Map.class).get("costUsd");
            if (c instanceof Number n) cost = n.doubleValue();
        }
        return new RunMetric(
                String.valueOf(run.get("id")),
                String.valueOf(run.get("tenant_id")),
                String.valueOf(run.get("agent")).replaceAll("@.*$", ""),
                String.valueOf(run.get("user_id")),
                String.valueOf(run.get("created_at")),
                String.valueOf(run.get("status")),
                Math.max(0, latency),
                in,
                out,
                cost,
                tools,
                toolErrors,
                confirmations,
                false);
    }

    static long number(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }

    public void insert(RunMetric m) {
        jdbc.sql(
                        "INSERT OR IGNORE INTO run_metrics (run_id, tenant_id, agent, user_id, at, status, latency_ms, input_tokens,"
                                + " output_tokens, cost_usd, tool_calls, tool_errors, confirmations, simulated)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        m.runId(),
                        m.tenantId(),
                        m.agent(),
                        m.userId(),
                        m.at(),
                        m.status(),
                        m.latencyMs(),
                        m.inputTokens(),
                        m.outputTokens(),
                        m.costUsd(),
                        m.toolCalls(),
                        m.toolErrors(),
                        m.confirmations(),
                        m.simulated() ? 1 : 0)
                .update();
    }

    List<RunMetric> rows(String tenantId, String since) {
        return jdbc.sql("SELECT * FROM run_metrics WHERE tenant_id = ? AND at >= ? AND agent NOT LIKE 'platform/%'")
                .params(tenantId, since)
                .query((rs, i) -> new RunMetric(
                        rs.getString("run_id"),
                        rs.getString("tenant_id"),
                        rs.getString("agent"),
                        rs.getString("user_id"),
                        rs.getString("at"),
                        rs.getString("status"),
                        rs.getLong("latency_ms"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getDouble("cost_usd"),
                        rs.getInt("tool_calls"),
                        rs.getInt("tool_errors"),
                        rs.getInt("confirmations"),
                        rs.getInt("simulated") != 0))
                .list();
    }

    public Overview overview(String tenantId, int days) {
        collect();
        String since = LocalDate.now(services.clock().withZone(ZoneOffset.UTC))
                .minusDays(days)
                .toString();
        List<RunMetric> rows = rows(tenantId, since);
        Map<String, String> names = agentNames(tenantId);
        Map<String, List<RunMetric>> byAgent = new TreeMap<>();
        rows.forEach(
                r -> byAgent.computeIfAbsent(r.agent(), k -> new ArrayList<>()).add(r));
        List<AgentMetrics> agents = new ArrayList<>();
        byAgent.forEach((agent, list) -> agents.add(
                agentMetrics(agent, names.getOrDefault(agent, agent), list, toolCorrectness(tenantId, agent))));
        agents.sort(Comparator.comparingInt(AgentMetrics::runs).reversed());

        Map<String, List<RunMetric>> byDay = new TreeMap<>();
        rows.forEach(r -> byDay.computeIfAbsent(r.at().substring(0, 10), k -> new ArrayList<>())
                .add(r));
        List<DailyPoint> daily = new ArrayList<>();
        byDay.forEach((day, list) -> daily.add(new DailyPoint(
                day,
                list.size(),
                (int) list.stream().filter(r -> r.status().equals("failed")).count(),
                percentile(list.stream().map(RunMetric::latencyMs).toList(), 95),
                round(list.stream().mapToDouble(RunMetric::costUsd).sum(), 4))));
        return new Overview(
                days,
                agents,
                daily,
                series(tenantId, "process", since),
                series(tenantId, "business", since),
                assistants(tenantId, since));
    }

    static AgentMetrics agentMetrics(String agent, String name, List<RunMetric> list, Double toolCorrectness) {
        int runs = list.size();
        long completed =
                list.stream().filter(r -> r.status().equals("completed")).count();
        long intervened = list.stream().filter(r -> r.confirmations() > 0).count();
        return new AgentMetrics(
                agent,
                name,
                runs,
                round(runs == 0 ? 0 : (double) completed / runs, 3),
                toolCorrectness,
                round(runs == 0 ? 0 : (double) intervened / runs, 3),
                (int) (runs - completed),
                percentile(list.stream().map(RunMetric::latencyMs).toList(), 50),
                percentile(list.stream().map(RunMetric::latencyMs).toList(), 95),
                list.stream().mapToLong(RunMetric::inputTokens).sum(),
                list.stream().mapToLong(RunMetric::outputTokens).sum(),
                round(list.stream().mapToDouble(RunMetric::costUsd).sum(), 4));
    }

    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    /** 工具调用正确率（评测口径）：这个 Agent 最近一次评测的通过率 */
    Double toolCorrectness(String tenantId, String agent) {
        return jdbc.sql("SELECT total, passed FROM eval_runs WHERE tenant_id = ? AND target = ? AND total > 0"
                        + " ORDER BY started_at DESC LIMIT 1")
                .params(tenantId, agent)
                .query((rs, i) -> round((double) rs.getInt("passed") / rs.getInt("total"), 3))
                .optional()
                .orElse(null);
    }

    Map<String, String> agentNames(String tenantId) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Asset a : services.registry()
                .latestPublished(new AssetFilter("Agent", null, null, null, null), Viewer.tenant(tenantId))
                .assets()) {
            names.put(
                    a.metadata().namespace() + "/" + a.metadata().name(),
                    a.metadata().displayName() == null
                            ? a.metadata().name()
                            : a.metadata().displayName());
        }
        return names;
    }

    List<Series> series(String tenantId, String layer, String since) {
        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT day, metric, agent, value FROM business_metrics WHERE tenant_id = ? AND layer = ?"
                                + " AND day >= ? ORDER BY metric, day")
                .params(tenantId, layer, since)
                .query()
                .listOfRows();
        Map<String, List<SeriesPoint>> byMetric = new LinkedHashMap<>();
        Map<String, String> agents = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String metric = String.valueOf(r.get("metric"));
            byMetric.computeIfAbsent(metric, k -> new ArrayList<>())
                    .add(new SeriesPoint(String.valueOf(r.get("day")), ((Number) r.get("value")).doubleValue()));
            if (r.get("agent") != null) agents.put(metric, String.valueOf(r.get("agent")));
        }
        List<Series> result = new ArrayList<>();
        byMetric.forEach((metric, points) -> {
            String[] label = LABELS.getOrDefault(metric, new String[] {metric, ""});
            result.add(new Series(
                    metric,
                    label[0],
                    label[1],
                    agents.get(metric),
                    points,
                    points.isEmpty() ? null : points.getLast().value()));
        });
        return result;
    }

    /** 岗位助手的 KPI 卡片：取 business_metrics 里同名指标的最新值和一周前的值 */
    List<AssistantKpis> assistants(String tenantId, String since) {
        List<AssistantKpis> result = new ArrayList<>();
        Map<String, List<SeriesPoint>> values = new LinkedHashMap<>();
        for (Series s : series(tenantId, "process", since)) values.put(s.metric(), s.points());
        for (Series s : series(tenantId, "business", since)) values.put(s.metric(), s.points());
        var assets = services.registry()
                .latestPublished(new AssetFilter("Assistant", null, null, null, null), Viewer.tenant(tenantId))
                .assets();
        for (Asset a : assets) {
            if (!(a instanceof Asset.AssistantAsset assistant)
                    || assistant.spec().kpis() == null) continue;
            List<Kpi> kpis = new ArrayList<>();
            for (var k : assistant.spec().kpis()) {
                List<SeriesPoint> points = values.getOrDefault(k.name(), List.of());
                Double latest = points.isEmpty() ? null : points.getLast().value();
                Double previous =
                        points.size() > 7 ? points.get(points.size() - 8).value() : null;
                kpis.add(new Kpi(k.name(), k.displayName(), k.unit(), k.target(), k.direction(), latest, previous));
            }
            result.add(new AssistantKpis(
                    a.metadata().namespace() + "/" + a.metadata().name(),
                    a.metadata().displayName() == null
                            ? a.metadata().name()
                            : a.metadata().displayName(),
                    kpis));
        }
        return result;
    }
}

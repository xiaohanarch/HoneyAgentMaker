package dev.eab.erp.bo;

import static dev.eab.erp.bo.BoTypes.notFound;
import static dev.eab.erp.bo.PurchasingRoutes.contains;
import static dev.eab.erp.bo.PurchasingRoutes.equalsIfSet;
import static dev.eab.erp.bo.PurchasingRoutes.firstNonNull;
import static dev.eab.erp.bo.PurchasingRoutes.scoped;
import static dev.eab.erp.bo.PurchasingRoutes.with;

import dev.eab.common.IsoTime;
import dev.eab.common.JsNumbers;
import dev.eab.erp.ErpJson;
import dev.eab.erp.bo.BoTypes.Outcome;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.type.TypeReference;

/** 库存与财务域的 BO API，数据权限按公司主体过滤；物料主数据与跟进任务只做租户隔离 */
public final class InventoryFinanceRoutes {

    private final ErpDb db;
    private final Clock clock;

    public InventoryFinanceRoutes(ErpDb db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    public List<BoRoute> routes() {
        return List.of(
                materialList(),
                inventoryBalanceList(),
                stockMovementList(),
                apInvoiceList(),
                paymentPlanList(),
                anomalyList(),
                anomalyGet(),
                closeChecklist(),
                followupTaskCreate());
    }

    static List<String> invoiceIds(Map<String, Object> anomaly) {
        return ErpJson.MAPPER.readValue(Rows.str(anomaly, "invoiceIds"), new TypeReference<List<String>>() {});
    }

    private BoRoute materialList() {
        return BoRoute.get("material_list", "/bo/v1/materials")
                .summary("查询物料")
                .description("物料主数据与安全库存设置。物料是租户级主数据，不按公司主体过滤。")
                .query(Param.string("name"), Param.string("id"), Param.limit())
                .handler((ctx, in) -> {
                    var items =
                            Rows.list(db.jdbc(), "SELECT * FROM materials WHERE tenant_id = ?", ctx.tenantId()).stream()
                                    .filter(m -> equalsIfSet(m.get("id"), in.query("id"))
                                            && contains(m.get("name"), in.query("name")))
                                    .limit(in.limit())
                                    .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("id"), in.query("name"), "物料"),
                            "返回 " + items.size() + " 种物料");
                });
    }

    private BoRoute inventoryBalanceList() {
        return BoRoute.get("inventory_balance_list", "/bo/v1/inventory-balances")
                .summary("查询库存余额")
                .description("各仓库的库存余额、库龄与呆滞标识（none 正常、watch 库龄超过 90 天、slow 库龄超过 180 天）。"
                        + "belowSafetyStock=true 只返回低于安全库存的记录，按缺口占安全库存的比例从大到小排列。")
                .query(
                        Param.string("materialId"),
                        Param.enumOf("slowMoving", "none", "watch", "slow"),
                        Param.bool("belowSafetyStock"),
                        Param.limit())
                .handler((ctx, in) -> {
                    Map<String, Map<String, Object>> materials = new HashMap<>();
                    for (var m : Rows.list(db.jdbc(), "SELECT * FROM materials WHERE tenant_id = ?", ctx.tenantId())) {
                        materials.put(Rows.str(m, "id"), m);
                    }
                    Map<String, String> warehouses = new HashMap<>();
                    for (var w : Rows.list(db.jdbc(), "SELECT * FROM warehouses WHERE tenant_id = ?", ctx.tenantId())) {
                        warehouses.put(Rows.str(w, "id"), Rows.str(w, "name"));
                    }
                    Boolean below = in.queryBool("belowSafetyStock");
                    var items = scoped(db, ctx, "inventory_balances", "company_id", null).stream()
                            .map(b -> {
                                var material = materials.get(Rows.str(b, "materialId"));
                                Object safety = material == null ? 0L : material.get("safetyStock");
                                Map<String, Object> item = new LinkedHashMap<>(b);
                                item.put("materialName", material == null ? b.get("materialId") : material.get("name"));
                                item.put("unit", material == null ? "" : material.get("unit"));
                                item.put("safetyStock", safety);
                                item.put("belowSafetyStock", Rows.num(b, "quantity") < ((Number) safety).doubleValue());
                                item.put(
                                        "warehouseName",
                                        warehouses.getOrDefault(
                                                Rows.str(b, "warehouseId"), Rows.str(b, "warehouseId")));
                                item.put("companyName", Queries.companyName(ctx.tenantId(), Rows.str(b, "companyId")));
                                return item;
                            })
                            .filter(b -> equalsIfSet(b.get("materialId"), in.query("materialId"))
                                    && equalsIfSet(b.get("slowMoving"), in.query("slowMoving"))
                                    && (!Boolean.TRUE.equals(below) || (boolean) b.get("belowSafetyStock")))
                            .sorted(
                                    Boolean.TRUE.equals(below)
                                            ? Comparator.comparingDouble(InventoryFinanceRoutes::shortageRatio)
                                                    .reversed()
                                            : (x, y) -> 0)
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("materialId"), "库存余额"),
                            "返回 " + items.size() + " 条库存余额");
                });
    }

    /** 缺口占安全库存的比例：(安全库存 - 可用量) / 安全库存 */
    static double shortageRatio(Map<String, Object> balance) {
        double safety = ((Number) balance.get("safetyStock")).doubleValue();
        return safety <= 0 ? 0 : (safety - Rows.num(balance, "quantity")) / safety;
    }

    private BoRoute stockMovementList() {
        return BoRoute.get("stock_movement_list", "/bo/v1/stock-movements")
                .summary("查询出入库流水")
                .description("最近 90 天的出入库流水，direction 为 in 入库、out 出库。")
                .query(Param.string("materialId"), Param.limit())
                .handler((ctx, in) -> {
                    var items = scoped(db, ctx, "stock_movements", "company_id", "date DESC").stream()
                            .filter(m -> equalsIfSet(m.get("materialId"), in.query("materialId")))
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("materialId"), "出入库流水"),
                            "返回 " + items.size() + " 条流水");
                });
    }

    private BoRoute apInvoiceList() {
        return BoRoute.get("ap_invoice_list", "/bo/v1/ap-invoices")
                .summary("查询应付发票")
                .description("应付发票及三单匹配状态：matched 三单匹配、price_diff 价格差异、qty_diff 数量差异、unmatched 未匹配。金额币种见 currency。")
                .query(
                        Param.string("invoiceNo"),
                        Param.string("supplierName"),
                        Param.enumOf("matchStatus", "matched", "price_diff", "qty_diff", "unmatched"),
                        Param.limit())
                .handler((ctx, in) -> {
                    var names = Queries.supplierNames(db, ctx.tenantId());
                    var items = scoped(db, ctx, "ap_invoices", "company_id", null).stream()
                            .map(i -> with(
                                    i,
                                    "supplierName",
                                    names.getOrDefault(Rows.str(i, "supplierId"), Rows.str(i, "supplierId"))))
                            .filter(i -> equalsIfSet(i.get("id"), in.query("invoiceNo"))
                                    && equalsIfSet(i.get("matchStatus"), in.query("matchStatus"))
                                    && contains(i.get("supplierName"), in.query("supplierName")))
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("invoiceNo"), "应付发票"),
                            "返回 " + items.size() + " 张发票");
                });
    }

    private BoRoute paymentPlanList() {
        return BoRoute.get("payment_plan_list", "/bo/v1/payment-plans")
                .summary("查询付款计划")
                .description("付款计划：planned 待付、paid 已付、on_hold 暂停。")
                .query(Param.enumOf("status", "planned", "paid", "on_hold"), Param.limit())
                .handler((ctx, in) -> {
                    var items = scoped(db, ctx, "payment_plans", "company_id", null).stream()
                            .filter(p -> equalsIfSet(p.get("status"), in.query("status")))
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(ctx.listBody(items), "付款计划", "返回 " + items.size() + " 条付款计划");
                });
    }

    private BoRoute anomalyList() {
        return BoRoute.get("transaction_anomaly_list", "/bo/v1/transaction-anomalies")
                .summary("查询交易异常")
                .description("交易异常记录。severity：low 低、medium 中、high 高、critical 严重；anomalyType：duplicate_payment 重复付款、"
                        + "split_invoice 拆分发票、round_amount 整数金额、off_hours 非工作时间过账、reversal 冲销。period 为 YYYY-MM。")
                .query(
                        Param.string("severity").describe("逗号分隔，例如 high,critical"),
                        Param.string("period").matching("^\\d{4}-\\d{2}$", "期间格式为 YYYY-MM"),
                        Param.enumOf("status", "open", "closed"),
                        Param.limit())
                .handler((ctx, in) -> {
                    String severity = in.query("severity");
                    List<String> severities = severity == null
                            ? null
                            : Arrays.stream(severity.split(",", -1))
                                    .map(String::trim)
                                    .toList();
                    String period = in.query("period");
                    var items = scoped(db, ctx, "transaction_anomalies", "company_id", "amount DESC").stream()
                            .filter(a -> (severities == null || severities.contains(Rows.str(a, "severity")))
                                    && (period == null
                                            || Rows.str(a, "detectedAt").startsWith(period))
                                    && equalsIfSet(a.get("status"), in.query("status")))
                            .limit(in.limit())
                            .map(a -> {
                                Map<String, Object> item = new LinkedHashMap<>(a);
                                item.put("invoiceIds", invoiceIds(a));
                                item.put("companyName", Queries.companyName(ctx.tenantId(), Rows.str(a, "companyId")));
                                return item;
                            })
                            .toList();
                    return Outcome.ok(ctx.listBody(items), firstNonNull(period, "交易异常"), "返回 " + items.size() + " 条异常");
                });
    }

    private BoRoute anomalyGet() {
        return BoRoute.get("transaction_anomaly_get", "/bo/v1/transaction-anomalies/{anomalyId}")
                .summary("查询单条交易异常")
                .description("交易异常明细，附关联的应付发票。")
                .params(Param.string("anomalyId").describe("异常编号，例如 ANO-2026-0003"))
                .handler((ctx, in) -> {
                    String id = in.param("anomalyId");
                    var anomaly = Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM transaction_anomalies WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    id)
                            .filter(a -> ctx.inScope(a.get("companyId")))
                            .orElseThrow(() -> notFound("交易异常 " + id));
                    List<String> ids = invoiceIds(anomaly);
                    var invoices = ids.isEmpty()
                            ? List.<Map<String, Object>>of()
                            : Rows.list(
                                    db.jdbc(),
                                    "SELECT * FROM ap_invoices WHERE tenant_id = ? AND id IN " + Rows.in(ids.size()),
                                    Rows.params(ctx.tenantId(), ids));
                    Map<String, Object> item = new LinkedHashMap<>(anomaly);
                    item.put("invoiceIds", ids);
                    item.put("invoices", invoices);
                    item.put("companyName", Queries.companyName(ctx.tenantId(), Rows.str(anomaly, "companyId")));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("item", item);
                    body.put("filtered", ctx.scope().filtered());
                    body.put("visibleScope", ctx.scope().names());
                    return Outcome.ok(
                            body,
                            Rows.str(anomaly, "id"),
                            anomaly.get("anomalyType") + " " + JsNumbers.format(Rows.num(anomaly, "amount")) + " "
                                    + anomaly.get("currency"));
                });
    }

    private BoRoute closeChecklist() {
        return BoRoute.get("period_close_checklist", "/bo/v1/accounting-periods/{periodId}/close-checks")
                .summary("查询结账检查项")
                .description("会计期间的结账检查项（演示只覆盖资产核算产品 asset-accounting）。status：passed 已通过、failed 未通过、"
                        + "not_started 未开始；未通过时 blocker 说明阻塞原因。")
                .params(Param.string("periodId")
                        .matching("^\\d{4}-\\d{2}$|^current$", "期间格式为 YYYY-MM 或 current")
                        .describe("会计期间 YYYY-MM，或 current"))
                .query(Param.string("product").withDefault("asset-accounting"))
                .handler((ctx, in) -> {
                    String periodId = in.param("periodId").equals("current")
                            ? IsoTime.date(clock.instant()).substring(0, 7)
                            : in.param("periodId");
                    List<String> values = ctx.scope().values();
                    var periods = Rows.list(
                            db.jdbc(),
                            "SELECT * FROM accounting_periods WHERE tenant_id = ? AND id = ? AND company_id IN "
                                    + Rows.in(values.size()),
                            concat(ctx.tenantId(), periodId, values));
                    var items = Rows.list(
                                    db.jdbc(),
                                    "SELECT * FROM close_check_items WHERE tenant_id = ? AND period_id = ? AND product = ? AND company_id IN "
                                            + Rows.in(values.size()),
                                    concat(ctx.tenantId(), periodId, in.query("product"), values))
                            .stream()
                            .map(c -> with(
                                    c, "companyName", Queries.companyName(ctx.tenantId(), Rows.str(c, "companyId"))))
                            .toList();
                    long passed = items.stream()
                            .filter(c -> "passed".equals(c.get("status")))
                            .count();
                    Map<String, Object> body = new LinkedHashMap<>(ctx.listBody(items));
                    body.put("periods", periods);
                    body.put(
                            "readinessRate",
                            items.isEmpty()
                                    ? null
                                    : JsNumbers.normalize(Math.round((double) passed / items.size() * 1000) / 10.0));
                    return Outcome.ok(body, periodId, "检查项 " + items.size() + " 个，已通过 " + passed + " 个");
                });
    }

    private static Object[] concat(Object first, Object second, List<String> rest) {
        return Rows.params(
                first, Stream.concat(Stream.of(second), rest.stream()).toList());
    }

    private static Object[] concat(Object first, Object second, Object third, List<String> rest) {
        return Rows.params(
                first, Stream.concat(Stream.of(second, third), rest.stream()).toList());
    }

    private BoRoute followupTaskCreate() {
        return BoRoute.post("followup_task_create", "/bo/v1/followup-tasks")
                .summary("创建跟进任务")
                .description("为业务对象创建跟进任务并指派负责人。写操作级别 L2，支持 Idempotency-Key。")
                .body(
                        Param.string("title").minLength(1).mandatory(),
                        Param.string("assignee").minLength(1).mandatory().describe("负责人或责任部门"),
                        Param.string("relatedObject").describe("关联的单据编号"),
                        Param.date("dueDate"))
                .handler((ctx, in) -> {
                    long n = db.jdbc()
                                    .sql("SELECT COUNT(*) FROM followup_tasks WHERE tenant_id = ?")
                                    .param(ctx.tenantId())
                                    .query(Long.class)
                                    .single()
                            + 1;
                    Map<String, Object> task = new LinkedHashMap<>();
                    task.put("id", "TASK-2026-" + String.format("%04d", n));
                    task.put("tenantId", ctx.tenantId());
                    task.put("title", in.body("title"));
                    task.put("assignee", in.body("assignee"));
                    task.put("relatedObject", in.body("relatedObject"));
                    task.put("dueDate", in.body("dueDate"));
                    task.put("createdBy", ctx.user().id());
                    task.put("createdVia", ctx.agent());
                    task.put("createdAt", IsoTime.now(clock));
                    Rows.insert(db.jdbc(), "followup_tasks", task);
                    return new Outcome(
                            201,
                            Map.of("item", task),
                            firstNonNull(in.body("relatedObject"), (String) task.get("id")),
                            "创建跟进任务 " + task.get("id") + "，指派给 " + in.body("assignee"));
                });
    }
}

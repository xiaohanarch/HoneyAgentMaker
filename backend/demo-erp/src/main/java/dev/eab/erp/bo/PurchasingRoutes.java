package dev.eab.erp.bo;

import static dev.eab.erp.bo.BoTypes.notFound;

import dev.eab.common.IsoTime;
import dev.eab.common.JsNumbers;
import dev.eab.erp.bo.BoTypes.BoContext;
import dev.eab.erp.bo.BoTypes.BoError;
import dev.eab.erp.bo.BoTypes.Outcome;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 采购域的 BO API（主提示词 6.1 节），数据权限按采购组织过滤 */
public final class PurchasingRoutes {

    private final ErpDb db;
    private final Clock clock;

    public PurchasingRoutes(ErpDb db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** 按租户和数据权限范围查询一张表 */
    static List<Map<String, Object>> scoped(ErpDb db, BoContext ctx, String table, String column, String orderBy) {
        List<String> values = ctx.scope().values();
        String sql = "SELECT * FROM " + table + " WHERE tenant_id = ? AND " + column + " IN " + Rows.in(values.size())
                + (orderBy == null ? "" : " ORDER BY " + orderBy);
        return Rows.list(db.jdbc(), sql, Rows.params(ctx.tenantId(), values));
    }

    static boolean contains(Object text, String part) {
        return part == null || (text != null && text.toString().contains(part));
    }

    static boolean equalsIfSet(Object value, Object expected) {
        return expected == null || expected.equals(value);
    }

    static Map<String, Object> with(Map<String, Object> row, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.put(key, value);
        return copy;
    }

    static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        return null;
    }

    public List<BoRoute> routes() {
        return List.of(
                supplierList(),
                supplierGet(),
                rfqList(),
                purchaseOrderList(),
                purchaseOrderGet(),
                supplierAlternativeList(),
                requisitionDraftCreate(),
                changeDeliveryDate(),
                approveChange(),
                deliveryList());
    }

    private BoRoute supplierList() {
        return BoRoute.get("supplier_list", "/bo/v1/suppliers")
                .summary("查询供应商")
                .description("按名称（模糊匹配）、评级查询供应商。评级含义：A 战略供应商、B 合格供应商、C 观察期供应商。结果按当前用户的采购组织过滤。")
                .query(
                        Param.string("name").describe("供应商名称，模糊匹配，例如 百润"),
                        Param.enumOf("rating", "A", "B", "C"),
                        Param.limit())
                .handler((ctx, in) -> {
                    var rows = scoped(db, ctx, "suppliers", "purchasing_org", null).stream()
                            .filter(s -> contains(s.get("name"), in.query("name"))
                                    && equalsIfSet(s.get("rating"), in.query("rating")))
                            .limit(in.limit())
                            .map(s -> with(
                                    s,
                                    "purchasingOrgName",
                                    Queries.orgName(ctx.tenantId(), Rows.str(s, "purchasingOrg"))))
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(rows), firstNonNull(in.query("name"), "供应商"), "返回 " + rows.size() + " 个供应商");
                });
    }

    private BoRoute supplierGet() {
        return BoRoute.get("supplier_get", "/bo/v1/suppliers/{supplierId}")
                .summary("查询单个供应商")
                .description("供应商主数据、评级与合同条款（价格、质保月数、交期天数、付款条件）。")
                .params(Param.string("supplierId").describe("供应商编号，例如 S-0001"))
                .handler((ctx, in) -> {
                    String id = in.param("supplierId");
                    var supplier = Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM suppliers WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    id)
                            .filter(s -> ctx.inScope(s.get("purchasingOrg")))
                            .orElseThrow(() -> notFound("供应商 " + id));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put(
                            "item",
                            with(
                                    supplier,
                                    "purchasingOrgName",
                                    Queries.orgName(ctx.tenantId(), Rows.str(supplier, "purchasingOrg"))));
                    body.put("filtered", ctx.scope().filtered());
                    body.put("visibleScope", ctx.scope().names());
                    return Outcome.ok(body, Rows.str(supplier, "id"), Rows.str(supplier, "name"));
                });
    }

    private BoRoute rfqList() {
        return BoRoute.get("rfq_list", "/bo/v1/rfqs")
                .summary("查询询价单与报价")
                .description("按供应商查询询价单，按发出时间倒序。每张询价单附报价行，以及最低报价与目标价的差异率（语义包指标 quote_price_variance）。")
                .query(Param.string("supplierId"), Param.string("supplierName"), Param.limit())
                .handler((ctx, in) -> {
                    var names = Queries.supplierNames(db, ctx.tenantId());
                    var rows = scoped(db, ctx, "rfqs", "purchasing_org", "issued_at DESC").stream()
                            .filter(r -> equalsIfSet(r.get("supplierId"), in.query("supplierId"))
                                    && contains(
                                            names.getOrDefault(Rows.str(r, "supplierId"), ""),
                                            in.query("supplierName")))
                            .limit(in.limit())
                            .toList();
                    List<String> ids = rows.stream().map(r -> Rows.str(r, "id")).toList();
                    var quotes = ids.isEmpty()
                            ? List.<Map<String, Object>>of()
                            : Rows.list(
                                    db.jdbc(),
                                    "SELECT * FROM quotations WHERE tenant_id = ? AND rfq_id IN " + Rows.in(ids.size()),
                                    Rows.params(ctx.tenantId(), ids));
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (var r : rows) {
                        var lines = quotes.stream()
                                .filter(q -> r.get("id").equals(q.get("rfqId")))
                                .sorted(Comparator.comparingDouble(q -> Rows.num(q, "lineNo")))
                                .toList();
                        Double lowest = lines.isEmpty()
                                ? null
                                : lines.stream()
                                        .mapToDouble(q -> Rows.num(q, "unitPrice"))
                                        .min()
                                        .getAsDouble();
                        double target = Rows.num(r, "targetPrice");
                        Map<String, Object> item = new LinkedHashMap<>(r);
                        item.put(
                                "supplierName",
                                names.getOrDefault(Rows.str(r, "supplierId"), Rows.str(r, "supplierId")));
                        item.put(
                                "quotationLines",
                                lines.stream()
                                        .map(q -> {
                                            Map<String, Object> line = new LinkedHashMap<>();
                                            line.put("lineNo", q.get("lineNo"));
                                            line.put("quantity", q.get("quantity"));
                                            line.put("unitPrice", q.get("unitPrice"));
                                            return line;
                                        })
                                        .toList());
                        item.put("lowestPrice", lowest == null ? null : JsNumbers.normalize(lowest));
                        item.put(
                                "priceVariance",
                                lowest == null
                                        ? null
                                        : JsNumbers.normalize(Math.round((lowest - target) / target * 10_000) / 100.0));
                        items.add(item);
                    }
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("supplierName"), in.query("supplierId"), "询价单"),
                            "返回 " + items.size() + " 张询价单");
                });
    }

    private BoRoute purchaseOrderList() {
        return BoRoute.get("purchase_order_list", "/bo/v1/purchase-orders")
                .summary("查询采购订单")
                .description("按供应商、状态、逾期天数查询采购订单。逾期天数口径：计划交付日期到实际交付日期（未交付则到今天）的自然日差，取订单行最大值。")
                .query(
                        Param.string("supplierId"),
                        Param.string("supplierName"),
                        Param.enumOf("status", "open", "partially_delivered", "delivered"),
                        Param.integer("overdueDaysGt", 0, null).describe("只返回逾期天数大于该值的订单"),
                        Param.limit())
                .handler((ctx, in) -> {
                    var names = Queries.supplierNames(db, ctx.tenantId());
                    var rows = scoped(db, ctx, "purchase_orders", "purchasing_org", null).stream()
                            .filter(po -> equalsIfSet(po.get("supplierId"), in.query("supplierId"))
                                    && equalsIfSet(po.get("status"), in.query("status"))
                                    && contains(
                                            names.getOrDefault(Rows.str(po, "supplierId"), ""),
                                            in.query("supplierName")))
                            .toList();
                    var status = Queries.deliveryStatus(
                            db,
                            ctx.tenantId(),
                            rows.stream().map(r -> Rows.str(r, "id")).toList(),
                            clock.instant());
                    Long overdueDaysGt = in.queryLong("overdueDaysGt");
                    var items = rows.stream()
                            .map(po -> {
                                var s = status.get(Rows.str(po, "id"));
                                Map<String, Object> item = new LinkedHashMap<>(po);
                                item.put(
                                        "supplierName",
                                        names.getOrDefault(Rows.str(po, "supplierId"), Rows.str(po, "supplierId")));
                                item.put(
                                        "purchasingOrgName",
                                        Queries.orgName(ctx.tenantId(), Rows.str(po, "purchasingOrg")));
                                item.put("overdueDays", s == null ? 0L : s.overdueDays());
                                item.put("nextPlannedDate", s == null ? null : s.nextPlannedDate());
                                return item;
                            })
                            .filter(po -> overdueDaysGt == null || (long) po.get("overdueDays") > overdueDaysGt)
                            .sorted(Comparator.comparingLong(po -> -(long) po.get("overdueDays")))
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("supplierName"), "采购订单"),
                            "返回 " + items.size() + " 张订单");
                });
    }

    private BoRoute purchaseOrderGet() {
        return BoRoute.get("purchase_order_get", "/bo/v1/purchase-orders/{poId}")
                .summary("查询单张采购订单")
                .description("订单头、订单行与交付进度，每个订单行带已交付数量与未交付数量，用于评估缺口。逾期口径与采购订单查询一致。")
                .params(Param.string("poId").describe("采购订单号，例如 PO-2026-0117"))
                .handler((ctx, in) -> {
                    String poId = in.param("poId");
                    var po = Queries.findPurchaseOrder(db, ctx.tenantId(), poId)
                            .filter(p -> ctx.inScope(p.get("purchasingOrg")))
                            .orElseThrow(() -> notFound("采购订单 " + poId));
                    var names = Queries.supplierNames(db, ctx.tenantId());
                    Map<String, Map<String, Object>> materials = new LinkedHashMap<>();
                    for (var m : Rows.list(db.jdbc(), "SELECT * FROM materials WHERE tenant_id = ?", ctx.tenantId())) {
                        materials.put(Rows.str(m, "id"), m);
                    }
                    var lines = Rows.list(
                            db.jdbc(),
                            "SELECT * FROM po_lines WHERE tenant_id = ? AND po_id = ? ORDER BY line_no",
                            ctx.tenantId(),
                            poId);
                    var deliveries = Rows.list(
                            db.jdbc(),
                            "SELECT * FROM deliveries WHERE tenant_id = ? AND po_id = ?",
                            ctx.tenantId(),
                            poId);
                    List<Map<String, Object>> lineItems = new ArrayList<>();
                    for (var line : lines) {
                        double deliveredQty = deliveries.stream()
                                .filter(d -> line.get("id").equals(d.get("poLineId")))
                                .mapToDouble(d -> Rows.num(d, "quantity"))
                                .sum();
                        var material = materials.get(Rows.str(line, "materialId"));
                        Map<String, Object> lineItem = new LinkedHashMap<>(line);
                        lineItem.put("materialName", material == null ? line.get("materialId") : material.get("name"));
                        lineItem.put("unit", material == null ? "" : material.get("unit"));
                        lineItem.put("deliveredQuantity", JsNumbers.normalize(Math.round(deliveredQty * 100) / 100.0));
                        lineItem.put(
                                "remainingQuantity",
                                JsNumbers.normalize(
                                        Math.round((Rows.num(line, "quantity") - deliveredQty) * 100) / 100.0));
                        lineItems.add(lineItem);
                    }
                    var s = Queries.deliveryStatus(db, ctx.tenantId(), List.of(poId), clock.instant())
                            .get(poId);
                    Map<String, Object> item = new LinkedHashMap<>(po);
                    item.put(
                            "supplierName", names.getOrDefault(Rows.str(po, "supplierId"), Rows.str(po, "supplierId")));
                    item.put("purchasingOrgName", Queries.orgName(ctx.tenantId(), Rows.str(po, "purchasingOrg")));
                    item.put("overdueDays", s == null ? 0L : s.overdueDays());
                    item.put("nextPlannedDate", s == null ? null : s.nextPlannedDate());
                    item.put("lines", lineItems);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("item", item);
                    body.put("filtered", ctx.scope().filtered());
                    body.put("visibleScope", ctx.scope().names());
                    return Outcome.ok(body, poId, "订单 " + poId + "，" + lineItems.size() + " 个订单行");
                });
    }

    private BoRoute supplierAlternativeList() {
        return BoRoute.get("supplier_alternative_list", "/bo/v1/supplier-alternatives")
                .summary("查询物料的备选供应商")
                .description("每个物料已认证的备选供应商，带协议单价、现货数量与备货周期，按当前用户的采购组织过滤。"
                        + "stockQtyGte 传需要的数量时只返回现货足够的备选；结果按现货从多到少、单价从低到高排列。")
                .query(
                        Param.string("materialId").describe("物料编号，例如 M-0007"),
                        Param.string("materialName").describe("物料名称，模糊匹配"),
                        Param.number("stockQtyGte", 0.0, null).describe("只返回现货数量不小于该值的备选"),
                        Param.limit())
                .handler((ctx, in) -> {
                    Map<String, Map<String, Object>> suppliers = new LinkedHashMap<>();
                    for (var s : Rows.list(db.jdbc(), "SELECT * FROM suppliers WHERE tenant_id = ?", ctx.tenantId())) {
                        suppliers.put(Rows.str(s, "id"), s);
                    }
                    Map<String, Map<String, Object>> materials = new LinkedHashMap<>();
                    for (var m : Rows.list(db.jdbc(), "SELECT * FROM materials WHERE tenant_id = ?", ctx.tenantId())) {
                        materials.put(Rows.str(m, "id"), m);
                    }
                    Double stockQtyGte = in.queryNumber("stockQtyGte");
                    var items = scoped(db, ctx, "supplier_alternatives", "purchasing_org", null).stream()
                            .map(a -> {
                                var supplier = suppliers.get(Rows.str(a, "supplierId"));
                                var material = materials.get(Rows.str(a, "materialId"));
                                Map<String, Object> item = new LinkedHashMap<>(a);
                                item.put("supplierName", supplier == null ? a.get("supplierId") : supplier.get("name"));
                                item.put("supplierRating", supplier == null ? null : supplier.get("rating"));
                                item.put("materialName", material == null ? a.get("materialId") : material.get("name"));
                                item.put(
                                        "purchasingOrgName",
                                        Queries.orgName(ctx.tenantId(), Rows.str(a, "purchasingOrg")));
                                return item;
                            })
                            .filter(a -> equalsIfSet(a.get("materialId"), in.query("materialId"))
                                    && contains(a.get("materialName"), in.query("materialName")))
                            .filter(a -> stockQtyGte == null || Rows.num(a, "stockQty") >= stockQtyGte)
                            .sorted(Comparator.comparingDouble((Map<String, Object> a) -> Rows.num(a, "stockQty"))
                                    .reversed()
                                    .thenComparingDouble(a -> Rows.num(a, "unitPrice")))
                            .limit(in.limit())
                            .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("materialId"), in.query("materialName"), "备选供应商"),
                            "返回 " + items.size() + " 条备选供应商");
                });
    }

    private BoRoute requisitionDraftCreate() {
        return BoRoute.post("purchase_requisition_draft_create", "/bo/v1/purchase-requisitions")
                .summary("创建请购单草稿")
                .description("从物料的备选供应商中选中一家，生成一张请购单草稿（状态 draft，线下审批后才能转采购订单）。"
                        + "供应商必须是该物料的备选供应商，单价按备选记录里的协议价，金额必须等于数量×单价。写操作级别 L2，支持 Idempotency-Key。")
                .body(
                        Param.string("supplierId").mandatory().describe("备选供应商编号，例如 S-0009"),
                        Param.string("materialId").mandatory().describe("物料编号，例如 M-0007"),
                        Param.number("quantity", 0.01, null).mandatory().describe("请购数量"),
                        Param.number("unitPrice", 0.01, null).mandatory().describe("协议单价，与备选供应商查询一致"),
                        Param.number("amount", 0.01, null).mandatory().describe("金额，必须等于数量×单价"),
                        Param.string("sourcePoId").describe("来源采购订单号，例如 PO-2026-0117"),
                        Param.string("note").describe("备注"))
                .handler((ctx, in) -> {
                    String supplierId = in.body("supplierId");
                    String materialId = in.body("materialId");
                    var supplier = Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM suppliers WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    supplierId)
                            .filter(s -> ctx.inScope(s.get("purchasingOrg")))
                            .orElseThrow(() -> notFound("供应商 " + supplierId));
                    Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM materials WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    materialId)
                            .orElseThrow(() -> notFound("物料 " + materialId));
                    var alternative = Rows.one(
                            db.jdbc(),
                            "SELECT * FROM supplier_alternatives WHERE tenant_id = ? AND material_id = ? AND supplier_id = ?",
                            ctx.tenantId(),
                            materialId,
                            supplierId);
                    if (alternative.isEmpty()) {
                        throw new BoError(
                                422,
                                "NOT_ALTERNATIVE",
                                "供应商 " + supplierId + " 不是物料 " + materialId + " 的认证备选供应商",
                                "先用备选供应商查询该物料的认证备选，从中选择");
                    }
                    double quantity = in.bodyNumber("quantity");
                    double unitPrice = in.bodyNumber("unitPrice");
                    double amount = in.bodyNumber("amount");
                    double listPrice = Rows.num(alternative.get(), "unitPrice");
                    if (Math.abs(unitPrice - listPrice) >= 0.01) {
                        throw new BoError(
                                422,
                                "PRICE_MISMATCH",
                                "单价 " + JsNumbers.format(unitPrice) + " 与备选供应商 " + supplierId + " 的协议价 "
                                        + JsNumbers.format(listPrice) + " 不一致",
                                "按备选供应商查询里返回的协议价下单");
                    }
                    if (Math.abs(quantity * unitPrice - amount) >= 0.01) {
                        throw new BoError(
                                422,
                                "INVALID_AMOUNT",
                                "金额 " + JsNumbers.format(amount) + " 与数量×单价 "
                                        + JsNumbers.format(Math.round(quantity * unitPrice * 100) / 100.0) + " 不一致",
                                "核对数量、单价与金额");
                    }
                    long n = db.jdbc()
                                    .sql("SELECT COUNT(*) FROM purchase_requisitions WHERE tenant_id = ?")
                                    .param(ctx.tenantId())
                                    .query(Long.class)
                                    .single()
                            + 1;
                    Map<String, Object> requisition = new LinkedHashMap<>();
                    requisition.put("id", "PR-2026-" + String.format("%04d", n));
                    requisition.put("tenantId", ctx.tenantId());
                    requisition.put("materialId", materialId);
                    requisition.put("supplierId", supplierId);
                    requisition.put("purchasingOrg", supplier.get("purchasingOrg"));
                    requisition.put("sourcePoId", in.body("sourcePoId"));
                    requisition.put("quantity", quantity);
                    requisition.put("unitPrice", unitPrice);
                    requisition.put("amount", amount);
                    requisition.put("currency", "CNY");
                    requisition.put("status", "draft");
                    requisition.put("note", in.body("note"));
                    requisition.put("requestedBy", ctx.user().id());
                    requisition.put("requestedVia", ctx.agent());
                    requisition.put("createdAt", IsoTime.now(clock));
                    Rows.insert(db.jdbc(), "purchase_requisitions", requisition);
                    return new Outcome(
                            201,
                            Map.of("item", requisition),
                            (String) requisition.get("id"),
                            "创建请购单草稿 " + requisition.get("id") + "：物料 " + materialId + "，供应商 " + supplier.get("name")
                                    + "，金额 " + JsNumbers.format(amount) + " CNY");
                });
    }

    private BoRoute changeDeliveryDate() {
        return BoRoute.post(
                        "purchase_order_change_delivery_date", "/bo/v1/purchase-orders/{poId}/delivery-date-changes")
                .summary("提交交期变更")
                .description("为采购订单提交一张交期变更单（状态为已提交），审批通过后才改写订单交期。写操作级别 L2，支持 Idempotency-Key。")
                .params(Param.string("poId").describe("采购订单号，例如 PO-2026-0117"))
                .body(
                        Param.date("newDate").mandatory().describe("新的计划交期"),
                        Param.string("reason").minLength(1).mandatory().describe("变更原因"))
                .handler((ctx, in) -> {
                    String poId = in.param("poId");
                    var po = Queries.findPurchaseOrder(db, ctx.tenantId(), poId)
                            .filter(p -> ctx.inScope(p.get("purchasingOrg")))
                            .orElseThrow(() -> notFound("采购订单 " + poId));
                    String newDate = in.body("newDate");
                    if (newDate.compareTo(IsoTime.date(clock.instant())) <= 0) {
                        throw new BoError(422, "INVALID_DATE", "新交期必须晚于今天", "选择今天之后的日期");
                    }
                    String id = Rows.str(po, "id");
                    // 同一张订单只能有一张待审批的变更单，避免审批时按过期的原交期改写
                    var pending = Rows.one(
                            db.jdbc(),
                            "SELECT * FROM delivery_date_changes WHERE tenant_id = ? AND po_id = ? AND status = 'submitted'",
                            ctx.tenantId(),
                            id);
                    if (pending.isPresent()) {
                        throw new BoError(
                                409,
                                "CHANGE_PENDING",
                                id + " 已有待审批的交期变更单 " + Rows.str(pending.get(), "id"),
                                "等待该变更单审批后再提交");
                    }
                    var s = Queries.deliveryStatus(db, ctx.tenantId(), List.of(id), clock.instant())
                            .get(id);
                    String oldDate = s == null ? null : s.nextPlannedDate();
                    if (oldDate == null) {
                        throw new BoError(409, "ALREADY_DELIVERED", id + " 已全部交付，不能修改交期", "确认订单号");
                    }
                    long n = db.jdbc()
                                    .sql("SELECT COUNT(*) FROM delivery_date_changes WHERE tenant_id = ?")
                                    .param(ctx.tenantId())
                                    .query(Long.class)
                                    .single()
                            + 1;
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("id", "DC-2026-" + String.format("%04d", n));
                    change.put("tenantId", ctx.tenantId());
                    change.put("poId", id);
                    change.put("oldDate", oldDate);
                    change.put("newDate", newDate);
                    change.put("reason", in.body("reason"));
                    change.put("status", "submitted");
                    change.put("requestedBy", ctx.user().id());
                    change.put("requestedVia", ctx.agent());
                    change.put("approvedBy", null);
                    change.put("approvedVia", null);
                    change.put("createdAt", IsoTime.now(clock));
                    change.put("approvedAt", null);
                    Rows.insert(db.jdbc(), "delivery_date_changes", change);
                    return new Outcome(
                            201,
                            Map.of("item", change),
                            id + " " + change.get("id"),
                            "交期 " + oldDate + " → " + newDate + "，变更单 " + change.get("id") + " 待审批");
                });
    }

    private BoRoute approveChange() {
        return BoRoute.post("purchase_order_change_approve", "/bo/v1/delivery-date-changes/{changeId}/approve")
                .summary("审批交期变更")
                .description("审批交期变更单并改写订单未交付行的计划交期。职责分离：提交人不能审批自己提交的变更单。写操作级别 L2。")
                .params(Param.string("changeId").describe("交期变更单号，例如 DC-2026-0001"))
                .handler((ctx, in) -> {
                    String changeId = in.param("changeId");
                    var change = Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM delivery_date_changes WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    changeId)
                            .orElseThrow(() -> notFound("交期变更单 " + changeId));
                    String poId = Rows.str(change, "poId");
                    Queries.findPurchaseOrder(db, ctx.tenantId(), poId)
                            .filter(p -> ctx.inScope(p.get("purchasingOrg")))
                            .orElseThrow(() -> notFound("交期变更单 " + changeId));
                    String id = Rows.str(change, "id");
                    if (!"submitted".equals(change.get("status"))) {
                        throw new BoError(
                                409, "INVALID_STATE", "变更单 " + id + " 当前状态为 " + change.get("status"), "只能审批已提交的变更单");
                    }
                    // ERP 自身的职责分离检查：即使平台侧漏了，这里也拦得住
                    if (ctx.user().id().equals(change.get("requestedBy"))) {
                        throw new BoError(
                                403,
                                "SOD_VIOLATION",
                                "变更单 " + id + " 由 " + ctx.user().name() + " 提交，同一用户不能审批（职责分离）",
                                "请由其他有审批权限的同事审批");
                    }
                    String now = IsoTime.now(clock);
                    db.jdbc()
                            .sql(
                                    "UPDATE delivery_date_changes SET status = 'approved', approved_by = ?, approved_via = ?, approved_at = ?"
                                            + " WHERE tenant_id = ? AND id = ?")
                            .params(ctx.user().id(), ctx.agent(), now, ctx.tenantId(), id)
                            .update();
                    var approved = Rows.one(
                                    db.jdbc(),
                                    "SELECT * FROM delivery_date_changes WHERE tenant_id = ? AND id = ?",
                                    ctx.tenantId(),
                                    id)
                            .orElseThrow();
                    db.jdbc()
                            .sql(
                                    "UPDATE po_lines SET planned_delivery_date = ? WHERE tenant_id = ? AND po_id = ? AND planned_delivery_date = ?")
                            .params(change.get("newDate"), ctx.tenantId(), poId, change.get("oldDate"))
                            .update();
                    return Outcome.ok(
                            Map.of("item", approved),
                            poId + " " + id,
                            "审批通过，" + poId + " 交期改为 " + change.get("newDate"));
                });
    }

    private BoRoute deliveryList() {
        return BoRoute.get("delivery_list", "/bo/v1/deliveries")
                .summary("查询交付记录")
                .description("查询交付记录（计划交付日期、实际交付日期、数量），未交付的订单行不出现在这里，用采购订单查询看逾期。")
                .query(
                        Param.string("poId"),
                        Param.string("supplierName"),
                        Param.date("from"),
                        Param.date("to"),
                        Param.limit())
                .handler((ctx, in) -> {
                    var names = Queries.supplierNames(db, ctx.tenantId());
                    Map<String, Map<String, Object>> pos = new LinkedHashMap<>();
                    for (var po : scoped(db, ctx, "purchase_orders", "purchasing_org", null))
                        pos.put(Rows.str(po, "id"), po);
                    String from = in.query("from");
                    String to = in.query("to");
                    var items =
                            Rows.list(db.jdbc(), "SELECT * FROM deliveries WHERE tenant_id = ?", ctx.tenantId())
                                    .stream()
                                    .filter(d -> pos.containsKey(Rows.str(d, "poId")))
                                    .filter(d -> equalsIfSet(d.get("poId"), in.query("poId"))
                                            && (from == null
                                                    || Rows.str(d, "plannedDate")
                                                                    .compareTo(from)
                                                            >= 0)
                                            && (to == null
                                                    || Rows.str(d, "plannedDate")
                                                                    .compareTo(to)
                                                            <= 0))
                                    .map(d -> with(
                                            d,
                                            "supplierName",
                                            names.getOrDefault(
                                                    Rows.str(pos.get(Rows.str(d, "poId")), "supplierId"), "")))
                                    .filter(d -> contains(d.get("supplierName"), in.query("supplierName")))
                                    .limit(in.limit())
                                    .toList();
                    return Outcome.ok(
                            ctx.listBody(items),
                            firstNonNull(in.query("poId"), in.query("supplierName"), "交付记录"),
                            "返回 " + items.size() + " 条交付记录");
                });
    }
}

package dev.eab.erp.seed;

import static dev.eab.erp.seed.SeedNames.ANOMALY_TYPES;
import static dev.eab.erp.seed.SeedNames.CLOSE_CHECKS_ASSET_ACCOUNTING;

import dev.eab.common.IsoTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成一个租户的演示数据（主提示词 6.2 节）。同一个种子、同一天生成的结果完全相同，并且与 TS 版逐条一致：
 * 随机数的取用顺序与 TS 版的求值顺序保持一致，测试用 TS 版导出的黄金样本比对。
 * 规模：约 40 个供应商、200 张采购订单、500 条交付记录、100 种物料、30 条交易异常。t001 的故事数据：
 * 供应商“百润精密”属于华东，采购订单 PO-2026-0117 计划交期在 120 天前且未交付；华东 26 个供应商，华南 14 个；
 * 本月有几条金额超过 50 万元的高风险交易异常（故事 B）；资产核算本期有 3 个结账检查项未通过。
 */
public final class SeedGenerator {

    private SeedGenerator() {}

    /** 演示故事里固定的对象 */
    public static final class Story {
        private Story() {}

        public static final String SUPPLIER_ID = "S-0001";
        public static final String SUPPLIER_NAME = "百润精密";
        public static final String PO_ID = "PO-2026-0117";
        public static final int PO_OVERDUE_DAYS = 120;
        public static final String MATERIAL_ID = "M-0007";
        public static final String LATEST_RFQ_ID = "RFQ-2026-0058";

        /** 故事物料 M-0007 的备选供应商：华东 S-0009 现货充足但单价高（2000 件需 112,000 元），华南 S-0033 华东用户看不到 */
        public static final String ALTERNATIVE_SUPPLIER_ID = "S-0009";

        public static final double ALTERNATIVE_UNIT_PRICE = 56.0;
        public static final int ALTERNATIVE_STOCK_QTY = 2600;
    }

    /** 表名与生成顺序；值是驼峰字段名的行 */
    public static final List<String> TABLES = List.of(
            "suppliers",
            "rfqs",
            "quotations",
            "purchaseOrders",
            "poLines",
            "deliveries",
            "materials",
            "warehouses",
            "inventoryBalances",
            "stockMovements",
            "apInvoices",
            "paymentPlans",
            "transactionAnomalies",
            "accountingPeriods",
            "closeCheckItems",
            "supplierAlternatives");

    private record Org(String id, int suppliers, List<String> cities, String company) {}

    private record Warehouse(String name, String company) {}

    private record Profile(
            long seed, List<String> prefixes, List<Org> orgs, List<Warehouse> warehouses, boolean story) {}

    private static final Map<String, Profile> PROFILES = Map.of(
            "t001",
            new Profile(
                    20261001,
                    SeedNames.SUPPLIER_PREFIX_T001,
                    List.of(
                            new Org("P-EAST", 26, SeedNames.CITIES_EAST, "C100"),
                            new Org("P-SOUTH", 14, SeedNames.CITIES_SOUTH, "C200")),
                    List.of(
                            new Warehouse("本部一号仓", "C100"),
                            new Warehouse("本部二号仓", "C100"),
                            new Warehouse("华南仓", "C200")),
                    true),
            "t002",
            new Profile(
                    20261002,
                    SeedNames.SUPPLIER_PREFIX_T002,
                    List.of(
                            new Org("P-EAST", 22, SeedNames.CITIES_EAST, "C100"),
                            new Org("P-NORTH", 18, SeedNames.CITIES_NORTH, "C100")),
                    List.of(new Warehouse("总部仓", "C100")),
                    false));

    static String pad(int n, int width) {
        return String.format("%0" + width + "d", n);
    }

    private static Map<String, Object> row(String tenantId, Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        for (int i = 0; i < kv.length; i += 2) row.put((String) kv[i], kv[i + 1]);
        return row;
    }

    public static Map<String, List<Map<String, Object>>> generate(String tenantId, Instant today) {
        Profile profile = PROFILES.get(tenantId);
        if (profile == null) throw new IllegalArgumentException("没有租户 " + tenantId + " 的种子配置");
        SeedRandom rnd = new SeedRandom(profile.seed());
        java.util.function.IntFunction<String> day = offset -> IsoTime.date(today.plus(Duration.ofDays(offset)));
        String t = tenantId;

        // ── 物料与仓库 ──────────────────────────────────────────────────────
        List<Map<String, Object>> materials = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String name = rnd.pick(SeedNames.MATERIAL_SPECS) + rnd.pick(SeedNames.MATERIAL_TYPES);
            String category = rnd.pick(SeedNames.MATERIAL_TYPES);
            String unit = rnd.pick(SeedNames.UNITS);
            int safetyStock = rnd.nextInt(5, 50) * 10;
            materials.add(row(
                    t,
                    "id",
                    "M-" + pad(i + 1, 4),
                    "name",
                    name,
                    "category",
                    category,
                    "unit",
                    unit,
                    "safetyStock",
                    safetyStock));
        }
        if (profile.story()) {
            materials.set(
                    6,
                    row(
                            t,
                            "id",
                            Story.MATERIAL_ID,
                            "name",
                            "精密级连接器",
                            "category",
                            "连接器",
                            "unit",
                            "件",
                            "safetyStock",
                            800));
        }
        List<Map<String, Object>> warehouses = new ArrayList<>();
        for (int i = 0; i < profile.warehouses().size(); i++) {
            var w = profile.warehouses().get(i);
            warehouses.add(row(t, "id", "W-" + pad(i + 1, 2), "name", w.name(), "companyId", w.company()));
        }

        // ── 供应商 ─────────────────────────────────────────────────────────
        Set<String> usedNames = new HashSet<>();
        List<Map<String, Object>> suppliers = new ArrayList<>();
        for (Org org : profile.orgs()) {
            for (int k = 0; k < org.suppliers(); k++) {
                String name;
                while (true) {
                    name = rnd.pick(profile.prefixes())
                            + rnd.pick(SeedNames.SUPPLIER_MIDDLE)
                            + rnd.pick(SeedNames.SUPPLIER_SUFFIX);
                    if (!usedNames.contains(name) && !name.equals(Story.SUPPLIER_NAME)) {
                        usedNames.add(name);
                        break;
                    }
                }
                String rating = rnd.pick(List.of("A", "B", "B", "C"));
                String city = rnd.pick(org.cities());
                String priceTerms = rnd.pick(SeedNames.PRICE_TERMS);
                int warranty = rnd.pick(List.of(6, 12, 18, 24));
                int leadTime = rnd.nextInt(7, 45);
                String payment = rnd.pick(SeedNames.PAYMENT_TERMS);
                suppliers.add(row(
                        t,
                        "id",
                        "S-" + pad(suppliers.size() + 1, 4),
                        "name",
                        name,
                        "rating",
                        rating,
                        "purchasingOrg",
                        org.id(),
                        "city",
                        city,
                        "priceTerms",
                        priceTerms,
                        "warrantyMonths",
                        warranty,
                        "leadTimeDays",
                        leadTime,
                        "paymentTerms",
                        payment));
            }
        }
        if (profile.story()) {
            suppliers.set(
                    0,
                    row(
                            t,
                            "id",
                            Story.SUPPLIER_ID,
                            "name",
                            Story.SUPPLIER_NAME,
                            "rating",
                            "B",
                            "purchasingOrg",
                            "P-EAST",
                            "city",
                            "苏州",
                            "priceTerms",
                            "年度框架价，季度调价",
                            "warrantyMonths",
                            12,
                            "leadTimeDays",
                            21,
                            "paymentTerms",
                            "月结 60 天"));
        }

        // ── 采购订单、订单行、交付 ──────────────────────────────────────────
        List<Map<String, Object>> purchaseOrders = new ArrayList<>();
        List<Map<String, Object>> poLines = new ArrayList<>();
        List<Map<String, Object>> deliveries = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            String id = "PO-2026-" + pad(i, 4);
            boolean isStory = profile.story() && id.equals(Story.PO_ID);
            // 百润精密每 9 张订单出现一次，交付表现偏差
            Map<String, Object> supplier =
                    isStory || (profile.story() && i % 9 == 0) ? suppliers.get(0) : rnd.pick(suppliers);
            int createdOffset = isStory ? -160 : -rnd.nextInt(10, 210);
            int lineCount = isStory ? 1 : rnd.nextInt(1, 3);
            double total = 0;
            int delivered = 0;
            for (int n = 1; n <= lineCount; n++) {
                Map<String, Object> material = isStory ? materials.get(6) : rnd.pick(materials);
                int quantity = isStory ? 2000 : rnd.nextInt(10, 200) * 10;
                double unitPrice = isStory ? 45.8 : rnd.amount(5, 800);
                int plannedOffset = isStory ? -Story.PO_OVERDUE_DAYS : createdOffset + rnd.nextInt(15, 60);
                String lineId = id + "-" + n;
                total += quantity * unitPrice;
                poLines.add(row(
                        t,
                        "id",
                        lineId,
                        "poId",
                        id,
                        "lineNo",
                        n,
                        "materialId",
                        material.get("id"),
                        "quantity",
                        quantity,
                        "unitPrice",
                        unitPrice,
                        "plannedDeliveryDate",
                        day.apply(plannedOffset)));

                boolean due = plannedOffset < 0;
                double late = supplier.get("id").equals(Story.SUPPLIER_ID) ? 0.5 : 0.2;
                if (isStory || !due || rnd.chance(late * 0.6)) continue; // 未交付
                delivered += 1;
                int parts = rnd.chance(0.6) ? 2 : 1;
                for (int p = 1; p <= parts; p++) {
                    int actualOffset =
                            Math.min(-1, plannedOffset + (rnd.chance(late) ? rnd.nextInt(3, 40) : rnd.nextInt(-5, 2)));
                    long first = Math.round(quantity * 0.6);
                    long qty = parts == 1 ? quantity : p == 1 ? first : quantity - first;
                    deliveries.add(row(
                            t,
                            "id",
                            "DL-" + pad(deliveries.size() + 1, 5),
                            "poId",
                            id,
                            "poLineId",
                            lineId,
                            "plannedDate",
                            day.apply(plannedOffset),
                            "actualDate",
                            day.apply(actualOffset),
                            "quantity",
                            qty));
                }
            }
            String purchasingOrg = (String) supplier.get("purchasingOrg");
            String company = profile.orgs().stream()
                    .filter(o -> o.id().equals(purchasingOrg))
                    .findFirst()
                    .map(Org::company)
                    .orElse("C100");
            purchaseOrders.add(row(
                    t,
                    "id",
                    id,
                    "supplierId",
                    supplier.get("id"),
                    "purchasingOrg",
                    purchasingOrg,
                    "companyId",
                    company,
                    "createdAt",
                    day.apply(createdOffset),
                    "status",
                    delivered == 0 ? "open" : delivered == lineCount ? "delivered" : "partially_delivered",
                    "currency",
                    "CNY",
                    "totalAmount",
                    Math.round(total * 100) / 100.0));
        }

        // ── 询价与报价 ──────────────────────────────────────────────────────
        List<Map<String, Object>> rfqs = new ArrayList<>();
        List<Map<String, Object>> quotations = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            String id = "RFQ-2026-" + pad(i, 4);
            boolean isStory = profile.story() && id.equals(Story.LATEST_RFQ_ID);
            Map<String, Object> supplier = isStory ? suppliers.get(0) : rnd.pick(suppliers);
            double target = isStory ? 42 : rnd.amount(10, 500);
            Object materialId =
                    isStory ? Story.MATERIAL_ID : rnd.pick(materials).get("id");
            // 百润精密最近一次询价在 12 天前，排在它的其他询价都更早
            String issuedAt = day.apply(isStory ? -12 : -rnd.nextInt(20, 300));
            String status = isStory ? "quoted" : rnd.pick(List.of("open", "quoted", "closed"));
            rfqs.add(row(
                    t,
                    "id",
                    id,
                    "supplierId",
                    supplier.get("id"),
                    "purchasingOrg",
                    supplier.get("purchasingOrg"),
                    "materialId",
                    materialId,
                    "issuedAt",
                    issuedAt,
                    "status",
                    status,
                    "targetPrice",
                    target,
                    "currency",
                    "CNY"));
            List<Double> prices = new ArrayList<>();
            if (isStory) {
                prices.addAll(List.of(46.5, 45.8, 47.2));
            } else {
                int count = rnd.nextInt(1, 3);
                for (int k = 0; k < count; k++) prices.add(rnd.amount(target * 0.9, target * 1.2));
            }
            for (int n = 0; n < prices.size(); n++) {
                quotations.add(row(
                        t,
                        "id",
                        id + "-Q" + (n + 1),
                        "rfqId",
                        id,
                        "lineNo",
                        n + 1,
                        "quantity",
                        rnd.nextInt(5, 50) * 100,
                        "unitPrice",
                        prices.get(n)));
            }
        }

        // ── 库存 ───────────────────────────────────────────────────────────
        List<Map<String, Object>> inventoryBalances = new ArrayList<>();
        for (Map<String, Object> material : materials) {
            List<Map<String, Object>> selected = new ArrayList<>();
            for (Map<String, Object> w : warehouses) if (rnd.chance(0.6)) selected.add(w);
            for (Map<String, Object> warehouse : selected) {
                boolean isStory = profile.story()
                        && material.get("id").equals(Story.MATERIAL_ID)
                        && warehouse.get("id").equals("W-01");
                int quantity = isStory ? 120 : rnd.nextInt(0, 120) * 10;
                int ageDays = isStory ? 30 : rnd.nextInt(5, 420);
                double value = Math.round(quantity * rnd.amount(3, 300) * 100) / 100.0;
                inventoryBalances.add(row(
                        t,
                        "id",
                        "IB-" + pad(inventoryBalances.size() + 1, 4),
                        "materialId",
                        material.get("id"),
                        "warehouseId",
                        warehouse.get("id"),
                        "companyId",
                        warehouse.get("companyId"),
                        "quantity",
                        quantity,
                        "value",
                        value,
                        "ageDays",
                        ageDays,
                        "slowMoving",
                        ageDays > 180 ? "slow" : ageDays > 90 ? "watch" : "none"));
            }
        }
        if (profile.story()
                && inventoryBalances.stream()
                        .noneMatch(b -> b.get("materialId").equals(Story.MATERIAL_ID)
                                && b.get("warehouseId").equals("W-01"))) {
            inventoryBalances.add(row(
                    t,
                    "id",
                    "IB-STORY",
                    "materialId",
                    Story.MATERIAL_ID,
                    "warehouseId",
                    "W-01",
                    "companyId",
                    "C100",
                    "quantity",
                    120,
                    "value",
                    5496,
                    "ageDays",
                    30,
                    "slowMoving",
                    "none"));
        }
        List<Map<String, Object>> stockMovements = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> warehouse = rnd.pick(warehouses);
            Object materialId = rnd.pick(materials).get("id");
            String direction = rnd.pick(List.of("in", "out"));
            int quantity = rnd.nextInt(1, 50) * 10;
            String date = day.apply(-rnd.nextInt(0, 90));
            stockMovements.add(row(
                    t,
                    "id",
                    "SM-" + pad(i + 1, 5),
                    "materialId",
                    materialId,
                    "warehouseId",
                    warehouse.get("id"),
                    "companyId",
                    warehouse.get("companyId"),
                    "direction",
                    direction,
                    "quantity",
                    quantity,
                    "date",
                    date));
        }

        // ── 财务 ───────────────────────────────────────────────────────────
        List<Map<String, Object>> invoicedPos = purchaseOrders.stream()
                .filter(po -> !po.get("status").equals("open"))
                .limit(150)
                .toList();
        List<Map<String, Object>> apInvoices = new ArrayList<>();
        for (int i = 0; i < invoicedPos.size(); i++) {
            var po = invoicedPos.get(i);
            String invoiceDate = day.apply(-rnd.nextInt(1, 120));
            String matchStatus =
                    rnd.pick(List.of("matched", "matched", "matched", "price_diff", "qty_diff", "unmatched"));
            apInvoices.add(row(
                    t,
                    "id",
                    "INV-2026-" + pad(i + 1, 4),
                    "supplierId",
                    po.get("supplierId"),
                    "poId",
                    po.get("id"),
                    "companyId",
                    po.get("companyId"),
                    "invoiceDate",
                    invoiceDate,
                    "amount",
                    po.get("totalAmount"),
                    "currency",
                    "CNY",
                    "matchStatus",
                    matchStatus));
        }
        List<Map<String, Object>> paymentPlans = new ArrayList<>();
        for (int i = 0; i < apInvoices.size(); i++) {
            var inv = apInvoices.get(i);
            String dueDate = day.apply(rnd.nextInt(-30, 60));
            String status = rnd.pick(List.of("planned", "planned", "paid", "on_hold"));
            paymentPlans.add(row(
                    t,
                    "id",
                    "PP-2026-" + pad(i + 1, 4),
                    "invoiceId",
                    inv.get("id"),
                    "companyId",
                    inv.get("companyId"),
                    "dueDate",
                    dueDate,
                    "amount",
                    inv.get("amount"),
                    "currency",
                    inv.get("currency"),
                    "status",
                    status));
        }

        LocalDate todayDate = LocalDate.ofInstant(today, ZoneOffset.UTC);
        Instant monthStart =
                todayDate.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int daysIntoMonth =
                (int) Math.max(0, Math.round((today.toEpochMilli() - monthStart.toEpochMilli()) / 86_400_000.0));
        record StoryAnomaly(String type, String severity, double amount, String description) {}
        Map<Integer, StoryAnomaly> storyAnomalies = Map.of(
                3, new StoryAnomaly("duplicate_payment", "critical", 1_280_000, "同一供应商、同一金额在 3 天内付款两次"),
                11, new StoryAnomaly("split_invoice", "high", 620_000, "同一订单下 4 张发票金额均略低于 20 万元审批阈值"),
                21, new StoryAnomaly("off_hours", "high", 86_500, "凌晨 2 点过账的大额付款凭证"),
                26, new StoryAnomaly("reversal", "high", 158_000, "上月计提、本月冲销的跨期冲销凭证"));
        List<Map<String, Object>> transactionAnomalies = new ArrayList<>();
        for (int n = 1; n <= 30; n++) {
            StoryAnomaly story = profile.story() ? storyAnomalies.get(n) : null;
            var kind = rnd.pick(ANOMALY_TYPES);
            Object companyId = story != null ? "C100" : rnd.pick(warehouses).get("companyId");
            String severity =
                    story != null ? story.severity() : rnd.pick(List.of("low", "low", "medium", "medium", "high"));
            double amount = story != null ? story.amount() : rnd.amount(2_000, 300_000);
            String detectedAt = day.apply(story != null ? -rnd.nextInt(0, daysIntoMonth) : -rnd.nextInt(0, 90));
            String status = story != null ? "open" : rnd.pick(List.of("open", "open", "closed"));
            String invoiceIds =
                    apInvoices.isEmpty() ? "[]" : "[\"" + rnd.pick(apInvoices).get("id") + "\"]";
            transactionAnomalies.add(row(
                    t,
                    "id",
                    "ANO-2026-" + pad(n, 4),
                    "companyId",
                    companyId,
                    "anomalyType",
                    story != null ? story.type() : kind.type(),
                    "severity",
                    severity,
                    "amount",
                    amount,
                    "currency",
                    "CNY",
                    "detectedAt",
                    detectedAt,
                    "status",
                    status,
                    "description",
                    story != null ? story.description() : kind.label() + "：规则引擎在过账后识别",
                    "invoiceIds",
                    invoiceIds));
        }

        String period = todayDate.toString().substring(0, 7);
        String previous = IsoTime.date(monthStart.minus(Duration.ofDays(1))).substring(0, 7);
        Set<String> companies = new LinkedHashSet<>();
        for (var w : warehouses) companies.add((String) w.get("companyId"));
        List<Map<String, Object>> accountingPeriods = new ArrayList<>();
        List<Map<String, Object>> closeCheckItems = new ArrayList<>();
        for (String companyId : companies) {
            accountingPeriods.add(row(t, "id", previous, "companyId", companyId, "status", "closed"));
            accountingPeriods.add(row(t, "id", period, "companyId", companyId, "status", "closing"));
        }
        for (String companyId : companies) {
            for (int i = 0; i < CLOSE_CHECKS_ASSET_ACCOUNTING.size(); i++) {
                var check = CLOSE_CHECKS_ASSET_ACCOUNTING.get(i);
                boolean failed = profile.story() && companyId.equals("C100") && check.fail() != null;
                String status =
                        failed ? "failed" : i == CLOSE_CHECKS_ASSET_ACCOUNTING.size() - 1 ? "not_started" : "passed";
                closeCheckItems.add(row(
                        t,
                        "id",
                        "CC-" + companyId + "-" + period + "-" + pad(i + 1, 2),
                        "periodId",
                        period,
                        "companyId",
                        companyId,
                        "product",
                        "asset-accounting",
                        "name",
                        check.name(),
                        "status",
                        status,
                        "owner",
                        check.owner(),
                        "blocker",
                        failed ? check.fail() : null));
            }
        }

        // ── 备选供应商 ─────────────────────────────────────────────────────
        // 追加在最后：随机数的取用顺序不影响前面的表，黄金样本只新增这一张表。
        // 每个物料有 0 到 2 个认证备选供应商；故事物料 M-0007 固定三家：
        // 华东 S-0009 现货充足但单价高（56.0 元，2000 件需 112,000 元，超过 10 万元的确认阈值）、
        // 华东 S-0014 便宜但现货只有 600 件、华南 S-0033 现货充足但华东用户看不到（数据权限过滤）
        record StoryAlt(String id, String supplierId, double unitPrice, int stockQty, int leadTimeDays) {}
        List<Map<String, Object>> supplierAlternatives = new ArrayList<>();
        for (Map<String, Object> material : materials) {
            if (profile.story() && Story.MATERIAL_ID.equals(material.get("id"))) continue;
            Set<String> usedSuppliers = new HashSet<>();
            int count = rnd.nextInt(0, 3);
            for (int k = 0; k < count; k++) {
                Map<String, Object> supplier = rnd.pick(suppliers);
                if (!usedSuppliers.add((String) supplier.get("id"))) continue;
                supplierAlternatives.add(row(
                        t,
                        "id",
                        "ALT-" + pad(supplierAlternatives.size() + 1, 4),
                        "materialId",
                        material.get("id"),
                        "supplierId",
                        supplier.get("id"),
                        "purchasingOrg",
                        supplier.get("purchasingOrg"),
                        "unitPrice",
                        rnd.amount(5, 800),
                        "stockQty",
                        rnd.nextInt(0, 120) * 10,
                        "leadTimeDays",
                        rnd.nextInt(2, 30)));
            }
        }
        if (profile.story()) {
            for (StoryAlt alt : List.of(
                    new StoryAlt("ALT-STORY-1", "S-0009", Story.ALTERNATIVE_UNIT_PRICE, Story.ALTERNATIVE_STOCK_QTY, 3),
                    new StoryAlt("ALT-STORY-2", "S-0014", 47.2, 600, 7),
                    new StoryAlt("ALT-STORY-3", "S-0033", 51.0, 4000, 10))) {
                Map<String, Object> supplier = suppliers.stream()
                        .filter(s -> alt.supplierId().equals(s.get("id")))
                        .findFirst()
                        .orElseThrow();
                supplierAlternatives.add(row(
                        t,
                        "id",
                        alt.id(),
                        "materialId",
                        Story.MATERIAL_ID,
                        "supplierId",
                        alt.supplierId(),
                        "purchasingOrg",
                        supplier.get("purchasingOrg"),
                        "unitPrice",
                        alt.unitPrice(),
                        "stockQty",
                        alt.stockQty(),
                        "leadTimeDays",
                        alt.leadTimeDays()));
            }
        }

        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        data.put("suppliers", suppliers);
        data.put("rfqs", rfqs);
        data.put("quotations", quotations);
        data.put("purchaseOrders", purchaseOrders);
        data.put("poLines", poLines);
        data.put("deliveries", deliveries);
        data.put("materials", materials);
        data.put("warehouses", warehouses);
        data.put("inventoryBalances", inventoryBalances);
        data.put("stockMovements", stockMovements);
        data.put("apInvoices", apInvoices);
        data.put("paymentPlans", paymentPlans);
        data.put("transactionAnomalies", transactionAnomalies);
        data.put("accountingPeriods", accountingPeriods);
        data.put("closeCheckItems", closeCheckItems);
        data.put("supplierAlternatives", supplierAlternatives);
        return data;
    }
}

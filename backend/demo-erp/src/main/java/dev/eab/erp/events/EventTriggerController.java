package dev.eab.erp.events;

import dev.eab.common.IsoTime;
import dev.eab.erp.ErpServices;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务事件（主提示词 6.4 节）：演示控制台手工触发，DemoERP 通过 webhook 推送给平台。
 * 只接受带内部调用密钥的请求（演示控制台经平台转发）。
 */
@RestController
public class EventTriggerController {

    public static final List<String> BUSINESS_EVENTS = List.of(
            "delivery.overdue",
            "inventory.below_safety_stock",
            "transaction.anomaly_created",
            "period.close_check_failed");

    /** 各事件的默认载荷，取自演示故事数据 */
    private static final Map<String, Map<String, Object>> DEFAULT_PAYLOADS = defaults();

    private static Map<String, Map<String, Object>> defaults() {
        Map<String, Object> overdue = new LinkedHashMap<>();
        overdue.put("poId", "PO-2026-0117");
        overdue.put("supplierId", "S-0001");
        overdue.put("supplierName", "百润精密");
        overdue.put("overdueDays", 120);
        overdue.put("materialId", "M-0007");
        Map<String, Object> belowSafety = new LinkedHashMap<>();
        belowSafety.put("materialId", "M-0007");
        belowSafety.put("materialName", "精密级连接器");
        belowSafety.put("warehouseId", "W-01");
        belowSafety.put("quantity", 120);
        belowSafety.put("safetyStock", 800);
        Map<String, Object> anomaly = new LinkedHashMap<>();
        anomaly.put("anomalyId", "ANO-2026-0003");
        anomaly.put("severity", "critical");
        anomaly.put("amount", 1_280_000);
        anomaly.put("currency", "CNY");
        Map<String, Object> closeCheck = new LinkedHashMap<>();
        closeCheck.put("product", "asset-accounting");
        closeCheck.put("companyId", "C100");
        closeCheck.put("failed", 3);
        return Map.of(
                "delivery.overdue", overdue,
                "inventory.below_safety_stock", belowSafety,
                "transaction.anomaly_created", anomaly,
                "period.close_check_failed", closeCheck);
    }

    private final ErpServices services;

    public EventTriggerController(ErpServices services) {
        this.services = services;
    }

    private boolean isInternal(String header) {
        String secret = services.internalSecret();
        return header != null
                && secret != null
                && MessageDigest.isEqual(
                        header.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Object> badRequest(String detail) {
        return ResponseEntity.status(400)
                .body(Map.of(
                        "error",
                        Map.of("code", "BAD_REQUEST", "reason", "请求参数不合法", "suggestion", "检查参数格式", "details", detail)));
    }

    @PostMapping("/demo/v1/events/trigger")
    public ResponseEntity<Object> trigger(
            @RequestHeader(value = "x-eab-internal-token", required = false) String token,
            @RequestBody(required = false) Map<String, Object> body) {
        // 与 TS 版一致：先校验请求体，再检查内部调用密钥
        if (body == null || !(body.get("type") instanceof String type) || !BUSINESS_EVENTS.contains(type)) {
            return badRequest("type 只能是 " + String.join("、", BUSINESS_EVENTS));
        }
        Object tenant = body.getOrDefault("tenantId", "t001");
        if (!(tenant instanceof String tenantId)) return badRequest("tenantId 必须是字符串");
        Object extra = body.get("payload");
        if (extra != null && !(extra instanceof Map<?, ?>)) return badRequest("payload 必须是对象");
        if (!isInternal(token)) {
            return ResponseEntity.status(401)
                    .body(Map.of(
                            "error",
                            Map.of("code", "INTERNAL_ONLY", "reason", "只接受内部调用", "suggestion", "通过平台的演示控制台触发")));
        }
        Map<String, Object> payload = new LinkedHashMap<>(DEFAULT_PAYLOADS.get(type));
        if (extra instanceof Map<?, ?> map) map.forEach((k, v) -> payload.put(String.valueOf(k), v));
        payload.put("occurredAt", IsoTime.now(services.clock()));
        services.link().publish(type, tenantId, payload);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("tenantId", tenantId);
        event.put("payload", payload);
        return ResponseEntity.status(202).body(Map.of("accepted", true, "event", event));
    }
}

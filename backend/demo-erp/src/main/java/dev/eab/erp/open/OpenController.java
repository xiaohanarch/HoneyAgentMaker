package dev.eab.erp.open;

import dev.eab.common.IsoTime;
import dev.eab.erp.ErpServices;
import dev.eab.erp.bo.Param;
import dev.eab.erp.bo.Queries;
import dev.eab.erp.db.Rows;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open API（主提示词 6.3 节）：给系统集成用，客户端凭证认证，只做租户级隔离，不做数据权限过滤。
 * 演示里作为反面对照：同样的问题走 Open API 会返回所有采购组织的数据，所以 Agent 必须走 BO API。
 */
@RestController
public class OpenController {

    private static final String DATA_SCOPE = "租户：全部数据（未按采购组织过滤）";

    private static final Map<String, Object> UNAUTHORIZED = Map.of(
            "error",
            Map.of(
                    "code", "CLIENT_AUTH_REQUIRED",
                    "reason", "Open API 只接受集成账号的客户端凭证令牌",
                    "suggestion", "用 client_credentials 换取令牌"));

    private record Client(String tenantId, String client) {}

    private final ErpServices services;

    public OpenController(ErpServices services) {
        this.services = services;
    }

    private Client authorize(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        try {
            Map<String, Object> claims = services.link().verify(header.substring(7), "demo-erp-open");
            if (!"client".equals(claims.get("kind")) || !(claims.get("tid") instanceof String tid)) return null;
            return new Client(tid, String.valueOf(claims.get("sub")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void logAccess(Client auth, String operation, int count) {
        String at = IsoTime.now(services.clock());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at);
        row.put("tenantId", auth.tenantId());
        row.put("api", "open");
        row.put("operation", operation);
        row.put("principal", auth.client());
        row.put("agent", null);
        row.put("mode", "integration");
        row.put("outcome", "allow");
        row.put("code", "OK");
        row.put("reason", "集成账号，仅租户隔离");
        row.put("dataScope", DATA_SCOPE);
        row.put("target", operation);
        Rows.insert(services.db().jdbc(), "access_log", row);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", at);
        payload.put("operation", "open:" + operation);
        payload.put("userId", auth.client());
        payload.put("agent", "");
        payload.put("mode", "integration");
        payload.put("outcome", "allow");
        payload.put("code", "OK");
        payload.put("reason", "集成账号调用 Open API，只做租户隔离");
        payload.put("dataScope", DATA_SCOPE);
        payload.put("target", operation);
        payload.put("resultSummary", "返回 " + count + " 条");
        services.link().publish("bo.access", auth.tenantId(), payload);
    }

    private static Map<String, Object> listBody(List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", items.size());
        body.put("filtered", false);
        body.put("visibleScope", List.of("全部"));
        return body;
    }

    @GetMapping("/open/v1/suppliers")
    public ResponseEntity<Object> suppliers(@RequestHeader(value = "authorization", required = false) String header) {
        Client auth = authorize(header);
        if (auth == null) return ResponseEntity.status(401).body(UNAUTHORIZED);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var s : Rows.list(services.db().jdbc(), "SELECT * FROM suppliers WHERE tenant_id = ?", auth.tenantId())) {
            Map<String, Object> item = new LinkedHashMap<>(s);
            item.put("purchasingOrgName", Queries.orgName(auth.tenantId(), Rows.str(s, "purchasingOrg")));
            items.add(item);
        }
        logAccess(auth, "suppliers", items.size());
        return ResponseEntity.ok(listBody(items));
    }

    @GetMapping("/open/v1/purchase-orders")
    public ResponseEntity<Object> purchaseOrders(
            @RequestHeader(value = "authorization", required = false) String header,
            @RequestParam(required = false) String overdueDaysGt) {
        List<Param.Problem> problems = new ArrayList<>();
        Object threshold = Param.integer("overdueDaysGt", 0, null).parse(overdueDaysGt, problems);
        if (!problems.isEmpty()) {
            return ResponseEntity.status(400)
                    .body(Map.of(
                            "error",
                            Map.of(
                                    "code",
                                    "BAD_REQUEST",
                                    "reason",
                                    "请求参数不合法",
                                    "suggestion",
                                    "检查参数格式",
                                    "details",
                                    problems)));
        }
        Client auth = authorize(header);
        if (auth == null) return ResponseEntity.status(401).body(UNAUTHORIZED);
        var db = services.db();
        var names = Queries.supplierNames(db, auth.tenantId());
        var rows = Rows.list(db.jdbc(), "SELECT * FROM purchase_orders WHERE tenant_id = ?", auth.tenantId());
        var status = Queries.deliveryStatus(
                db,
                auth.tenantId(),
                rows.stream().map(r -> Rows.str(r, "id")).toList(),
                services.clock().instant());
        var items = rows.stream()
                .map(po -> {
                    var s = status.get(Rows.str(po, "id"));
                    Map<String, Object> item = new LinkedHashMap<>(po);
                    item.put(
                            "supplierName", names.getOrDefault(Rows.str(po, "supplierId"), Rows.str(po, "supplierId")));
                    item.put("purchasingOrgName", Queries.orgName(auth.tenantId(), Rows.str(po, "purchasingOrg")));
                    item.put("overdueDays", s == null ? 0L : s.overdueDays());
                    return item;
                })
                .filter(po -> threshold == null || (long) po.get("overdueDays") > (long) threshold)
                .sorted(Comparator.comparingLong(po -> -(long) po.get("overdueDays")))
                .toList();
        logAccess(auth, "purchase-orders", items.size());
        return ResponseEntity.ok(listBody(items));
    }
}

package dev.eab.erp.pages;

import dev.eab.common.JsNumbers;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.directory.DemoDirectory.Tenant;
import dev.eab.erp.ErpServices;
import dev.eab.erp.bo.Queries;
import dev.eab.erp.db.Rows;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 极简的只读页面（主提示词 6.4、9.1.1 节导航型交互的深链接目标）。
 * 页面链接里的令牌由平台签发（受众 demo-erp-pages，有效期 10 分钟，带用户和租户），
 * 页面按这个用户的数据权限展示：没有令牌、令牌无效、或单据不在数据权限内，都看不到内容。
 */
@RestController
public class PageController {

    private static final MediaType HTML = new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8);

    private final ErpServices services;

    public PageController(ErpServices services) {
        this.services = services;
    }

    /** 转义 HTML 特殊字符 */
    public static String escape(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private record Row(String label, Object value) {}

    static String page(String title, List<Row> rows, String extra) {
        StringBuilder table = new StringBuilder();
        for (Row row : rows) {
            table.append("<tr><th>")
                    .append(escape(row.label()))
                    .append("</th><td>")
                    .append(escape(row.value()))
                    .append("</td></tr>");
        }
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="referrer" content="no-referrer">
                <title>%s · DemoERP</title>
                <style>body{font-family:system-ui,'PingFang SC','Microsoft YaHei',sans-serif;margin:0;background:#f4f5f7;color:#1f2430}
                header{background:#2b3440;color:#fff;padding:12px 24px;font-size:14px}main{max-width:880px;margin:24px auto;background:#fff;border:1px solid #dfe3e8;border-radius:8px;padding:24px}
                h1{font-size:20px;margin:0 0 16px}table{border-collapse:collapse;width:100%%}th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #eef0f3;font-size:14px}th{width:160px;color:#5b6472;font-weight:500}
                .note{margin-top:16px;font-size:12px;color:#7a8290}</style></head><body><header>DemoERP · 只读页面（演示用）</header>
                <main><h1>%s</h1><table>%s</table>%s
                <p class="note">本页面供统一入口的导航型交互跳转，数据均为虚构。</p></main></body></html>""".formatted(escape(title), escape(title), table, extra);
    }

    private Optional<DemoUser> viewer(String token) {
        if (token == null || token.isEmpty()) return Optional.empty();
        try {
            Map<String, Object> claims = services.link().verify(token, "demo-erp-pages");
            if (!"page".equals(claims.get("kind"))) return Optional.empty();
            return DemoDirectory.findUser(String.valueOf(claims.get("sub")))
                    .filter(u -> u.tenantId().equals(claims.get("tid")));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<String> html(int status, String body) {
        return ResponseEntity.status(status)
                .header("cache-control", "no-store")
                .contentType(HTML)
                .body(body);
    }

    private static ResponseEntity<String> denied() {
        return html(404, page("页面不存在或链接已过期", List.of(new Row("说明", "请从统一入口重新打开这个链接")), ""));
    }

    @GetMapping("/pages/suppliers/{id}")
    public ResponseEntity<String> supplier(
            @PathVariable String id, @RequestParam(name = "t", required = false) String token) {
        Optional<DemoUser> found = viewer(token);
        if (found.isEmpty()) return denied();
        DemoUser user = found.get();
        var supplier = Rows.one(
                        services.db().jdbc(),
                        "SELECT * FROM suppliers WHERE tenant_id = ? AND id = ?",
                        user.tenantId(),
                        id)
                .filter(s -> user.dataScope().purchasingOrgs().contains(Rows.str(s, "purchasingOrg")));
        if (supplier.isEmpty()) return denied();
        var s = supplier.get();
        return html(
                200,
                page(
                        "供应商 · " + s.get("name"),
                        List.of(
                                new Row("供应商编号", s.get("id")),
                                new Row(
                                        "所属租户",
                                        DemoDirectory.findTenant(user.tenantId())
                                                .map(Tenant::name)
                                                .orElse(null)),
                                new Row("采购组织", Queries.orgName(user.tenantId(), Rows.str(s, "purchasingOrg"))),
                                new Row("评级", s.get("rating")),
                                new Row("所在城市", s.get("city")),
                                new Row("价格条款", s.get("priceTerms")),
                                new Row("质保", s.get("warrantyMonths") + " 个月"),
                                new Row("约定交期", s.get("leadTimeDays") + " 天"),
                                new Row("付款条件", s.get("paymentTerms")),
                                new Row("查看人", user.name() + "（" + user.dataScopeLabel() + "）")),
                        ""));
    }

    @GetMapping("/pages/purchase-orders/{id}")
    public ResponseEntity<String> purchaseOrder(
            @PathVariable String id, @RequestParam(name = "t", required = false) String token) {
        Optional<DemoUser> found = viewer(token);
        if (found.isEmpty()) return denied();
        DemoUser user = found.get();
        var db = services.db();
        var po = Queries.findPurchaseOrder(db, user.tenantId(), id)
                .filter(p -> user.dataScope().purchasingOrgs().contains(Rows.str(p, "purchasingOrg")));
        if (po.isEmpty()) return denied();
        var order = po.get();
        String poId = Rows.str(order, "id");
        var status = Queries.deliveryStatus(
                        db, user.tenantId(), List.of(poId), services.clock().instant())
                .get(poId);
        var lines =
                Rows.list(db.jdbc(), "SELECT * FROM po_lines WHERE tenant_id = ? AND po_id = ?", user.tenantId(), poId);
        var supplier = Rows.one(
                db.jdbc(),
                "SELECT * FROM suppliers WHERE tenant_id = ? AND id = ?",
                user.tenantId(),
                order.get("supplierId"));
        StringBuilder extra = new StringBuilder(
                "<h1 style=\"font-size:16px;margin-top:24px\">订单行</h1><table><tr><th>行号</th><th>物料</th><th>数量</th><th>计划交期</th></tr>");
        for (var line : lines) {
            extra.append("<tr><td>")
                    .append(line.get("lineNo"))
                    .append("</td><td>")
                    .append(escape(line.get("materialId")))
                    .append("</td><td>")
                    .append(JsNumbers.format(Rows.num(line, "quantity")))
                    .append("</td><td>")
                    .append(escape(line.get("plannedDeliveryDate")))
                    .append("</td></tr>");
        }
        extra.append("</table>");
        return html(
                200,
                page(
                        "采购订单 · " + poId,
                        List.of(
                                new Row("供应商", supplier.map(s -> s.get("name")).orElse(null)),
                                new Row("采购组织", Queries.orgName(user.tenantId(), Rows.str(order, "purchasingOrg"))),
                                new Row("状态", order.get("status")),
                                new Row(
                                        "金额",
                                        JsNumbers.format(Rows.num(order, "totalAmount")) + " " + order.get("currency")),
                                new Row("下单日期", order.get("createdAt")),
                                new Row("逾期天数", status == null ? 0 : status.overdueDays())),
                        extra.toString()));
    }
}

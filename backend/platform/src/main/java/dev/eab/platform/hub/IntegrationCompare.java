package dev.eab.platform.hub;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.spec.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 故事 D 第 2 步的对照（演示模式）：同一个问题“逾期超过 30 天的订单”，一边以用户身份经 BO API（数据按用户的采购组织过滤），
 * 一边以集成账号经 Open API（返回全部采购组织）。用来说明 Agent 为什么必须走 BO API、为什么不能用集成账号兜底。只读，写审计。
 */
public final class IntegrationCompare {

    /** 对照里用的平台身份，记入审计和运行记录 */
    static final String AGENT = "platform/governance";

    public record Side(String channel, String identity, boolean filtered, String scope, int total, Map<String, Integer> byOrg) {}

    public record Result(String question, Side bo, Side open, String explanation) {}

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public IntegrationCompare(PlatformServices services, RuntimeService runtime) {
        this.services = services;
        this.runtime = runtime;
    }

    @SuppressWarnings("unchecked")
    public Result compare(DemoUser user) {
        String question = "列出逾期超过 30 天的采购订单";
        String token = services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles()));
        var bo = runtime.callPlatformTool(RuntimeService.NAVIGATOR_AGENT, user, token, "purchase_order_list", Map.of("overdueDaysGt", 30, "limit", 200));
        Map<String, Object> boData = bo.ok() && bo.data() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Side boSide = side("BO API", user.name() + "（" + user.id() + "）经 Agent", boData);

        String clientId = user.tenantId() + "-integration";
        String clientToken = services.tokens().issueClient("client:" + clientId, user.tenantId());
        Map<String, Object> openData;
        try {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(runtime.settings().erpUrl()).resolve("/open/v1/purchase-orders?overdueDaysGt=30&limit=200"))
                            .timeout(Duration.ofSeconds(10))
                            .header("authorization", "Bearer " + clientToken)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            openData = Json.MAPPER.readValue(response.body(), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 DemoERP 的 Open API：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("请求被中断");
        }
        Side openSide = side("Open API", "集成账号 " + clientId, openData);
        services.audit()
                .append(NewAuditEvent.of(user.id(), "demo.integration-compare", clientId, "allow", "演示：BO API 与 Open API 的数据范围对照")
                        .tenantId(user.tenantId()));
        String explanation = "BO API 以“用户 + Agent”的身份调用，按 " + user.name() + " 的数据权限过滤，只返回 " + boSide.scope()
                + "；Open API 以集成账号调用，不区分用户，返回 " + openSide.total() + " 张订单、" + openSide.byOrg().size()
                + " 个采购组织的全部数据。Agent 如果用集成账号兜底，就会让用户看到自己在 ERP 里看不到的数据，所以平台只允许 Agent 走 BO API，"
                + "身份透传失败时一律拒绝。";
        return new Result(question, boSide, openSide, explanation);
    }

    @SuppressWarnings("unchecked")
    static Side side(String channel, String identity, Map<String, Object> data) {
        List<Map<String, Object>> items = data.get("items") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        Map<String, Integer> byOrg = new TreeMap<>();
        for (var item : items) byOrg.merge(String.valueOf(item.getOrDefault("purchasingOrgName", "未知")), 1, Integer::sum);
        String scope = data.get("scopeLabel") instanceof String s ? s : String.join("、", byOrg.keySet());
        int total = data.get("total") instanceof Number n ? n.intValue() : items.size();
        return new Side(channel, identity, Boolean.TRUE.equals(data.get("filtered")), scope, total, new LinkedHashMap<>(byOrg));
    }
}

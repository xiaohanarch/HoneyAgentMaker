package dev.eab.platform.assist;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * P4 验收的端到端测试：统一入口经两级路由把问题交给 Agent，覆盖四种交互模式、权限过滤、澄清、能力缺口、
 * 扩展需求、业务事件触发的受托运行与通知。平台和 DemoERP 经真实端口互通，模型为脚本化模型。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistEndToEndTest {

    static final String SECRET = "assist-e2e-secret";
    static final long NOTIFICATION_WAIT_MILLIS = 20_000;

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    void start() throws Exception {
        int erpPort = freePort();
        platform = new SpringApplicationBuilder(PlatformApplication.class)
                .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.ephemeral-keys=true",
                        "--eab.seed-assets=true",
                        "--eab.demo-mode=true",
                        "--eab.internal-secret=" + SECRET,
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort);
        services = platform.getBean(PlatformServices.class);
        platformUrl = "http://127.0.0.1:" + platform.getEnvironment().getProperty("local.server.port");
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .run(
                        "--server.port=" + erpPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl,
                        "--eab.internal-secret=" + SECRET,
                        "--eab.delegation-cache-millis=0");
    }

    @AfterAll
    void stop() {
        if (erp != null) erp.close();
        if (platform != null) platform.close();
    }

    String login(String user) {
        return tokens.computeIfAbsent(user, u -> services.identity().login(u).token());
    }

    JsonNode call(String method, String path, String user, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(platformUrl + path))
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
        if (user != null) builder.header("authorization", "Bearer " + login(user));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.MAPPER.readTree(response.body());
    }

    JsonNode ask(String user, String message) throws Exception {
        return ask(user, message, null);
    }

    JsonNode ask(String user, String message, String target) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        if (target != null) body.put("target", target);
        JsonNode response = call("POST", "/assist", user, body);
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    static JsonNode routing(JsonNode assist) {
        for (JsonNode event : assist.path("events")) {
            if (event.path("type").asString().equals("routing")) return event.path("data");
        }
        return null;
    }

    static List<String> eventTypes(JsonNode assist) {
        List<String> types = new ArrayList<>();
        assist.path("events").forEach(e -> types.add(e.path("type").asString()));
        return types;
    }

    JsonNode run(String user, String runId) throws Exception {
        return call("GET", "/runs/" + runId, user, null).path("data");
    }

    @Test
    @DisplayName("分析型：张敏问华东区逾期交付率，路由到采购助手里的供应商绩效分析，追踪里有 routing span，路由面板有候选与理由")
    void analysisRoutesThroughProcurementAssistant() throws Exception {
        JsonNode result = ask("zhangmin", "华东区本季度逾期交付率是多少");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        JsonNode routing = routing(result);
        assertThat(routing).as(result.toString()).isNotNull();
        assertThat(routing.path("assistant").path("ref").asString()).isEqualTo("vendor.pur/procurement-assistant");
        assertThat(routing.path("target").asString()).isEqualTo("vendor.pur/supplier-performance-analyst");
        assertThat(routing.path("levels")).hasSize(2);
        assertThat(routing.path("levels").get(1).path("candidates").size()).isGreaterThan(1);
        assertThat(routing.path("reason").asString()).isNotBlank();
        assertThat(eventTypes(result)).contains("routing", "tool_call", "final");

        JsonNode detail = run("zhangmin", result.path("runId").asString());
        assertThat(detail.path("status").asString()).isEqualTo("completed");
        assertThat(detail.path("result").path("text").asString()).contains("逾期交付率", "采购组织：华东");

        JsonNode trace = call("GET", "/traces/" + result.path("traceId").asString(), "zhangmin", null)
                .path("data");
        List<String> kinds = new ArrayList<>();
        trace.path("spans").forEach(s -> kinds.add(s.path("kind").asString()));
        assertThat(kinds).contains("routing", "model", "tool");
    }

    @Test
    @DisplayName("同一个问题，张敏和李强看到的结果不同：数据按各自的采购组织过滤")
    void sameQuestionDifferentUsers() throws Exception {
        JsonNode zhang = ask("zhangmin", "华东区本季度逾期交付率是多少");
        JsonNode li = ask("liqiang", "华东区本季度逾期交付率是多少");
        String zhangText = run("zhangmin", zhang.path("runId").asString())
                .path("result")
                .path("text")
                .asString();
        String liText = run("liqiang", li.path("runId").asString())
                .path("result")
                .path("text")
                .asString();
        assertThat(zhangText).contains("采购组织：华东");
        assertThat(liText).contains("采购组织：华南");
        assertThat(zhangText).isNotEqualTo(liText);
    }

    @Test
    @DisplayName("事务型：改 PO-2026-0117 的交期先出确认卡片，确认后写入；拒绝时不写入")
    void transactionNeedsConfirmation() throws Exception {
        JsonNode result = ask("zhangmin", "把 PO-2026-0117 的交期改到下月 15 号");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        assertThat(routing(result).path("target").asString()).isEqualTo("vendor.pur/purchase-order-clerk");
        assertThat(eventTypes(result)).contains("confirmation_required");
        String runId = result.path("runId").asString();
        assertThat(run("zhangmin", runId).path("status").asString()).isEqualTo("suspended");

        JsonNode done = call("POST", "/runs/" + runId + "/confirmation", "zhangmin", Map.of("approved", true))
                .path("data");
        assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("completed");
        assertThat(done.path("text").asString()).contains("PO-2026-0117");
        assertThat(services.audit().list(AuditFilter.all().withAction("bo.access")))
                .anyMatch(e -> "purchase_order_change_delivery_date".equals(e.tool()) && "allow".equals(e.decision()));

        JsonNode second = ask("zhangmin", "把 PO-2026-0117 的交期改到下月 15 号");
        JsonNode rejected = call(
                        "POST",
                        "/runs/" + second.path("runId").asString() + "/confirmation",
                        "zhangmin",
                        Map.of("approved", false))
                .path("data");
        assertThat(rejected.path("status").asString()).isEqualTo("completed");
        assertThat(rejected.path("text").asString()).contains("用户拒绝");
    }

    @Test
    @DisplayName("导航型：打开百润精密的供应商详情，返回带页面令牌的 DemoERP 深链接卡片，页面可以打开")
    void navigationReturnsDeepLink() throws Exception {
        JsonNode result = ask("zhangmin", "打开百润精密的供应商详情");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("navigation");
        JsonNode card = result.path("payload").path("card");
        assertThat(card.path("title").asString()).isEqualTo("供应商 · 百润精密");
        String url = card.path("url").asString();
        assertThat(url).startsWith("/erp/pages/suppliers/S-0001?t=");
        String erpUrl = "http://127.0.0.1:" + erp.getEnvironment().getProperty("local.server.port");
        var page = http.send(
                HttpRequest.newBuilder(URI.create(erpUrl + url.substring("/erp".length())))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("百润精密");
        assertThat(result.path("traceId").asString()).isNotBlank();
    }

    @Test
    @DisplayName("信息型：降价谈判制度由采购制度顾问回答，引用知识库文档")
    void informationAnswersFromKnowledgeBase() throws Exception {
        JsonNode result = ask("zhangmin", "我们的降价谈判制度是怎么规定的");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        assertThat(routing(result).path("target").asString()).isEqualTo("vendor.pur/procurement-policy-advisor");
        JsonNode detail = run("zhangmin", result.path("runId").asString());
        assertThat(detail.path("status").asString()).isEqualTo("completed");
        assertThat(detail.path("result").path("text").asString()).contains("降价");
        assertThat(result.path("events").toString()).contains("kb_procurement_policies");
    }

    @Test
    @DisplayName("权限过滤：财务经理问采购问题，采购助手被过滤并说明原因，结果是能力缺口")
    void filteredAssistantBecomesGap() throws Exception {
        JsonNode result = ask("wangfang", "把 PO-2026-0117 的交期改到下月 15 号");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("gap");
        String routing = routing(result).toString();
        assertThat(routing).contains("filtered");
        assertThat(result.path("payload").path("originalText").asString()).contains("PO-2026-0117");
    }

    @Test
    @DisplayName("能力缺口：现有能力处理不了的问题给出提交扩展需求，提交后在本租户创建 DevRequest 草稿")
    void gapAndDevRequest() throws Exception {
        JsonNode result = ask("zhangmin", "帮我预订下周去深圳的机票");
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("gap");
        assertThat(result.path("payload").path("suggestion").asString()).contains("扩展需求");

        JsonNode submitted = call("POST", "/dev-requests", "zhangmin", Map.of("text", "帮我预订下周去深圳的机票", "assistant", ""));
        assertThat(submitted.path("success").asBoolean())
                .as(submitted.toString())
                .isTrue();
        String ref = submitted.path("data").path("ref").asString();
        assertThat(ref).startsWith("tenant.t001.requests/req-");
        var drafts = services.registry()
                .list(
                        Viewer.tenant("t001"),
                        new dev.eab.platform.registry.AssetRepository.AssetFilter(
                                "DevRequest", null, null, null, null));
        assertThat(drafts.toString()).contains(ref.substring(ref.indexOf('/') + 1));

        JsonNode invalid = call("POST", "/dev-requests", "zhangmin", Map.of("text", " "));
        assertThat(invalid.path("error").path("code").asString()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("澄清：点选澄清选项时直接进入所选能力；统一入口只对租户用户开放，参数不合法时 400")
    void targetAndValidation() throws Exception {
        JsonNode chosen = ask("zhangmin", "PO-2026-0117 现在怎么样了", "vendor.pur/procurement-assistant");
        assertThat(routing(chosen)
                        .path("levels")
                        .get(0)
                        .path("decision")
                        .path("decidedBy")
                        .asString())
                .isEqualTo("user");

        assertThat(call("POST", "/assist", "vendordev", Map.of("message", "你好"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("POST", "/assist", "zhangmin", Map.of("message", ""))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(call("POST", "/assist", "zhangmin", Map.of("message", "你好", "target", "../x"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(call("POST", "/assist", null, Map.of("message", "你好"))
                        .path("success")
                        .asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("业务事件：交付逾期事件触发库存经营分析，以孙库管的委托运行，结果推送到孙库管的通知区，可标记已读")
    void deliveryOverdueNotifiesDelegator() throws Exception {
        JsonNode triggered = call("POST", "/demo/v1/events", "zhaoadmin", Map.of("type", "delivery.overdue"));
        assertThat(triggered.path("success").asBoolean())
                .as(triggered.toString())
                .isTrue();

        JsonNode notification = null;
        long deadline = System.currentTimeMillis() + NOTIFICATION_WAIT_MILLIS;
        while (notification == null && System.currentTimeMillis() < deadline) {
            for (JsonNode n : call("GET", "/notifications", "sunkg", null).path("data")) {
                if (n.path("source").asString().equals("event:delivery.overdue")) notification = n;
            }
            if (notification == null) Thread.sleep(200);
        }
        assertThat(notification).as("孙库管应收到交付逾期事件的通知").isNotNull();
        assertThat(notification.path("body").asString()).contains("M-0007");
        assertThat(notification.path("read").asBoolean()).isFalse();
        // 张敏不是委托人，看不到这条通知，也不能标记它
        assertThat(call("GET", "/notifications", "zhangmin", null).path("data").toString())
                .doesNotContain("event:delivery.overdue");
        long id = notification.path("id").asLong();
        assertThat(call("POST", "/notifications/" + id + "/read", "zhangmin", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(call("POST", "/notifications/" + id + "/read", "sunkg", null)
                        .path("success")
                        .asBoolean())
                .isTrue();
        assertThat(call("POST", "/demo/v1/events", "zhaoadmin", Map.of("type", "x"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
    }
}

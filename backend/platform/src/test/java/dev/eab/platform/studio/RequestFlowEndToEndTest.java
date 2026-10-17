package dev.eab.platform.studio;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.spec.Json;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 扩展需求处理闭环（P9）的端到端测试：张敏在缺口卡片上补一句大额审批要求后提交，平台按需求文本检索
 * 语义对象与规则附带给开发者；陈工在 Studio 生成六步方案卡（脚本化模型按剧本输出，草稿与仓库里的
 * 租户资产一致），修改保存后采纳成工作区草稿（幂等），发布完成后标记交付，张敏在通知区收到消息。
 * 拒绝路径同样通知提出人。发布与运行部分由 StoryEEndToEndTest 覆盖，这里不重复。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequestFlowEndToEndTest {

    static final String SECRET = "request-flow-e2e-secret";
    static final String QUESTION = "百润精密这批货到不了，帮我从备选供应商里找有现货的下单";
    static final String SUPPLEMENT = "金额超过 10 万的必须人工审批";

    ConfigurableApplicationContext platform;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    String requestRef;
    String secondRef;

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    void start() throws Exception {
        int platformPort = freePort();
        platformUrl = "http://127.0.0.1:" + platformPort;
        platform = new SpringApplicationBuilder(PlatformApplication.class)
                .run(
                        "--server.port=" + platformPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.ephemeral-keys=true",
                        "--eab.seed-assets=true",
                        "--eab.demo-mode=true",
                        "--eab.internal-secret=" + SECRET,
                        "--eab.demo-erp-url=http://127.0.0.1:1");
        services = platform.getBean(PlatformServices.class);
    }

    @AfterAll
    void stop() {
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

    JsonNode ok(String method, String path, String user, Object body) throws Exception {
        JsonNode response = call(method, path, user, body);
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    String error(String method, String path, String user, Object body) throws Exception {
        JsonNode response = call(method, path, user, body);
        assertThat(response.path("success").asBoolean()).as(response.toString()).isFalse();
        return response.path("error").path("code").asString();
    }

    static String namespaceOf(String ref) {
        return ref.substring(0, ref.indexOf('/'));
    }

    static String nameOf(String ref) {
        return ref.substring(ref.indexOf('/') + 1);
    }

    static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) values.add(item.path(field).asString());
        return values;
    }

    JsonNode detail(String ref) throws Exception {
        return ok("GET", "/studio/requests/" + namespaceOf(ref) + "/" + nameOf(ref), "chengong", null);
    }

    @Test
    @Order(1)
    @DisplayName("张敏补上大额审批要求后提交：平台按需求文本检索语义对象与规则附带给开发者")
    void submitWithContext() throws Exception {
        JsonNode submitted = ok(
                "POST",
                "/dev-requests",
                "zhangmin",
                Map.of("text", QUESTION, "assistant", "vendor.pur/purchase-order-clerk", "supplement", SUPPLEMENT));
        requestRef = submitted.path("ref").asString();
        assertThat(requestRef).startsWith("tenant.t001.requests/req-");
        assertThat(submitted.path("semanticObjects").toString()).contains("SupplierAlternative");
        assertThat(submitted.path("rules").toString()).contains("PUR-R-008");
    }

    @Test
    @Order(2)
    @DisplayName("陈工在首页看到需求；详情里有原话、补充说明和附带的口径；张敏不能进 Studio")
    void homeAndDetail() throws Exception {
        JsonNode home = ok("GET", "/studio/home", "chengong", null);
        JsonNode mine = null;
        for (JsonNode item : home.path("devRequests")) {
            if (item.path("ref").asString().equals(requestRef)) mine = item;
        }
        assertThat(mine).as(home.toString()).isNotNull();
        assertThat(mine.path("status").asString()).isEqualTo("submitted");
        assertThat(mine.path("displayName").asString()).contains("百润精密");

        JsonNode view = detail(requestRef);
        assertThat(view.path("originalText").asString()).isEqualTo(QUESTION);
        assertThat(view.path("supplements").toString()).contains(SUPPLEMENT);
        assertThat(view.path("requesterName").asString()).isEqualTo("张敏");
        assertThat(view.path("context").path("semanticObjects").toString()).contains("SupplierAlternative");
        assertThat(view.path("context").path("rules").toString()).contains("PUR-R-008");
        // 还没有生成过方案；序列化忽略 null 字段，所以 plan 字段整个不出现
        assertThat(view.has("plan")).isFalse();

        assertThat(error(
                        "GET",
                        "/studio/requests/" + namespaceOf(requestRef) + "/" + nameOf(requestRef),
                        "zhangmin",
                        null))
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @Order(3)
    @DisplayName("陈工点生成方案：六步方案卡齐了（fit-gap 是扩展，草稿与评测集来自剧本），需求进入处理中")
    void generatePlan() throws Exception {
        String path = "/studio/requests/" + namespaceOf(requestRef) + "/" + nameOf(requestRef) + "/plan";
        JsonNode generated = ok("POST", path, "chengong", null);
        assertThat(generated.path("status").asString()).isEqualTo("in-progress");
        JsonNode plan = generated.path("plan");
        assertThat(plan.path("decidedBy").asString()).isEqualTo("model");
        assertThat(plan.path("generatedAt").asString()).isNotEmpty();
        assertThat(plan.path("fitGap").path("verdict").asString()).isEqualTo("extend");
        assertThat(plan.path("fitGap").path("score").asDouble()).isGreaterThan(0.8);
        assertThat(plan.path("summary").path("boundaries").asString()).contains("10 万元");
        List<String> kinds = texts(plan.path("drafts"), "kind");
        assertThat(kinds).containsExactlyInAnyOrder("Skill", "AgentExtension");
        assertThat(plan.path("drafts").toString()).contains("alternative-supplier-lookup", "purchase-order-clerk-ext");
        assertThat(plan.path("evalSuite").path("name").asString()).isEqualTo("purchase-clerk-ext-smoke");
        // 详情里能再读到同一份方案
        assertThat(detail(requestRef)
                        .path("plan")
                        .path("fitGap")
                        .path("verdict")
                        .asString())
                .isEqualTo("extend");
    }

    @Test
    @Order(4)
    @DisplayName("陈工修改方案后保存：合格的结构被接受，不合格的报 PLAN_INVALID")
    void savePlan() throws Exception {
        JsonNode plan = detail(requestRef).path("plan");
        ObjectNode edited = ((ObjectNode) plan).deepCopy();
        ((ObjectNode) edited.path("summary"))
                .put("boundaries", "只对认证备选供应商操作；金额超过 10 万元的请购单草稿必须人工确认（PUR-R-008）；补货数量由技能按订单缺口计算");
        String path = "/studio/requests/" + namespaceOf(requestRef) + "/" + nameOf(requestRef) + "/plan";
        JsonNode saved = ok("PUT", path, "chengong", Map.of("plan", edited));
        assertThat(saved.path("plan").path("summary").path("boundaries").asString())
                .contains("补货数量由技能按订单缺口计算");

        assertThat(error("PUT", path, "chengong", Map.of("plan", Map.of("fitGap", "broken"))))
                .isEqualTo("PLAN_INVALID");
    }

    @Test
    @Order(5)
    @DisplayName("采纳方案：技能与扩展落成工作区草稿，扩展带上了评测集；重复采纳返回同一批草稿")
    void adopt() throws Exception {
        String path = "/studio/requests/" + namespaceOf(requestRef) + "/" + nameOf(requestRef) + "/adopt";
        JsonNode adopted = ok("POST", path, "chengong", null);
        List<String> ids = texts(adopted.path("drafts"), "id");
        List<String> refs = texts(adopted.path("drafts"), "ref");
        assertThat(refs)
                .containsExactlyInAnyOrder(
                        "tenant.t001.skills/alternative-supplier-lookup", "tenant.t001.pur/purchase-order-clerk-ext");

        JsonNode again = ok("POST", path, "chengong", null);
        assertThat(texts(again.path("drafts"), "id")).containsExactlyInAnyOrderElementsOf(ids);

        for (JsonNode draft : adopted.path("drafts")) {
            JsonNode view = ok("GET", "/studio/drafts/" + draft.path("id").asString(), "chengong", null);
            if (draft.path("kind").asString().equals("AgentExtension")) {
                assertThat(view.path("evalYaml").asString()).contains("purchase-clerk-ext-smoke");
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("交付：需求标记已交付，张敏在通知区收到消息；已交付的需求不能再处理")
    void deliver() throws Exception {
        String path = "/studio/requests/" + namespaceOf(requestRef) + "/" + nameOf(requestRef);
        JsonNode delivered = ok("POST", path + "/deliver", "chengong", Map.of("summary", "备选供应商补货与大额请购确认已上线"));
        assertThat(delivered.path("status").asString()).isEqualTo("delivered");

        JsonNode notifications = ok("GET", "/notifications", "zhangmin", null);
        boolean found = false;
        for (JsonNode item : notifications) {
            if (item.path("title").asString().contains("已交付")
                    && item.path("body").asString().contains("已上线")) {
                found = true;
            }
        }
        assertThat(found).as(notifications.toString()).isTrue();

        assertThat(error("POST", path + "/deliver", "chengong", Map.of())).isEqualTo("REQUEST_CLOSED");
    }

    @Test
    @Order(7)
    @DisplayName("第二个需求走拒绝：张敏收到没有通过评估的通知")
    void reject() throws Exception {
        JsonNode submitted = ok("POST", "/dev-requests", "zhangmin", Map.of("text", "帮我自动给所有供应商群发降价通知"));
        secondRef = submitted.path("ref").asString();
        String path = "/studio/requests/" + namespaceOf(secondRef) + "/" + nameOf(secondRef);
        JsonNode rejected = ok("POST", path + "/reject", "chengong", Map.of("reason", "群发通知不适合用 Agent 做"));
        assertThat(rejected.path("status").asString()).isEqualTo("rejected");

        JsonNode notifications = ok("GET", "/notifications", "zhangmin", null);
        boolean found = false;
        for (JsonNode item : notifications) {
            if (item.path("title").asString().contains("没有通过评估")
                    && item.path("body").asString().contains("群发通知不适合")) {
                found = true;
            }
        }
        assertThat(found).as(notifications.toString()).isTrue();
    }
}

package dev.eab.platform.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.Json;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
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
 * P7 的端到端测试：平台、DemoERP 和一个用官方 Java SDK 搭的外部 Agent 经真实端口互通。
 * 覆盖出站（统一入口路由到远程 Agent、身份透传、多轮、超时转异步与推送回调）和入站（Agent Card、无身份拒绝、审计调用方）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A2aEndToEndTest {

    static final String SECRET = "a2a-e2e-secret";
    static final long NOTIFICATION_WAIT_MILLIS = 20_000;

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    StubRemoteAgent remote;
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
        remote = new StubRemoteAgent();
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
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort,
                        "--eab.a2a-timeout-seconds=2",
                        "--eab.connections.[SUPPLIER_RISK_AGENT]=" + remote.url);
        services = platform.getBean(PlatformServices.class);
        platformUrl = "http://127.0.0.1:" + platform.getEnvironment().getProperty("local.server.port");
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .run(
                        "--server.port=" + erpPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl,
                        "--eab.internal-secret=" + SECRET);
    }

    @AfterAll
    void stop() {
        if (erp != null) erp.close();
        if (platform != null) platform.close();
        if (remote != null) remote.close();
    }

    String login(String user) {
        return tokens.computeIfAbsent(user, u -> services.identity().login(u).token());
    }

    HttpResponse<String> send(String method, String path, Map<String, String> headers, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(platformUrl + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    JsonNode call(String method, String path, String user, Object body) throws Exception {
        Map<String, String> headers =
                new HashMap<>(Map.of("accept", "application/json", "content-type", "application/json"));
        if (user != null) headers.put("authorization", "Bearer " + login(user));
        var response = send(method, path, headers, body == null ? null : Json.MAPPER.writeValueAsString(body));
        return Json.MAPPER.readTree(response.body());
    }

    JsonNode ask(String user, String message, String sessionId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        if (sessionId != null) body.put("sessionId", sessionId);
        JsonNode response = call("POST", "/assist", user, body);
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    static String finalText(JsonNode assist) {
        for (JsonNode event : assist.path("events")) {
            if (event.path("type").asString().equals("final"))
                return event.path("data").path("text").asString();
        }
        return null;
    }

    static JsonNode routing(JsonNode assist) {
        for (JsonNode event : assist.path("events")) {
            if (event.path("type").asString().equals("routing")) return event.path("data");
        }
        return null;
    }

    static JsonNode jwtClaims(String bearer) {
        String token = bearer.substring("Bearer ".length());
        String payload = token.split("\\.")[1];
        return Json.MAPPER.readTree(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("出站：张敏问百润精密的风险，路由到采购助手下的外部 Agent（agent-request），A2A 请求带以张敏为主体的服务令牌，追踪里有请求原文且令牌已脱敏")
    void outboundThroughAssistant() throws Exception {
        remote.authorizations.clear();
        JsonNode result = ask("zhangmin", "百润精密最近有没有什么风险", null);
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        JsonNode routing = routing(result);
        assertThat(routing.path("target").asString()).isEqualTo("tenant.t001.remote/supplier-risk");
        assertThat(routing.path("action").asString()).isEqualTo("agent-request");
        assertThat(routing.path("assistant").path("ref").asString()).isEqualTo("vendor.pur/procurement-assistant");
        assertThat(finalText(result)).contains("百润精密：风险等级 高");

        assertThat(remote.authorizations).isNotEmpty();
        JsonNode claims = jwtClaims(remote.authorizations.getFirst());
        assertThat(claims.path("sub").asString()).isEqualTo("zhangmin");
        assertThat(claims.path("aud").toString()).contains("eab-a2a");
        assertThat(claims.path("act").path("sub").asString()).isEqualTo("agent:vendor.pur/procurement-assistant");

        JsonNode trace = call("GET", "/traces/" + result.path("traceId").asString(), "zhangmin", null);
        String spans = trace.path("data").toString();
        assertThat(spans).contains("A2A · 供应商风险舆情 Agent", "SendStreamingMessage", "Bearer ***");
        assertThat(spans).doesNotContain(remote.authorizations.getFirst().substring(7));
    }

    @Test
    @DisplayName("多轮：没说供应商时外部 Agent 追问，追问展示给用户；同一会话里回答后带着 contextId、taskId 续接并完成")
    void multiTurnInputRequired() throws Exception {
        JsonNode first = ask("zhangmin", "最近有没有什么风险舆情需要注意", null);
        assertThat(routing(first).path("target").asString())
                .as(first.toString())
                .isEqualTo("tenant.t001.remote/supplier-risk");
        assertThat(finalText(first)).contains("需要补充信息", "哪家供应商");
        String session = first.path("sessionId").asString();

        JsonNode second = ask("zhangmin", "华远电子", session);
        assertThat(routing(second).path("decidedBy").asString())
                .as(second.toString())
                .isEqualTo("session");
        assertThat(finalText(second)).contains("华远电子：风险等级 高");
    }

    @Test
    @DisplayName("异步：外部 Agent 超过同步超时仍在处理，转为异步任务，结果经推送回调送到张敏的通知区")
    void asyncThroughPushCallback() throws Exception {
        JsonNode result = ask("zhangmin", "深度检索百润精密的风险舆情", null);
        assertThat(finalText(result)).as(result.toString()).contains("已转为异步任务");
        JsonNode notification = null;
        long deadline = System.currentTimeMillis() + NOTIFICATION_WAIT_MILLIS;
        while (notification == null && System.currentTimeMillis() < deadline) {
            for (JsonNode n : call("GET", "/notifications", "zhangmin", null).path("data")) {
                if (n.path("source").asString().equals("a2a:tenant.t001.remote/supplier-risk")) notification = n;
            }
            if (notification == null) Thread.sleep(200);
        }
        assertThat(notification).as("张敏应收到异步任务结束的通知").isNotNull();
        assertThat(notification.path("body").asString()).contains("百润精密：风险等级 高");
    }

    @Test
    @DisplayName("推送回调：令牌不对时拒绝，不更新任务")
    void callbackRejectsWrongToken() throws Exception {
        var response = send(
                "POST",
                "/a2a/callbacks/a2a-unknown",
                Map.of("content-type", "application/json", A2aCallbackController.TOKEN_HEADER, "wrong"),
                "{}");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("入站：采购助手的 Agent Card 在标准路径和旧路径都能取到，未允许外部调用的 Agent 返回 404")
    void inboundCards() throws Exception {
        String base = "/a2a/agents/vendor.pur/procurement-assistant/.well-known/";
        JsonNode card = Json.MAPPER.readTree(
                send("GET", base + "agent-card.json", Map.of(), null).body());
        assertThat(card.path("name").asString()).isEqualTo("采购助手");
        assertThat(card.toString()).contains("platformUserToken", "/a2a/agents/vendor.pur/procurement-assistant");
        assertThat(card.toString()).doesNotContain("instructions");
        JsonNode legacy = Json.MAPPER.readTree(
                send("GET", base + "agent.json", Map.of(), null).body());
        assertThat(legacy.path("protocolVersion").asString()).isEqualTo("0.3.0");
        assertThat(send(
                                "GET",
                                "/a2a/agents/vendor.pur/purchase-order-clerk/.well-known/agent-card.json",
                                Map.of(),
                                null)
                        .statusCode())
                .isEqualTo(404);
    }

    static String sendMessage(String text) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\"m-1\","
                + "\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"" + text + "\"}]}}}";
    }

    @Test
    @DisplayName("入站：没有用户令牌的调用拒绝并写审计；带张敏令牌的调用经采购助手完成，审计里有调用方和用户")
    void inboundCallRequiresIdentityAndIsAudited() throws Exception {
        String path = "/a2a/agents/vendor.pur/procurement-assistant";
        var denied = send(
                "POST",
                path,
                Map.of("content-type", "application/json", A2aInboundController.CALLER_HEADER, "partner-procurement"),
                sendMessage("华东区本季度逾期交付率是多少"));
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(services.audit().list(AuditFilter.all().withAction("a2a.inbound")))
                .anyMatch(e -> e.decision().equals("deny") && e.reason().contains("partner-procurement"));

        var allowed = send(
                "POST",
                path,
                Map.of(
                        "content-type",
                        "application/json",
                        "authorization",
                        "Bearer " + login("zhangmin"),
                        "A2A-Version",
                        "1.0",
                        A2aInboundController.CALLER_HEADER,
                        "partner-procurement"),
                sendMessage("华东区本季度逾期交付率是多少"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        JsonNode task = Json.MAPPER.readTree(allowed.body()).path("result").path("task");
        assertThat(task.path("status").path("state").asString())
                .as(allowed.body())
                .isEqualTo("TASK_STATE_COMPLETED");
        assertThat(task.path("artifacts").toString()).contains("逾期");
        assertThat(services.audit().list(AuditFilter.all().withAction("a2a.inbound")))
                .anyMatch(e -> e.decision().equals("allow")
                        && "zhangmin".equals(e.actor())
                        && e.reason().contains("partner-procurement"));
    }

    dev.eab.runtime.RunContext context(dev.eab.runtime.RunContext.User user) {
        return new dev.eab.runtime.RunContext(
                "run-test",
                "ses-test",
                user,
                "on-behalf",
                null,
                null,
                "vendor.pur/procurement-assistant",
                null,
                null,
                0);
    }

    dev.eab.runtime.RunContext.User zhangmin() {
        return dev.eab.platform.runtime.RuntimeService.runUser(
                dev.eab.directory.DemoDirectory.findUser("zhangmin").orElseThrow());
    }

    @Test
    @DisplayName("身份透传失败即拒绝：运行没有最终用户身份时不调用外部 Agent，不退回技术账号，并写审计")
    void outboundDeniesWithoutIdentity() {
        remote.authorizations.clear();
        RemoteAgentGateway gateway = platform.getBean(RemoteAgentGateway.class);
        var result = gateway.call(context(null), "tenant.t001.remote/supplier-risk", "百润精密最近有没有什么风险");
        assertThat(result.ok()).isFalse();
        assertThat(result.errorCode()).isEqualTo("IDENTITY_MISSING");
        assertThat(result.error()).contains("不会退回到技术账号");
        assertThat(remote.authorizations).isEmpty();
        assertThat(services.audit().list(AuditFilter.all().withAction("a2a.outbound")))
                .anyMatch(e -> e.decision().equals("deny") && e.reason().contains("身份透传失败"));
    }

    @Test
    @DisplayName("连接没有绑定地址时拒绝调用；外部 Agent 停机时给出降级说明")
    void outboundUnboundAndUnavailable() {
        var settings = new RemoteAgentGateway.Settings(Map.of(), () -> platformUrl, java.time.Duration.ofSeconds(2));
        var tasks = platform.getBean(A2aTaskStore.class);
        var unbound = new RemoteAgentGateway(services, tasks, settings)
                .call(context(zhangmin()), "tenant.t001.remote/fx-rate", "美元兑人民币");
        // 部署配置为空时取租户 Connection 资产的 prod 绑定，指向本机 4210（测试里没有启动），因此是不可用而不是未绑定
        assertThat(unbound.errorCode()).isIn("REMOTE_AGENT_UNAVAILABLE", "CONNECTION_UNBOUND");

        var down = new RemoteAgentGateway(
                        services,
                        tasks,
                        new RemoteAgentGateway.Settings(
                                Map.of("SUPPLIER_RISK_AGENT", "http://127.0.0.1:1"),
                                () -> platformUrl,
                                java.time.Duration.ofSeconds(2)))
                .call(context(zhangmin()), "tenant.t001.remote/supplier-risk", "百润精密最近有没有什么风险");
        assertThat(down.errorCode()).isEqualTo("REMOTE_AGENT_UNAVAILABLE");
        assertThat(down.error()).contains("暂时不可用", "本次回答不包含它提供的信息");

        assertThat(new RemoteAgentGateway(services, tasks, settings).connectionUrl("t001", "NO_SUCH_ALIAS"))
                .isEmpty();
    }

    @Test
    @DisplayName("入站运行没有调用方身份时拒绝执行")
    void inboundRunnerRequiresPrincipal() {
        var runner = new InboundRunner(
                new InboundAgents.Exposed(
                        "assistant", "vendor.pur/procurement-assistant", "1.1.1", "采购助手", null, java.util.List.of()),
                platform.getBean(dev.eab.platform.assist.AssistService.class),
                platform.getBean(dev.eab.platform.runtime.RuntimeService.class));
        var reply = runner.run(null, "你好", "a2a-x", null, note -> {});
        assertThat(reply.state()).isEqualTo("failed");
        assertThat(reply.text()).contains("身份透传失败");
    }

    @Test
    @DisplayName("接入外部 Agent：陈工输入 Agent Card 地址看到技能和建议配置，连通性测试展示请求与响应原文，发布后进入能力目录")
    void studioConnectsExternalAgent() throws Exception {
        String cardUrl = remote.url + "/.well-known/agent-card.json";
        JsonNode inspected = call("POST", "/studio/external/inspect", "chengong", Map.of("cardUrl", cardUrl));
        assertThat(inspected.path("success").asBoolean())
                .as(inspected.toString())
                .isTrue();
        JsonNode data = inspected.path("data");
        assertThat(data.path("card").path("name").asString()).isEqualTo("供应商风险舆情 Agent");
        assertThat(data.path("card").path("streaming").asBoolean()).isTrue();
        assertThat(data.path("skills").toString()).contains("supplier-risk");
        assertThat(data.path("suggestion").path("connection").asString()).isEqualTo("SUPPLIER_RISK_AGENT");
        assertThat(data.path("suggestion").path("name").asString()).isEqualTo("supplier-risk");

        JsonNode tested = call(
                "POST", "/studio/external/test", "chengong", Map.of("cardUrl", cardUrl, "message", "百润精密最近有没有什么风险"));
        assertThat(tested.path("data").path("state").asString())
                .as(tested.toString())
                .isEqualTo("completed");
        assertThat(tested.path("data").path("exchanges").toString()).contains("agent-card.json", "Bearer ***");

        Map<String, Object> body = new HashMap<>();
        body.put("cardUrl", cardUrl);
        body.put("name", "supplier-risk");
        body.put("displayName", "供应商风险舆情 Agent");
        body.put("connection", "SUPPLIER_RISK_AGENT");
        body.put("assistant", "vendor.pur/procurement-assistant");
        body.put("routing", Map.of("description", "查询供应商的外部风险和舆情", "examples", java.util.List.of("百润精密最近有没有什么风险")));
        JsonNode published = call("POST", "/studio/external/publish", "chengong", body);
        assertThat(published.path("data").path("ref").asString())
                .as(published.toString())
                .startsWith("tenant.t001.remote/supplier-risk@");
        assertThat(call("GET", "/studio/external", "chengong", null)
                        .path("data")
                        .toString())
                .contains("tenant.t001.remote/supplier-risk", "vendor.pur/procurement-assistant");
        assertThat(call("GET", "/studio/catalog", "chengong", null)
                        .path("data")
                        .path("agents")
                        .toString())
                .contains("tenant.t001.remote/supplier-risk");
    }

    @Test
    @DisplayName("接入外部 Agent：未登记的外部地址拒绝访问，非开发者不能使用")
    void studioExternalGuards() throws Exception {
        JsonNode foreign = call(
                "POST",
                "/studio/external/inspect",
                "chengong",
                Map.of("cardUrl", "http://10.1.2.3/.well-known/agent-card.json"));
        assertThat(foreign.path("error").path("code").asString()).isEqualTo("A2A_HOST_NOT_ALLOWED");
        JsonNode forbidden = call("POST", "/studio/external/inspect", "zhangmin", Map.of("cardUrl", remote.url));
        assertThat(forbidden.path("error").path("code").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("入站：允许被其他 Agent 调用的 Agent 直接运行，流式请求以 SSE 返回；其他租户的 Agent 不能调用")
    void inboundAgentStreams() throws Exception {
        String body = sendMessage("百润精密最近表现怎么样").replace("\"SendMessage\"", "\"SendStreamingMessage\"");
        var response = send(
                "POST",
                "/a2a/agents/vendor.pur/supplier-performance-analyst",
                Map.of(
                        "content-type", "application/json",
                        "authorization", "Bearer " + login("zhangmin"),
                        "A2A-Version", "1.0"),
                body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains("TASK_STATE_COMPLETED", "百润精密");
        var foreign = send(
                "POST",
                "/a2a/agents/tenant.t002.agents/anything",
                Map.of("content-type", "application/json", "authorization", "Bearer " + login("zhangmin")),
                body);
        assertThat(foreign.statusCode()).isEqualTo(404);
    }
}

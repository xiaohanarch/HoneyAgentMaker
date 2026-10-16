package dev.eab.platform.skills;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
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

/** 技能编辑器：从标品技能复制、单独测试（每一步的输入输出）、编辑步骤、写操作停在确认点、发布 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillEndToEndTest {

    static final String SECRET = "skills-e2e-secret";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    String draftId;

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

    JsonNode ok(JsonNode response) {
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    @Test
    @Order(1)
    @DisplayName("从标品的询价单查询技能复制成租户技能，单独测试时能看到每一步的输入和输出")
    void copyAndTest() throws Exception {
        JsonNode created = ok(call(
                "POST",
                "/studio/skills",
                "chengong",
                Map.of("name", "my-rfq-lookup", "displayName", "询价单查询（本公司）", "from", "vendor.pur.skills/rfq-lookup")));
        draftId = created.path("draft").path("id").asString();
        assertThat(created.path("yaml").asString()).contains("namespace: tenant.t001.skills", "copiedFrom", "rfq_list");

        JsonNode result = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/skill-test",
                "chengong",
                Map.of("input", Map.of("supplierName", "百润精密"), "asUser", "zhangmin")));
        assertThat(result.path("status").asString()).as(result.toString()).isEqualTo("ok");
        assertThat(result.path("output").path("rfqs")).hasSize(1);
        List<String> ids = new ArrayList<>();
        result.path("steps").forEach(s -> ids.add(s.path("id").asString()));
        assertThat(ids).contains("query", "latest", "take-first");
        JsonNode query = result.path("steps").get(ids.indexOf("query"));
        assertThat(query.path("input").asString()).contains("百润精密");
        assertThat(query.path("output").asString()).contains("RFQ-");
        // 测试运行记下了发起的开发者，陈工可以查看追踪
        assertThat(call("GET", "/traces/" + result.path("traceId").asString(), "chengong", null)
                        .path("success")
                        .asBoolean())
                .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("编辑步骤：去掉“只取最近一次”后返回全部询价单；不合法的步骤列在问题里")
    void editSteps() throws Exception {
        List<Map<String, Object>> steps = List.of(Map.of(
                "id",
                "query",
                "type",
                "tool",
                "name",
                "查询询价单",
                "tool",
                "rfq_list",
                "source",
                "vendor.erp.tools/demo-erp-bo",
                "with",
                Map.of("supplierName", "input.supplierName")));
        JsonNode draft =
                ok(call("PUT", "/studio/drafts/" + draftId + "/skill-steps", "chengong", Map.of("steps", steps)));
        assertThat(draft.path("issues")).isEmpty();
        JsonNode result = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/skill-test",
                "chengong",
                Map.of("input", Map.of("supplierName", "百润精密"), "asUser", "zhangmin")));
        assertThat(result.path("steps")).hasSize(1);
        assertThat(result.path("output").path("items").size()).isGreaterThan(0);

        JsonNode bad = ok(call(
                "PUT",
                "/studio/drafts/" + draftId + "/skill-steps",
                "chengong",
                Map.of("steps", List.of(Map.of("id", "x", "type", "teleport")))));
        assertThat(bad.path("issues").toString()).contains("spec.steps[0].type");
        ok(call("PUT", "/studio/drafts/" + draftId + "/skill-steps", "chengong", Map.of("steps", steps)));
    }

    @Test
    @Order(3)
    @DisplayName("写操作的技能单独测试时停在确认点，不写入数据")
    void stopsAtConfirmation() throws Exception {
        JsonNode created = ok(call(
                "POST",
                "/studio/skills",
                "chengong",
                Map.of("name", "my-followup", "from", "vendor.common.skills/create-followup-task")));
        JsonNode result = ok(call(
                "POST",
                "/studio/drafts/" + created.path("draft").path("id").asString() + "/skill-test",
                "chengong",
                Map.of("input", Map.of("title", "跟进测试", "assignee", "财务经理"), "asUser", "sunkg")));
        assertThat(result.path("status").asString()).as(result.toString()).isEqualTo("confirmation");
        assertThat(result.path("pending").asString()).isNotBlank();
    }

    @Test
    @Order(4)
    @DisplayName("发布技能：写入注册中心；内容没变时不升版本；业务用户不能用技能编辑器")
    void publish() throws Exception {
        JsonNode published = ok(call("POST", "/studio/drafts/" + draftId + "/skill-publish", "chengong", null));
        assertThat(published.path("ref").asString()).isEqualTo("tenant.t001.skills/my-rfq-lookup");
        assertThat(published.path("version").asString()).isEqualTo("1.0.0");
        JsonNode again = ok(call("POST", "/studio/drafts/" + draftId + "/skill-publish", "chengong", null));
        assertThat(again.path("unchanged").asBoolean()).isTrue();
        JsonNode tools = ok(call("GET", "/studio/tools", "chengong", null));
        assertThat(tools.path("skills").toString()).contains("tenant.t001.skills/my-rfq-lookup");
        assertThat(call("POST", "/studio/skills", "zhangmin", Map.of("name", "abc-def"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
    }
}

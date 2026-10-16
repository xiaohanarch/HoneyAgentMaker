package dev.eab.platform.studio;

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

/**
 * 故事 A 的后端端到端测试：陈工从一句话意图走完向导（脚本化模型按 studio.yaml 推荐），在私有环境以张敏的身份测试，
 * 运行评测，静态检查，发布并晋级到 prod；之后张敏在统一入口直接用上价格谈判助手，追踪里能看到 PUR-R-007 触发。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StudioEndToEndTest {

    static final String SECRET = "studio-e2e-secret";
    static final String INTENT = "帮采购经理制定和供应商的降价谈判策略";
    static final String AGENT = "tenant.t001.agents/price-negotiation-assistant";
    static final String PROMPT = "查一下百润精密最近一次询价，帮我出一份谈判策略";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    String draftId;
    String version;

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

    static final List<String> STEPS = List.of(
            "purpose",
            "naming",
            "process",
            "collaborators",
            "knowledge",
            "tools",
            "triggers",
            "channels",
            "instructions",
            "model");

    @Test
    @Order(1)
    @DisplayName("向导：每一步的 AI 推荐都来自剧本（模型网关），一键采纳后草稿逐步成形；外部 Agent 候选不可用，不写进草稿")
    void wizard() throws Exception {
        JsonNode created = ok(call("POST", "/studio/drafts", "chengong", Map.of("intent", INTENT)));
        draftId = created.path("draft").path("id").asString();
        assertThat(created.path("yaml").asString()).contains("kind: Agent", "tenant.t001.agents");

        for (String step : STEPS) {
            JsonNode rec =
                    ok(call("POST", "/studio/drafts/" + draftId + "/recommend", "chengong", Map.of("step", step)));
            assertThat(rec.path("decidedBy").asString()).as(step + " " + rec).isEqualTo("model");
            Map<String, Object> value = Json.MAPPER.convertValue(rec.path("value"), Map.class);
            ok(call("POST", "/studio/drafts/" + draftId + "/apply", "chengong", Map.of("step", step, "value", value)));
            if (step.equals("collaborators")) {
                assertThat(rec.path("value").path("agents").toString()).contains("connection:SUPPLIER_RISK_AGENT");
                assertThat(rec.path("candidates").toString()).contains("P7 接入 A2A 后可调用");
            }
        }
        JsonNode draft = ok(call("GET", "/studio/drafts/" + draftId, "chengong", null));
        String yaml = draft.path("yaml").asString();
        assertThat(yaml)
                .contains(
                        "name: price-negotiation-assistant",
                        "价格谈判助手",
                        "source-to-pay",
                        "vendor.pur/supplier-performance-analyst",
                        "vendor.pur/procurement",
                        "vendor.pur.skills/rfq-lookup",
                        "PUR-R-007",
                        "profile: reasoning")
                .doesNotContain("SUPPLIER_RISK_AGENT");
        assertThat(draft.path("issues")).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("私有环境测试：以张敏的身份运行草稿，PUR-R-007 触发，回复先提示交付问题；陈工能看到测试运行的追踪")
    void privateTest() throws Exception {
        JsonNode run = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/test",
                "chengong",
                Map.of("message", PROMPT, "asUser", "zhangmin")));
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("completed");
        assertThat(run.path("text").asString()).startsWith("先说结论：按规则 PUR-R-007").contains("已逾期", "RFQ-");
        List<String> types = new ArrayList<>();
        run.path("events").forEach(e -> types.add(e.path("type").asString()));
        assertThat(types).contains("constraint");
        List<String> tools = new ArrayList<>();
        run.path("events").forEach(e -> {
            if (e.path("type").asString().equals("tool_call"))
                tools.add(e.path("data").path("tool").asString());
        });
        assertThat(tools).contains("rfq-lookup", "purchase_order_list", "supplier-performance-analyst");

        JsonNode trace = ok(call("GET", "/traces/" + run.path("traceId").asString(), "chengong", null));
        assertThat(trace.path("spans").toString()).contains("约束 · PUR-R-007 触发");
        // 别的开发者看不到
        assertThat(call("GET", "/traces/" + run.path("traceId").asString(), "zhaoadmin", null)
                        .path("success")
                        .asBoolean())
                .isTrue(); // 租户管理员可以看本租户的运行
        assertThat(services.audit().list(AuditFilter.all().withAction("studio.test-run")))
                .anyMatch(e -> "chengong".equals(e.actor()));
    }

    @Test
    @Order(3)
    @DisplayName("评测：从语义包的已验证任务生成初稿，运行通过；没有评测通过时不能晋级 prod")
    void evals() throws Exception {
        JsonNode generated = ok(call(
                "POST", "/studio/drafts/" + draftId + "/evals/generate", "chengong", Map.of("testUser", "zhangmin")));
        assertThat(generated.path("evalYaml").asString()).contains("PUR-VT-005", "calledTool", "judge");
        assertThat(generated.path("yaml").asString()).contains("tenant.t001.evals/price-negotiation-assistant-smoke");

        JsonNode lint = ok(call("POST", "/studio/drafts/" + draftId + "/lint", "chengong", null));
        assertThat(lint.path("ok").asBoolean()).as(lint.toString()).isTrue();

        JsonNode result = ok(call("POST", "/studio/drafts/" + draftId + "/evals/run", "chengong", null));
        assertThat(result.path("status").asString()).as(result.toString()).isEqualTo("passed");
        assertThat(result.path("cases").size()).isGreaterThan(0);
    }

    @Test
    @Order(4)
    @DisplayName("发布与晋级：发布部署到 dev，晋级 test、prod，门禁都通过；重复发布同一内容不升版本")
    void publishAndPromote() throws Exception {
        JsonNode published = ok(call("POST", "/studio/drafts/" + draftId + "/publish", "chengong", Map.of()));
        version = published.path("version").asString();
        assertThat(published.path("ref").asString()).isEqualTo(AGENT);
        assertThat(published.path("deployment").path("environment").asString()).isEqualTo("dev");
        JsonNode again = ok(call("POST", "/studio/drafts/" + draftId + "/publish", "chengong", Map.of()));
        assertThat(again.path("version").asString()).isEqualTo(version);
        assertThat(again.path("unchanged").asBoolean()).isTrue();

        // 跳过 test 直接晋级 prod 被门禁拦下
        JsonNode blocked = call(
                "POST",
                "/studio/deployments/promote",
                "chengong",
                Map.of("ref", AGENT, "version", version, "environment", "prod"));
        assertThat(blocked.path("error").path("code").asString()).isEqualTo("GATE_BLOCKED");
        assertThat(blocked.path("error").path("details").toString()).contains("还没有部署到 test");

        ok(call(
                "POST",
                "/studio/deployments/promote",
                "chengong",
                Map.of("ref", AGENT, "version", version, "environment", "test")));
        JsonNode prod = ok(call(
                "POST",
                "/studio/deployments/promote",
                "chengong",
                Map.of("ref", AGENT, "version", version, "environment", "prod")));
        assertThat(prod.path("gates").toString()).contains("评测门禁", "静态检查");
        JsonNode deployments = ok(call("GET", "/studio/deployments?ref=" + AGENT, "chengong", null));
        assertThat(deployments.path("environments").path("prod").path("version").asString())
                .isEqualTo(version);
    }

    @Test
    @Order(5)
    @DisplayName("上线后：张敏在统一入口问谈判策略，直接路由到价格谈判助手，追踪里 PUR-R-007 触发；首页列出各环境版本")
    void usedFromAssistant() throws Exception {
        JsonNode result = ok(call("POST", "/assist", "zhangmin", Map.of("message", PROMPT)));
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        JsonNode routing = null;
        for (JsonNode e : result.path("events"))
            if (e.path("type").asString().equals("routing")) routing = e.path("data");
        assertThat(routing).isNotNull();
        assertThat(routing.path("target").asString()).isEqualTo(AGENT);
        JsonNode trace = ok(call("GET", "/traces/" + result.path("traceId").asString(), "zhangmin", null));
        assertThat(trace.path("spans").toString()).contains("约束 · PUR-R-007 触发");

        JsonNode home = ok(call("GET", "/studio/home", "chengong", null));
        assertThat(home.path("agents").toString()).contains(AGENT, "\"prod\":\"" + version + "\"");
        assertThat(home.path("drafts").toString()).contains(draftId);
    }

    @Test
    @Order(6)
    @DisplayName("回滚：prod 只有一个版本时没有可回滚的；业务用户不能用 Studio；草稿只能放在本租户命名空间")
    void guards() throws Exception {
        assertThat(call("POST", "/studio/deployments/rollback", "chengong", Map.of("ref", AGENT, "environment", "prod"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOTHING_TO_ROLLBACK");
        assertThat(call("GET", "/studio/home", "zhangmin", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("GET", "/studio/drafts/" + draftId, "zhouting", null)
                        .path("success")
                        .asBoolean())
                .isFalse();
        String yaml = ok(call("GET", "/studio/drafts/" + draftId, "chengong", null))
                .path("yaml")
                .asString()
                .replace("tenant.t001.agents", "tenant.t002.agents");
        assertThat(call("PUT", "/studio/drafts/" + draftId + "/yaml", "chengong", Map.of("yaml", yaml))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NAMESPACE_FORBIDDEN");
    }
}

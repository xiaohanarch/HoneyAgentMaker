package dev.eab.platform.tools;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 工具中心：工具清单与引用关系、从 DemoERP 的 OpenAPI 勾选生成 BO 工具、登记 MCP Server 与审批、未审批不能上线。
 * MCP Server 用平台自己的 /mcp 端点代替外部服务（列工具不需要令牌）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolCenterEndToEndTest {

    static final String SECRET = "tools-e2e-secret";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    long approvalId;

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    void start() throws Exception {
        int erpPort = freePort();
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
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort);
        services = platform.getBean(PlatformServices.class);
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .run(
                        "--server.port=" + erpPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl,
                        "--eab.internal-secret=" + SECRET,
                        "--eab.delegation-cache-millis=0");
        // 一个指向平台自己 /mcp 端点的 MCP 连接别名
        String connection = """
                apiVersion: eab.dev/v1
                kind: Connection
                metadata: { name: platform-mcp, namespace: tenant.t001.connections, version: 1.0.0, owner: zhaoadmin }
                spec:
                  alias: PLATFORM_MCP
                  type: mcp
                  bindings:
                    dev: { url: '%s/mcp', auth: { type: platform-service-token } }
                """.formatted(platformUrl);
        Viewer t001 = Viewer.tenant("t001");
        assertThat(services.registry().put(t001, connection, "测试").ok()).isTrue();
        assertThat(services.registry()
                        .setStatus(
                                t001,
                                "tenant.t001.connections",
                                "platform-mcp",
                                "1.0.0",
                                AssetResolver.Status.published)
                        .ok())
                .isTrue();
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

    static JsonNode source(JsonNode list, String ref) {
        for (JsonNode s : list.path("sources")) if (s.path("ref").asString().equals(ref)) return s;
        return null;
    }

    @Test
    @Order(1)
    @DisplayName("工具清单：每个工具带写操作级别和被引用关系；技能也列出被哪些 Agent 使用")
    void list() throws Exception {
        JsonNode list = ok(call("GET", "/studio/tools", "chengong", null));
        JsonNode bo = source(list, "vendor.erp.tools/demo-erp-bo");
        assertThat(bo).isNotNull();
        JsonNode purchaseOrders = null;
        for (JsonNode t : bo.path("tools"))
            if (t.path("name").asString().equals("purchase_order_list")) purchaseOrders = t;
        assertThat(purchaseOrders.path("writeLevel").asString()).isEqualTo("L0");
        assertThat(purchaseOrders.path("usedBy").toString()).contains("vendor.pur/supplier-performance-analyst");
        assertThat(source(list, "tenant.t001.tools/notify-im").path("tools").toString())
                .contains("L2");
        assertThat(list.path("skills").toString())
                .contains("vendor.pur.skills/rfq-lookup", "vendor.pur/supplier-performance-analyst");
        // 技能用到的工具也记为被技能引用
        JsonNode rfq = null;
        for (JsonNode t : bo.path("tools")) if (t.path("name").asString().equals("rfq_list")) rfq = t;
        assertThat(rfq.path("usedBy").toString()).contains("vendor.pur.skills/rfq-lookup");
    }

    @Test
    @Order(2)
    @DisplayName("BO API 导入：从 OpenAPI 列出操作与写级别，勾选生成租户工具来源；没有的操作 400")
    void importBo() throws Exception {
        JsonNode ops = ok(call("GET", "/studio/tools/bo-operations", "chengong", null));
        assertThat(ops.toString())
                .contains(
                        "supplier_list",
                        "\"writeLevel\":\"L0\"",
                        "purchase_order_change_delivery_date",
                        "\"imported\":true");
        JsonNode created = ok(call(
                "POST",
                "/studio/tools/bo-import",
                "chengong",
                Map.of(
                        "name",
                        "pur-readonly",
                        "displayName",
                        "采购只读工具",
                        "operations",
                        List.of("supplier_list", "rfq_list"))));
        assertThat(created.path("ref").asString()).isEqualTo("tenant.t001.tools/pur-readonly");
        JsonNode list = ok(call("GET", "/studio/tools", "chengong", null));
        assertThat(source(list, "tenant.t001.tools/pur-readonly").path("tools").toString())
                .contains("supplier_list", "rfq_list");
        assertThat(call(
                                "POST",
                                "/studio/tools/bo-import",
                                "chengong",
                                Map.of("name", "bad-one", "operations", List.of("no_such_op")))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(call(
                                "POST",
                                "/studio/tools/bo-import",
                                "wangfang",
                                Map.of("name", "x-y-z", "operations", List.of("supplier_list")))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @Order(3)
    @DisplayName("MCP Server 登记：按连接别名拉取工具清单，生成 mcp 工具来源并进入待审批")
    void registerMcp() throws Exception {
        JsonNode tools = ok(call("GET", "/studio/tools/mcp-tools?connection=PLATFORM_MCP", "chengong", null));
        assertThat(tools.toString()).contains("supplier_list");
        JsonNode registered = ok(call(
                "POST",
                "/studio/tools/mcp-servers",
                "chengong",
                Map.of(
                        "name", "platform-mcp-tools",
                        "displayName", "平台 MCP（测试）",
                        "connection", "PLATFORM_MCP",
                        "tools", List.of(Map.of("name", "supplier_list", "writeLevel", "L0")))));
        assertThat(registered.path("approval").asString()).isEqualTo("pending");
        JsonNode approvals = ok(call("GET", "/studio/mcp-approvals", "chengong", null));
        approvalId = approvals.get(0).path("id").asLong();
        assertThat(source(ok(call("GET", "/studio/tools", "chengong", null)), "tenant.t001.tools/platform-mcp-tools")
                        .path("approval")
                        .asString())
                .isEqualTo("pending");
        assertThat(call("GET", "/studio/tools/mcp-tools?connection=DEMO_ERP", "chengong", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    @Order(4)
    @DisplayName("未审批的 MCP Server 不能上线：用到它的扩展发布被拦下；租户管理员审批通过后门禁放行")
    void approvalGate() throws Exception {
        JsonNode draft = ok(call(
                "POST", "/studio/extensions", "chengong", Map.of("target", "vendor.fin/transaction-anomaly-analyst")));
        String id = draft.path("draft").path("id").asString();
        ok(call(
                "POST",
                "/studio/drafts/" + id + "/extension",
                "chengong",
                Map.of("section", "tools", "value", Map.of("add", List.of("tenant.t001.tools/platform-mcp-tools")))));
        ok(call(
                "POST",
                "/studio/drafts/" + id + "/extension/evals/generate",
                "chengong",
                Map.of("testUser", "wangfang")));
        JsonNode blocked = call("POST", "/studio/drafts/" + id + "/extension/publish", "chengong", Map.of());
        assertThat(blocked.path("error").path("code").asString())
                .as(blocked.toString())
                .isEqualTo("MCP_NOT_APPROVED");

        McpGate gate = platform.getBean(McpGate.class);
        var ext = dev.eab.spec.AssetParser.parseYaml(ok(call("GET", "/studio/drafts/" + id, "chengong", null))
                        .path("yaml")
                        .asString())
                .asset();
        assertThat(gate.check("t001", ext))
                .singleElement()
                .satisfies(g -> assertThat(g.passed()).isFalse());

        assertThat(call(
                                "POST",
                                "/studio/mcp-approvals/" + approvalId + "/decision",
                                "chengong",
                                Map.of("approved", true))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        JsonNode decided = ok(call(
                "POST",
                "/studio/mcp-approvals/" + approvalId + "/decision",
                "zhaoadmin",
                Map.of("approved", true, "note", "只读工具，同意")));
        assertThat(decided.path("status").asString()).isEqualTo("approved");
        assertThat(gate.check("t001", ext))
                .singleElement()
                .satisfies(g -> assertThat(g.passed()).isTrue());
        assertThat(services.audit().list(AuditFilter.all().withAction("mcp.approval.decide")))
                .isNotEmpty();
    }
}

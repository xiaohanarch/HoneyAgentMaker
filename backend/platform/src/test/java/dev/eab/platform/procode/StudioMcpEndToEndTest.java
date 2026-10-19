package dev.eab.platform.procode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.spec.Json;
import dev.eab.spec.testing.RepoPaths;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * Studio MCP 端点的端到端测试（ADR 0014）：编码 Agent 经 /studio-mcp 拿业务上下文、校验资产并发布草稿。
 * 工具清单固定 14 个；陈工（租户开发者）可以查语义与扩展点、带评测集校验扩展 YAML、把技能草稿发布到租户命名空间；
 * 张敏（非开发者）与匿名调用被拒。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudioMcpEndToEndTest {

    static final String SECRET = "studio-mcp-e2e-secret";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();

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
    }

    @AfterAll
    void stop() {
        if (erp != null) erp.close();
        if (platform != null) platform.close();
    }

    static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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

    McpSyncClient mcpClient(String token) {
        var request = HttpRequest.newBuilder();
        if (token != null) request.header("Authorization", "Bearer " + token);
        var transport = HttpClientStreamableHttpTransport.builder(platformUrl)
                .endpoint(StudioMcpServer.ENDPOINT)
                .requestBuilder(request)
                .build();
        var client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args)
            throws Exception {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    private static String callText(McpSyncClient client, String tool, Map<String, Object> args) throws Exception {
        var result = call(client, tool, args);
        assertThat(result.isError()).as(text(result)).isFalse();
        return text(result);
    }

    @Test
    @DisplayName("工具清单：语义查询四个加 Studio 十个，全部登记")
    void listsAllTools() throws Exception {
        var client = mcpClient(login("chengong"));
        try {
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactlyInAnyOrder(
                            "semantic_describe_object",
                            "semantic_get_metric",
                            "semantic_find_rules",
                            "semantic_search",
                            "studio_list_capabilities",
                            "studio_list_vendor_agents",
                            "studio_get_vendor_agent",
                            "studio_list_drafts",
                            "studio_get_draft",
                            "studio_validate_asset",
                            "studio_generate_evals",
                            "studio_run_evals",
                            "studio_deploy_draft",
                            "studio_promote");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("陈工查业务上下文：语义检索命中备选供应商与 PUR-R-008，扩展点目录里有采购订单处理")
    void chengongQueriesContext() throws Exception {
        var client = mcpClient(login("chengong"));
        try {
            assertThat(callText(client, "semantic_search", Map.of("query", "备选供应商")))
                    .contains("备选供应商");
            assertThat(callText(client, "semantic_find_rules", Map.of("keyword", "PUR-R-008")))
                    .contains("PUR-R-008", "大额请购须人工确认");
            JsonNode agents = Json.MAPPER.readTree(callText(client, "studio_list_vendor_agents", Map.of()));
            assertThat(agents.toString()).contains("vendor.pur/purchase-order-clerk", "skills");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("技能草稿经 MCP 查看并发布；扩展带评测集一起校验，评测集引用在发布前就能解析")
    void draftLifecycleThroughMcp() throws Exception {
        // 准备一条技能草稿（快照内容与仓库一致），MCP 工具本身不建草稿，创建走 Studio 接口
        JsonNode created = ok(call(
                "POST",
                "/studio/skills",
                "chengong",
                Map.of("name", "alternative-supplier-lookup", "displayName", "备选供应商补货")));
        String draftId = created.path("draft").path("id").asString();
        String skillYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/pur/skills/alternative-supplier-lookup.yaml"),
                StandardCharsets.UTF_8);
        ok(call("PUT", "/studio/drafts/" + draftId + "/yaml", "chengong", Map.of("yaml", skillYaml)));

        var client = mcpClient(login("chengong"));
        try {
            assertThat(callText(client, "studio_list_drafts", Map.of()))
                    .contains(draftId, "tenant.t001.skills/alternative-supplier-lookup");
            JsonNode draft = Json.MAPPER.readTree(callText(client, "studio_get_draft", Map.of("draftId", draftId)));
            assertThat(draft.path("yaml").asString()).contains("备选供应商补货", "purchase_requisition_draft_create");

            assertThat(callText(client, "studio_deploy_draft", Map.of("draftId", draftId)))
                    .contains("tenant.t001.skills/alternative-supplier-lookup");

            // 扩展快照引用技能与评测集：技能已发布，评测集随扩展一起校验（草稿的静态检查语义）
            String extensionYaml = Files.readString(
                    RepoPaths.assets().resolve("tenants/t001/pur/purchase-order-clerk-ext.yaml"),
                    StandardCharsets.UTF_8);
            String evalYaml = Files.readString(
                    RepoPaths.assets().resolve("tenants/t001/evals/purchase-clerk-ext-smoke.yaml"),
                    StandardCharsets.UTF_8);
            JsonNode validation = Json.MAPPER.readTree(
                    callText(client, "studio_validate_asset", Map.of("yaml", extensionYaml, "evalYaml", evalYaml)));
            assertThat(validation.path("valid").asBoolean())
                    .as(validation.toString())
                    .isTrue();

            // 不带评测集时引用解析不了，如实报告：这是发布前就能看到的问题
            JsonNode bare =
                    Json.MAPPER.readTree(callText(client, "studio_validate_asset", Map.of("yaml", extensionYaml)));
            assertThat(bare.path("valid").asBoolean()).as(bare.toString()).isFalse();
            assertThat(bare.toString()).contains("purchase-clerk-ext-smoke");
        } finally {
            client.closeGracefully();
        }
    }

    @Test
    @DisplayName("张敏不是开发者、匿名没有令牌、受限回调令牌只属于 /mcp：工具调用一律被拒")
    void rejectsNonDeveloperAndAnonymous() throws Exception {
        var zhangmin = mcpClient(login("zhangmin"));
        try {
            var denied = call(zhangmin, "studio_list_drafts", Map.of());
            assertThat(denied.isError()).isTrue();
            assertThat(text(denied)).contains("租户开发者");
        } finally {
            zhangmin.closeGracefully();
        }
        var anonymous = mcpClient(null);
        try {
            var denied = call(anonymous, "semantic_search", Map.of("query", "供应商"));
            assertThat(denied.isError()).isTrue();
            assertThat(text(denied)).contains("用户令牌");
        } finally {
            anonymous.closeGracefully();
        }
        // 受限回调令牌只在 /mcp 被接受（ADR 0014），/studio-mcp 按用户令牌校验，一律拒绝
        String serviceToken = services.tokens()
                .issueService(
                        "zhangmin",
                        "t001",
                        "agent:vendor.pur/purchase-assistant",
                        "tenant.t001.remote/supplier-risk-sentiment");
        String restricted =
                services.identity().exchangeRestricted(serviceToken, null).accessToken();
        var callback = mcpClient(restricted);
        try {
            var denied = call(callback, "semantic_search", Map.of("query", "供应商"));
            assertThat(denied.isError()).isTrue();
            assertThat(text(denied)).contains("用户令牌");
        } finally {
            callback.closeGracefully();
        }
    }
}

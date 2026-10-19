package dev.eab.platform.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.Json;
import dev.eab.spec.testing.Fixtures;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * P3 验收的端到端测试：平台加载仓库里的全部标品资产（签名入库），DemoERP 灌入种子数据，两者经真实端口互通；
 * 用脚本化模型经 /runs 接口跑通四个标品 Agent，核对追踪、确认流程、审计与租户视角的裁剪。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeEndToEndTest {

    static final String SECRET = "runtime-e2e-secret";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();

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
        return services.identity().login(user).token();
    }

    JsonNode call(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(platformUrl + path))
                .header("authorization", "Bearer " + token)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.MAPPER.readTree(response.body());
    }

    JsonNode run(String user, String agent, String message) throws Exception {
        JsonNode body = call("POST", "/runs", login(user), Map.of("agent", agent, "message", message));
        assertThat(body.path("success").asBoolean()).as(body.toString()).isTrue();
        return body.path("data");
    }

    static List<String> kinds(JsonNode trace) {
        List<String> kinds = new ArrayList<>();
        trace.path("spans").forEach(s -> kinds.add(s.path("kind").asString()));
        return kinds;
    }

    @Test
    @DisplayName("供应商绩效分析：回复基于真实数据，指出逾期 120 天的订单并按 PUR-R-007 提示；追踪有各类 span，租户视角看不到标品指令")
    void supplierPerformance() throws Exception {
        JsonNode run = run("zhangmin", "vendor.pur/supplier-performance-analyst", "百润精密最近表现怎么样");
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("completed");
        String text = run.path("text").asString();
        assertThat(text)
                .contains("百润精密", "PO-2026-0117", "120 天", "PUR-R-007", "采购组织：华东")
                .doesNotContain("未经核实");
        assertThat(run.path("output").path("risks").toString()).contains("PO-2026-0117 逾期 120 天");
        assertThat(run.path("output").path("maxOverdueDays").asInt()).isGreaterThanOrEqualTo(120);
        List<String> types = new ArrayList<>();
        run.path("events").forEach(e -> types.add(e.path("type").asString()));
        assertThat(types).contains("run_started", "step", "tool_call", "tool_result", "text_delta", "final");

        JsonNode trace = call("GET", "/traces/" + run.path("traceId").asString(), login("zhangmin"), null)
                .path("data");
        assertThat(kinds(trace))
                .contains("run", "assemble", "model", "pipeline", "policy", "tool", "skill", "guard", "memory");
        String json = trace.toString();
        assertThat(json).contains("标品指令（不可见，运行时注入）").doesNotContain("你是供应商绩效分析专家");

        assertThat(services.audit().list(AuditFilter.all().withAction("policy.tool")))
                .anyMatch(e -> "supplier-lookup".equals(e.tool()) && "allow".equals(e.decision()));
        assertThat(services.audit().list(AuditFilter.all().withAction("bo.access")))
                .anyMatch(e -> "rfq_list".equals(e.tool()) && "zhangmin".equals(e.userId()));
    }

    @Test
    @DisplayName("交易异常解析：结构化结果带 severity、anomalyCount、totalAmount，金额合计由计算器算出")
    void transactionAnomalies() throws Exception {
        JsonNode run = run("wangfang", "vendor.fin/transaction-anomaly-analyst", "本月有哪些高风险交易异常");
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("completed");
        JsonNode output = run.path("output");
        assertThat(output.path("severity").asString()).isIn("high", "critical");
        assertThat(output.path("anomalyCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(output.path("totalAmount").asDouble()).isGreaterThan(500_000);
        assertThat(run.path("text").asString()).contains("ANO-").contains("公司主体：星海制造本部");
    }

    @Test
    @DisplayName("结账检查：列出 3 个未通过项和就绪率 50%")
    void periodClose() throws Exception {
        JsonNode run = run("wangfang", "vendor.fin/period-close-checker", "本期资产核算的结账检查还有哪些没通过");
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("completed");
        assertThat(run.path("output").path("failedCount").asInt()).isEqualTo(3);
        assertThat(run.path("text").asString()).contains("结账就绪率 50");
    }

    @Test
    @DisplayName("库存经营分析：低于安全库存的物料里有 M-0007；创建跟进任务需要确认，确认后写入并可审计，拒绝时不写入")
    void inventoryAndConfirmation() throws Exception {
        JsonNode shortage = run("sunkg", "vendor.scm/inventory-risk-analyst", "哪些物料低于安全库存");
        assertThat(shortage.path("status").asString()).isEqualTo("completed");
        assertThat(shortage.path("text").asString()).as(shortage.toString()).contains("M-0007");

        JsonNode suspended = run("sunkg", "vendor.scm/inventory-risk-analyst", "为 M-0007 创建补货跟进任务");
        assertThat(suspended.path("status").asString()).isEqualTo("suspended");
        assertThat(suspended.path("pending").path("title").asString()).contains("M-0007", "跟进任务");
        String runId = suspended.path("runId").asString();
        // 别人不能替他确认
        assertThat(call("POST", "/runs/" + runId + "/confirmation", login("zhangmin"), Map.of("approved", true))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");

        JsonNode done = call("POST", "/runs/" + runId + "/confirmation", login("sunkg"), Map.of("approved", true))
                .path("data");
        assertThat(done.path("status").asString()).isEqualTo("completed");
        // 确认提示先说过一遍，最终回复按顺序拼接各回合说过的话（脚本化与真实模型一致）
        assertThat(done.path("text").asString())
                .startsWith("为 M-0007 创建补货跟进任务，写入前需要你确认")
                .contains("已创建跟进任务 TASK-");
        assertThat(services.audit().list(AuditFilter.all().withAction("bo.access")))
                .anyMatch(e -> "followup_task_create".equals(e.tool()) && "allow".equals(e.decision()));
        JsonNode trace = call("GET", "/traces/" + done.path("traceId").asString(), login("sunkg"), null)
                .path("data");
        assertThat(trace.toString()).doesNotContain("\"status\":\"suspended\"");
        assertThat(kinds(trace)).contains("confirmation");

        JsonNode second = run("sunkg", "vendor.scm/inventory-risk-analyst", "为 M-0008 创建补货跟进任务");
        JsonNode rejected = call(
                        "POST",
                        "/runs/" + second.path("runId").asString() + "/confirmation",
                        login("sunkg"),
                        Map.of("approved", false))
                .path("data");
        assertThat(rejected.path("text").asString())
                .startsWith("为 M-0008 创建补货跟进任务，写入前需要你确认")
                .contains("没有创建跟进任务：用户拒绝");
    }

    @Test
    @DisplayName("追踪按查看者限定：别的租户、厂商看不到；平台运维能看到标品指令原文")
    void traceAccess() throws Exception {
        JsonNode run = run("zhangmin", "vendor.pur/supplier-performance-analyst", "百润精密最近表现怎么样");
        String path = "/traces/" + run.path("traceId").asString();
        assertThat(call("GET", path, login("zhouting"), null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(call("GET", path, login("vendordev"), null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(call("GET", path, login("platformops"), null).toString()).contains("你是供应商绩效分析专家");
        assertThat(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS).isNotBlank();
    }

    @Test
    @DisplayName("记忆：长期记忆注入后出现在追踪的提示词片段里，可以查看和清除；参数不合法时 400")
    void memoryAndValidation() throws Exception {
        String token = login("wangfang");
        call("POST", "/memory", token, Map.of("agent", "vendor.fin/period-close-checker", "text", "汇报时先说结论"));
        assertThat(call("GET", "/memory?agent=vendor.fin/period-close-checker", token, null)
                        .path("data")
                        .size())
                .isEqualTo(1);
        JsonNode run = run("wangfang", "vendor.fin/period-close-checker", "本期资产核算的结账检查还有哪些没通过");
        JsonNode trace = call("GET", "/traces/" + run.path("traceId").asString(), token, null);
        assertThat(trace.toString()).contains("汇报时先说结论");
        assertThat(call("DELETE", "/memory", token, null)
                        .path("data")
                        .path("removed")
                        .asInt())
                .isEqualTo(1);

        assertThat(call("POST", "/runs", token, Map.of("agent", "bad", "message", "x"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(call("POST", "/runs", login("vendordev"), Map.of("agent", "vendor.pur/x", "message", "x"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("运行列表：开发者看本租户全部并可按 Agent 过滤，普通用户只看自己的，厂商被拒绝")
    void listRuns() throws Exception {
        run("zhangmin", "vendor.pur/supplier-performance-analyst", "百润精密最近表现怎么样");
        JsonNode all = call(
                        "GET", "/runs?agent=vendor.pur/supplier-performance-analyst&limit=5", login("chengong"), null)
                .path("data");
        assertThat(all.size()).isGreaterThanOrEqualTo(1);
        assertThat(all.get(0).path("agent").asString()).isEqualTo("vendor.pur/supplier-performance-analyst");
        assertThat(all.get(0).path("runId").asString()).isNotEmpty();
        assertThat(all.get(0).has("journal")).isFalse();

        // 普通用户只看自己的运行：王芳没有发起过这个 Agent 的运行
        JsonNode own = call("GET", "/runs?agent=vendor.pur/supplier-performance-analyst", login("wangfang"), null)
                .path("data");
        assertThat(own.size()).isZero();

        // 不带过滤时开发者能看到其他用户的运行
        JsonNode tenant =
                call("GET", "/runs?limit=100", login("chengong"), null).path("data");
        assertThat(tenant.size()).isGreaterThanOrEqualTo(1);
        assertThat(tenant.toString()).contains("zhangmin");

        assertThat(call("GET", "/runs", login("vendordev"), null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("GET", "/runs?agent=bad%20ref", login("zhangmin"), null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
    }

    io.modelcontextprotocol.client.McpSyncClient mcpClient(String token) {
        var request = HttpRequest.newBuilder();
        if (token != null) request.header("Authorization", "Bearer " + token);
        var transport = io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport.builder(platformUrl)
                .endpoint("/mcp")
                .requestBuilder(request)
                .build();
        var client = io.modelcontextprotocol.client.McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    @Test
    @DisplayName("平台 MCP 端点：只暴露只读工具；以用户身份调用，数据按该用户的权限过滤，并留下审计；没有令牌时拒绝")
    void mcpEndpoint() throws Exception {
        var client = mcpClient(login("zhangmin"));
        try {
            var names = client.listTools().tools().stream()
                    .map(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .toList();
            assertThat(names)
                    .contains("purchase_order_list", "supplier_list", "semantic_get_metric")
                    .doesNotContain("purchase_order_change_delivery_date", "followup_task_create");
            var result = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "purchase_order_list", Map.of("overdueDaysGt", 30)));
            assertThat(result.isError()).isFalse();
            String text = ((io.modelcontextprotocol.spec.McpSchema.TextContent)
                            result.content().getFirst())
                    .text();
            assertThat(text).contains("采购组织：华东").doesNotContain("华南");
            var metric = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "semantic_get_metric", Map.of("name", "overdue_days")));
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent)
                                    metric.content().getFirst())
                            .text())
                    .contains("overdue_days");
        } finally {
            client.closeGracefully();
        }
        assertThat(services.audit().list(AuditFilter.all().withAction("bo.access")))
                .anyMatch(e -> e.agent() != null && e.agent().startsWith("platform/mcp-endpoint"));

        // 没有令牌：调用被拒绝（平台的 MCP 客户端也能连上 SDK 起的服务端，并把错误如实带回）
        var anonymous = mcpClient(null);
        try {
            var denied = anonymous.callTool(
                    new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("supplier_list", Map.of()));
            assertThat(denied.isError()).isTrue();
        } finally {
            anonymous.closeGracefully();
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> McpConnections.callUrl(platformUrl + "/mcp", "supplier_list", Map.of()))
                .hasMessageContaining("返回错误");
    }

    /** 走 HTTP 令牌端点做一次交换（与外部 Agent 的用法一致），返回原始 JSON */
    JsonNode exchange(String subjectToken, String subjectTokenType, String actorToken, String audience, String scope)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        body.put("subject_token", subjectToken);
        body.put("subject_token_type", subjectTokenType);
        if (actorToken != null) body.put("actor_token", actorToken);
        body.put("audience", audience);
        if (scope != null) body.put("scope", scope);
        return call("POST", "/identity/v1/token", null, body);
    }

    @Test
    @DisplayName("受限回调（ADR 0014）：外部 Agent 用服务令牌换只读令牌经 /mcp 代表用户查数据，审计走完身份链；令牌不能再交换，普通接口与越界调用一律拒绝")
    void mcpRestrictedCallback() throws Exception {
        String remote = "tenant.t001.remote/supplier-risk-sentiment";
        // 平台出站调用外部 Agent 时携带的服务令牌：sub 是发起用户，target 是外部 Agent
        String serviceToken =
                services.tokens().issueService("zhangmin", "t001", "agent:vendor.pur/purchase-assistant", remote);
        JsonNode exchanged = exchange(
                serviceToken,
                dev.eab.platform.identity.IdentityService.TokenType.SERVICE,
                null,
                dev.eab.platform.identity.TokenService.Audience.PLATFORM,
                null);
        assertThat(exchanged.has("error")).as(exchanged.toString()).isFalse();
        String restricted = exchanged.path("access_token").asString();
        assertThat(exchanged.path("scope").asString()).contains("purchase_order_list");
        assertThat(exchanged.path("expires_in").asInt()).isEqualTo(300);

        // 受限令牌只被 /mcp 接受：普通接口按用户令牌校验，一律拒绝
        assertThat(call("GET", "/runs", restricted, null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("INVALID_TOKEN");

        // /mcp 上代表用户调用只读工具，数据范围按 sub 用户过滤
        var client = mcpClient(restricted);
        try {
            var result = client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "purchase_order_list", Map.of("overdueDaysGt", 30)));
            assertThat(result.isError()).isFalse();
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent)
                                    result.content().getFirst())
                            .text())
                    .contains("采购组织：华东")
                    .doesNotContain("华南");
        } finally {
            client.closeGracefully();
        }

        // 身份链审计：签发受限令牌（identity.token-exchange）、受限调用（mcp.restricted-call）、DemoERP 侧的 BO 访问
        assertThat(services.audit()
                        .list(AuditFilter.all()
                                .withAction("identity.token-exchange")
                                .withDecision("allow")))
                .anyMatch(e -> "restricted".equals(e.mode()) && remote.equals(e.actor()));
        assertThat(services.audit().list(AuditFilter.all().withAction("mcp.restricted-call")))
                .anyMatch(e -> "purchase_order_list".equals(e.tool())
                        && remote.equals(e.actor())
                        && "zhangmin".equals(e.userId()));
        assertThat(services.audit().list(AuditFilter.all().withAction("bo.access")))
                .anyMatch(e -> "purchase_order_list".equals(e.tool())
                        && "zhangmin".equals(e.userId())
                        && e.agent() != null
                        && e.agent().startsWith("platform/mcp-endpoint"));

        // 受限令牌不能再次交换：把它当 subject_token 换 BO 委托令牌被拒
        String agentToken = services.identity()
                .issueAgentToken(
                        "vendor.pur/supplier-performance-analyst", "1.0.0", "t001", List.of("purchase_order_list"));
        JsonNode denied = exchange(
                restricted,
                dev.eab.platform.identity.IdentityService.TokenType.JWT,
                agentToken,
                dev.eab.platform.identity.TokenService.Audience.BO,
                "purchase_order_list");
        assertThat(denied.path("error").asString()).isEqualTo("invalid_grant");
        assertThat(denied.path("error_code").asString()).isEqualTo("WRONG_TOKEN_KIND");

        // 申请范围收窄到 supplier_list 后，越界操作在 BO 令牌签发处被拒（双保险的里层）
        JsonNode narrow = exchange(
                serviceToken,
                dev.eab.platform.identity.IdentityService.TokenType.SERVICE,
                null,
                dev.eab.platform.identity.TokenService.Audience.PLATFORM,
                "supplier_list");
        assertThat(narrow.path("scope").asString()).isEqualTo("supplier_list");
        var narrowClient = mcpClient(narrow.path("access_token").asString());
        try {
            var out = narrowClient.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                    "purchase_order_list", Map.of("overdueDaysGt", 30)));
            assertThat(out.isError()).isTrue();
            assertThat(((io.modelcontextprotocol.spec.McpSchema.TextContent)
                                    out.content().getFirst())
                            .text())
                    .contains("不在受限回调令牌的只读范围内");
        } finally {
            narrowClient.closeGracefully();
        }
    }
}

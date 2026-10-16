package dev.eab.platform.hub;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformProperties;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.registry.AssetSeeder;
import dev.eab.spec.Json;
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
 * 故事 D、F 的后端端到端测试（P8）：平台与 DemoERP 经随机端口互通，加载仓库全部资产（含租户完成态快照），模型为脚本化模型。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GovernanceEndToEndTest {

    static final String SECRET = "governance-e2e-secret";
    static final String ANALYST = "vendor.pur/supplier-performance-analyst";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    String changeId;

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
        var assets = platform.getBean(PlatformProperties.class).resolvedAssetsDir();
        var seeded = AssetSeeder.seed(services.registry(), assets, List.of("tenants/t001"));
        assertThat(seeded.failed()).isEmpty();
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

    static String text(JsonNode assist) {
        for (JsonNode e : assist.path("events"))
            if (e.path("type").asString().equals("final"))
                return e.path("data").path("text").asString();
        return "";
    }

    /** 统一入口提问；遇到确认点时由本人确认，返回最终文本 */
    String ask(String user, String message) throws Exception {
        return ask(user, message, null);
    }

    /** target 非空时相当于用户在澄清卡片上选定了能力 */
    String ask(String user, String message, String target) throws Exception {
        Map<String, Object> body =
                target == null ? Map.of("message", message) : Map.of("message", message, "target", target);
        JsonNode result = ok(call("POST", "/assist", user, body));
        String finalText = text(result);
        String runId = result.path("runId").asString();
        for (int i = 0; i < 3 && finalText.isEmpty() && !runId.isEmpty(); i++) {
            JsonNode confirmed = ok(call("POST", "/runs/" + runId + "/confirmation", user, Map.of("approved", true)));
            finalText = confirmed.path("text").asString();
        }
        return finalText;
    }

    record Asked(String runId, String traceId, String text) {}

    /** 统一入口提问并依次确认确认点（故事 B 的扩展发布后有 IM 通知、跟进任务两个确认点），返回运行编号、追踪和最终文本 */
    Asked askRun(String user, String message) throws Exception {
        JsonNode result = ok(call("POST", "/assist", user, Map.of("message", message)));
        String finalText = text(result);
        String runId = result.path("runId").asString();
        for (int i = 0; i < 3 && finalText.isEmpty() && !runId.isEmpty(); i++) {
            JsonNode confirmed = ok(call("POST", "/runs/" + runId + "/confirmation", user, Map.of("approved", true)));
            finalText = confirmed.path("text").asString();
        }
        return new Asked(runId, result.path("traceId").asString(), finalText);
    }

    // ── 故事 D ──

    @Test
    @Order(1)
    @DisplayName("故事 D：张敏和李强问同一个问题，结果按各自的采购组织过滤，并注明可见范围")
    void scopedResults() throws Exception {
        String zhang = ask("zhangmin", "列出我们逾期超过 30 天的供应商", ANALYST);
        String li = ask("liqiang", "列出我们逾期超过 30 天的供应商", ANALYST);
        assertThat(zhang).contains("已按你的数据权限过滤，可见范围：采购组织：华东").doesNotContain("（华南）");
        assertThat(li).contains("可见范围：采购组织：华南").doesNotContain("（华东）");
    }

    @Test
    @Order(2)
    @DisplayName("故事 D：集成账号对照——BO API 按用户过滤，Open API 返回两个采购组织的全部数据")
    void integrationCompare() throws Exception {
        JsonNode r = ok(call("GET", "/hub/demo/integration-compare?user=zhangmin", "zhaoadmin", null));
        assertThat(r.path("bo").path("filtered").asBoolean()).isTrue();
        assertThat(r.path("bo").path("byOrg").size()).isEqualTo(1);
        assertThat(r.path("open").path("filtered").asBoolean()).isFalse();
        assertThat(r.path("open").path("byOrg").size()).isEqualTo(2);
        assertThat(r.path("open").path("total").asInt())
                .isGreaterThan(r.path("bo").path("total").asInt());
    }

    @Test
    @Order(3)
    @DisplayName("故事 D：改交期确认后执行；再让 Agent 审批自己提交的变更单，被职责分离拒绝，审计有记录")
    void sodDenied() throws Exception {
        String changed = ask("zhangmin", "把 PO-2026-0117 的交期改到下月 15 号");
        assertThat(changed).contains("已提交交期变更单");
        var m = java.util.regex.Pattern.compile("DC-\\d{4}-\\d{4}").matcher(changed);
        assertThat(m.find()).isTrue();
        changeId = m.group();
        String approved = ask("zhangmin", "把交期变更单 " + changeId + " 审批通过");
        assertThat(approved).contains("没有审批交期变更单 " + changeId).contains("职责分离");
        assertThat(services.audit().list(AuditFilter.all().withDecision("deny")))
                .anyMatch(e -> "purchase_order_change_approve".equals(e.tool()));
    }

    @Test
    @Order(4)
    @DisplayName("故事 D：撤销库存 Agent 的委托后触发库存事件，Agent 被拒绝运行，审计有记录")
    void revokedDelegation() throws Exception {
        JsonNode delegations = ok(call("GET", "/identity/v1/delegations", "zhaoadmin", null));
        String id = delegations.get(0).path("id").asString();
        ok(call("DELETE", "/identity/v1/delegations/" + id, "zhaoadmin", null));
        ok(call("POST", "/demo/v1/events", "zhaoadmin", Map.of("type", "inventory.below_safety_stock")));
        boolean denied = false;
        for (int i = 0; i < 50 && !denied; i++) {
            denied = services.audit().list(AuditFilter.all().withAction("trigger.event")).stream()
                    .anyMatch(
                            e -> e.decision().equals("deny") && e.target().equals("vendor.scm/inventory-risk-analyst"));
            if (!denied) Thread.sleep(200);
        }
        assertThat(denied).isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("故事 D：审计反查——PO-2026-0117 的交期是张敏通过采购订单处理 Agent 改的，能定位到那次运行")
    void reverseLookup() throws Exception {
        JsonNode findings = ok(call("GET", "/hub/audit/reverse?object=PO-2026-0117", "zhaoadmin", null));
        JsonNode change = null;
        for (JsonNode f : findings) {
            if ("purchase_order_change_delivery_date".equals(f.path("operation").asString())
                    && f.path("decision").asString().equals("allow")) change = f;
        }
        assertThat(change).as(findings.toString()).isNotNull();
        assertThat(change.path("userId").asString()).isEqualTo("zhangmin");
        assertThat(change.path("agent").asString()).startsWith("vendor.pur/purchase-order-clerk");
        assertThat(change.path("runId").asString()).startsWith("run-");
    }

    // ── 故事 F ──

    @Test
    @Order(10)
    @DisplayName("故事 F：发布标品 1.3.0，自动回归——扩展的钩子用例因字段改名失败，租户收到通知")
    void upgradeRegression() throws Exception {
        JsonNode results = ok(call("POST", "/hub/demo/upgrade", "zhaoadmin", null));
        JsonNode r = results.get(0);
        assertThat(r.path("extension").asString()).startsWith("tenant.t001.finance/anomaly-analyst-ext");
        assertThat(r.path("status").asString()).isEqualTo("failed");
        assertThat(r.path("regressions").toString()).contains("followup-hook-fires");
        assertThat(r.path("issues").toString()).contains("HOOK_CONDITION_INVALID");
        assertThat(ok(call("GET", "/notifications", "zhaoadmin", null)).toString())
                .contains("标品升级回归未通过");
        assertThat(ok(call("GET", "/notifications", "chengong", null)).toString())
                .contains("followup-hook-fires");
        // 再升一次同一版本被拒绝
        assertThat(call("POST", "/hub/demo/upgrade", "zhaoadmin", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("UPGRADE_FAILED");
        assertThat(ok(call("GET", "/hub/upgrades", "zhaoadmin", null))
                        .path("regressions")
                        .size())
                .isGreaterThan(0);
    }

    @Test
    @Order(11)
    @DisplayName("故事 F：版本差异——租户视角下标品指令只显示“已变更”，输出字段改名可见")
    void versionDiff() throws Exception {
        JsonNode diff = ok(call(
                "GET",
                "/hub/assets/diff?ref=vendor.fin/transaction-anomaly-analyst&from=1.2.0&to=1.3.0",
                "zhaoadmin",
                null));
        String json = diff.toString();
        assertThat(json).contains("spec.instructions", "已变更", "riskLevel").doesNotContain("你是交易异常解析专家");
        boolean instructionsRedacted = false;
        for (JsonNode c : diff.path("changes")) {
            if (c.path("path").asString().equals("spec.instructions"))
                instructionsRedacted = c.path("redacted").asBoolean();
        }
        assertThat(instructionsRedacted).isTrue();
    }

    @Test
    @Order(12)
    @DisplayName("故事 F：资产清单与血缘、三层指标与岗位助手 KPI")
    void inventoryAndMetrics() throws Exception {
        JsonNode inv = ok(call("GET", "/hub/inventory", "zhaoadmin", null));
        assertThat(inv.path("edges").toString())
                .contains("tenant.t001.finance/anomaly-analyst-ext", "vendor.fin/transaction-anomaly-analyst", "扩展");
        assertThat(inv.path("items").toString()).contains("\"source\":\"vendor\"", "\"source\":\"extension\"");
        ok(call("POST", "/hub/demo/history", "zhaoadmin", null));
        JsonNode metrics = ok(call("GET", "/hub/metrics?days=30", "zhaoadmin", null));
        assertThat(metrics.path("agents").size()).isGreaterThan(3);
        assertThat(metrics.path("daily").size()).isGreaterThanOrEqualTo(29);
        assertThat(metrics.path("process").toString()).contains("谈判建议采纳率", "异常处理时长");
        assertThat(metrics.path("business").toString()).contains("谈判节省金额");
        assertThat(metrics.path("assistants").toString()).contains("准时交付率");
    }

    @Test
    @Order(13)
    @DisplayName("故事 F：经验回流——王芳指出跨期冲销不算异常，审核通过后重新提问结论改变，追踪显示规则来自租户语义包")
    void feedbackLoop() throws Exception {
        Asked first = askRun("wangfang", "本月有哪些高风险交易异常");
        String before = first.text();
        assertThat(before).contains("reversal");
        JsonNode fb = ok(call("POST", "/feedback", "wangfang", Map.of("runId", first.runId(), "reason", "这类跨期冲销不算异常")));
        assertThat(fb.path("decidedBy").asString()).isEqualTo("model");
        assertThat(fb.path("candidate").asString()).contains("transaction_anomaly_list");
        // 业务用户不能审核
        assertThat(call("POST", "/hub/feedback/" + fb.path("id").asString() + "/approve", "wangfang", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        JsonNode approved = ok(call(
                "POST", "/hub/feedback/" + fb.path("id").asString() + "/approve", "zhaoadmin", Map.of("note", "确认")));
        assertThat(approved.path("packRef").asString()).isEqualTo("tenant.t001.semantic/finance-ext@1.0.0");

        Asked second = askRun("wangfang", "本月有哪些高风险交易异常");
        String after = second.text();
        assertThat(after).startsWith("按规则 T001-R-001").doesNotContain("reversal");
        JsonNode trace = ok(call("GET", "/traces/" + second.traceId(), "wangfang", null));
        assertThat(trace.path("spans").toString())
                .contains("约束 · T001-R-001 触发", "tenant.t001.semantic/finance-ext@1.0.0");
    }

    // ── 方案包、评测、准入、权限 ──

    @Test
    @Order(20)
    @DisplayName("方案包：从扩展生成方案并编译（清单、哈希、签名），按 dev、test、prod 部署；篡改后拒绝部署")
    void solutionPackages() throws Exception {
        JsonNode pkg = ok(call(
                "POST",
                "/hub/solutions/compile",
                "zhaoadmin",
                Map.of("name", "finance-extension", "root", "tenant.t001.finance/anomaly-analyst-ext")));
        assertThat(pkg.path("manifest").path("assets").toString())
                .contains("anomaly-analyst-ext", "create-followup-task", "anomaly-ext-smoke");
        assertThat(pkg.path("manifest").path("requires").toString()).contains("vendor.fin/transaction-anomaly-analyst");
        String id = pkg.path("id").asString();
        assertThat(call("POST", "/hub/solutions/packages/" + id + "/deploy", "zhaoadmin", Map.of("environment", "prod"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("GATE_BLOCKED");
        for (String env : List.of("dev", "test", "prod"))
            ok(call("POST", "/hub/solutions/packages/" + id + "/deploy", "zhaoadmin", Map.of("environment", env)));
        JsonNode list = ok(call("GET", "/hub/solutions", "zhaoadmin", null));
        assertThat(list.get(0).path("verified").asBoolean()).isTrue();
        assertThat(list.get(0).path("environments").has("prod")).isTrue();
        // 同一方案同一版本只编译一次
        assertThat(ok(call(
                                "POST",
                                "/hub/solutions/compile",
                                "zhaoadmin",
                                Map.of("name", "finance-extension", "root", "tenant.t001.finance/anomaly-analyst-ext")))
                        .path("id")
                        .asString())
                .isEqualTo(id);
        services.db()
                .jdbc()
                .sql(
                        "UPDATE solution_packages SET manifest = replace(manifest, 'anomaly-ext-smoke', 'anomaly-ext-tampered') WHERE id = ?")
                .param(id)
                .update();
        assertThat(call("POST", "/hub/solutions/packages/" + id + "/deploy", "zhaoadmin", Map.of("environment", "dev"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("INTEGRITY_VIOLATION");
    }

    @Test
    @Order(19)
    @DisplayName("评测：列出冒烟集并运行标品公开评测集；上架准入列出待上架的租户资产与检查；业务用户和开发者的越权操作被拒绝")
    void evalsAdmissionAndPermissions() throws Exception {
        JsonNode suites = ok(call("GET", "/hub/evals/suites?tier=smoke", "zhaoadmin", null));
        assertThat(suites.toString()).contains("vendor.fin.evals/anomaly-public");
        JsonNode run =
                ok(call("POST", "/hub/evals/run", "zhaoadmin", Map.of("suite", "vendor.fin.evals/anomaly-public")));
        assertThat(run.path("result").path("status").asString()).isEqualTo("passed");
        JsonNode admission = ok(call("GET", "/hub/admission", "zhaoadmin", null));
        assertThat(admission.toString()).contains("评测门禁", "工具写级别合规", "MCP Server 已审批");
        assertThat(call("GET", "/hub/inventory", "zhangmin", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("POST", "/hub/demo/history", "chengong", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(ok(call("GET", "/hub/inventory", "chengong", null))
                        .path("items")
                        .size())
                .isGreaterThan(0);
        assertThat(call("GET", "/hub/inventory?tenant=t002", "platformops", null)
                        .path("success")
                        .asBoolean())
                .isTrue();
    }
}

package dev.eab.platform.extend;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.Json;
import dev.eab.spec.testing.RepoPaths;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * 故事 E 的端到端测试：张敏要的事标品采购订单处理 Agent 办不了，回复如实说明缺少的能力，结构化输出 capabilityGap，
 * 统一入口补发“提交扩展需求”卡片；张敏补上大额请购须人工审批后提交需求。陈工在 Studio 里发布备选供应商补货技能
 * （单独测试停在确认点、不写入），再建扩展：追加规定并挂 PUR-R-008、加请购单草稿工具与技能、金额超 10 万元须确认；
 * 扩展评测集在确认点被拒绝的评测策略下也要通过，发布后对本租户即时生效。张敏重新提问，确认后按备选供应商的
 * 协议价创建请购单草稿，拒绝时说明没有创建。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StoryEEndToEndTest {

    static final String SECRET = "story-e-e2e-secret";
    static final String TARGET = "vendor.pur/purchase-order-clerk";
    static final String QUESTION = "百润精密这批货到不了，帮我从备选供应商里找有现货的下单";
    /** 标品指令原文的第一句，租户在任何接口里都不应该看到 */
    static final String VENDOR_TEXT = "你是采购订单处理助手";
    /**
     * 扩展追加的指令，与仓库快照一致。不在指令里引用规则编号：生成评测集时，确定性裁判会要求回复中出现
     * 指令里引用的规则号，而回复永远不会写规则号。规则经 constraints 挂 PUR-R-008，运行时一样注入。
     */
    static final String APPEND = """
            本公司规定：金额超过 10 万元的请购单草稿，提交前必须向用户展示供应商、数量与金额并取得人工确认。
            采购订单交付出现问题、需要从备选供应商补货时，调用 alternative-supplier-lookup 技能并传入订单号 poId：
            技能会查询订单缺口和现货足够的认证备选供应商，有现货时按协议价创建请购单草稿（提交前会请用户确认），
            没有现货时建议对物料发起询价流程。不要直接调用 purchase_requisition_draft_create，
            请购数量、协议价和金额由技能按订单缺口与备选记录计算。""";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();
    String skillDraftId;
    String extDraftId;

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
        JsonNode response = call("POST", "/assist", user, Map.of("message", message));
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    JsonNode ok(JsonNode response) {
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    @Test
    @Order(1)
    @DisplayName("扩展发布前：张敏提问，Agent 如实说明缺少的能力并输出 capabilityGap，入口补发“提交扩展需求”卡片；张敏补上大额审批要求后提交需求")
    void capabilityGapBeforeExtension() throws Exception {
        JsonNode result = ask("zhangmin", QUESTION);
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        JsonNode routing = null;
        JsonNode gap = null;
        for (JsonNode event : result.path("events")) {
            if (event.path("type").asString().equals("routing")) routing = event.path("data");
            if (event.path("type").asString().equals("gap")) gap = event.path("data");
        }
        assertThat(routing).as(result.toString()).isNotNull();
        assertThat(routing.path("target").asString()).isEqualTo(TARGET);
        assertThat(gap).as(result.toString()).isNotNull();
        assertThat(gap.path("originalText").asString()).isEqualTo(QUESTION);
        assertThat(gap.path("message").asString()).contains("还没有完成这件事的能力", "备选供应商");

        JsonNode run = ok(call("GET", "/runs/" + result.path("runId").asString(), "zhangmin", null));
        assertThat(run.path("status").asString()).isEqualTo("completed");
        assertThat(run.path("result").path("text").asString()).contains("没有查询物料认证备选供应商的能力");
        assertThat(run.path("result").path("output").path("capabilityGap").asBoolean())
                .isTrue();
        assertThat(run.path("result").path("output").path("missingCapability").asString())
                .contains("备选供应商");

        // 张敏补上金额审批要求后提交扩展需求，陈工在 Studio 的需求列表里看到它
        JsonNode submitted = ok(call(
                "POST",
                "/dev-requests",
                "zhangmin",
                Map.of("text", "百润精密这批货到不了，帮我从备选供应商里找有现货的下单；金额超过 10 万元的请购单草稿必须人工审批", "assistant", TARGET)));
        assertThat(submitted.path("ref").asString()).startsWith("tenant.t001.requests/req-");
        JsonNode home = ok(call("GET", "/studio/home", "chengong", null));
        assertThat(home.path("devRequests").toString()).contains("zhangmin", "submitted", "金额超过 10 万元的请购单草稿必须人工审批");
    }

    @Test
    @Order(2)
    @DisplayName("陈工发布技能：新建草稿、贴入与仓库快照一致的 YAML、单独测试停在确认点不写入、发布到租户命名空间")
    void authorAndPublishSkill() throws Exception {
        JsonNode created = ok(call(
                "POST",
                "/studio/skills",
                "chengong",
                Map.of("name", "alternative-supplier-lookup", "displayName", "备选供应商补货")));
        skillDraftId = created.path("draft").path("id").asString();
        String yaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/pur/skills/alternative-supplier-lookup.yaml"),
                StandardCharsets.UTF_8);
        ok(call("PUT", "/studio/drafts/" + skillDraftId + "/yaml", "chengong", Map.of("yaml", yaml)));

        JsonNode test = ok(call(
                "POST",
                "/studio/drafts/" + skillDraftId + "/skill-test",
                "chengong",
                Map.of("input", Map.of("poId", "PO-2026-0117"), "asUser", "zhangmin")));
        assertThat(test.path("status").asString()).as(test.toString()).isEqualTo("confirmation");
        assertThat(test.path("pending").asString()).contains("purchase_requisition_draft_create");

        JsonNode published =
                ok(call("POST", "/studio/drafts/" + skillDraftId + "/skill-publish", "chengong", Map.of()));
        assertThat(published.path("ref").asString()).isEqualTo("tenant.t001.skills/alternative-supplier-lookup");
        assertThat(published.path("version").asString()).isEqualTo("1.0.0");
    }

    @Test
    @Order(3)
    @DisplayName("陈工建扩展：追加规定并挂 PUR-R-008、加请购工具与补货技能、超 10 万元须确认；评测集在确认点被拒绝时也通过，发布后生效")
    void authorAndPublishExtension() throws Exception {
        JsonNode created = ok(call("POST", "/studio/extensions", "chengong", Map.of("target", TARGET)));
        extDraftId = created.path("draft").path("id").asString();
        assertThat(created.path("yaml").asString()).contains("kind: AgentExtension", "target: " + TARGET + "@^1.0");

        JsonNode draft = ok(call(
                "POST",
                "/studio/drafts/" + extDraftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "instructions",
                        "value",
                        Map.of("append", APPEND, "constraints", List.of("PUR-R-008")))));
        assertThat(draft.path("issues")).isEmpty();
        ok(call(
                "POST",
                "/studio/drafts/" + extDraftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "tools",
                        "value",
                        Map.of(
                                "add",
                                List.of(Map.of(
                                        "api",
                                        Map.of(
                                                "source",
                                                "vendor.erp.tools/demo-erp-bo",
                                                "tools",
                                                List.of("purchase_requisition_draft_create"))))))));
        ok(call(
                "POST",
                "/studio/drafts/" + extDraftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "skills",
                        "value",
                        Map.of("add", List.of("tenant.t001.skills/alternative-supplier-lookup")))));
        ok(call(
                "POST",
                "/studio/drafts/" + extDraftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "approvals",
                        "value",
                        Map.of(
                                "add",
                                List.of(Map.of(
                                        "tool",
                                        "purchase_requisition_draft_create",
                                        "policy",
                                        "require-confirmation",
                                        "when",
                                        "input.amount > 100000",
                                        "description",
                                        "金额超过 10 万元的请购单草稿须人工确认（本公司大额请购规定）"))))));

        JsonNode effective = ok(call("GET", "/studio/drafts/" + extDraftId + "/effective", "chengong", null));
        assertThat(effective.path("ok").asBoolean()).as(effective.toString()).isTrue();
        assertThat(effective.path("view").toString())
                .contains("tenant-instructions", "alternative-supplier-lookup", "PUR-R-008")
                .doesNotContain(VENDOR_TEXT);

        JsonNode lint = ok(call("POST", "/studio/drafts/" + extDraftId + "/extension/lint", "chengong", null));
        assertThat(lint.path("ok").asBoolean()).as(lint.toString()).isTrue();

        // 没有扩展评测集时不能发布
        assertThat(call("POST", "/studio/drafts/" + extDraftId + "/extension/publish", "chengong", Map.of())
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NO_EVALS");

        // 评测集对确认结果不变：技能走了、回复说明请购单草稿的处理结果（评测里确认点一律拒绝）
        String evalYaml = """
                apiVersion: eab.dev/v1
                kind: EvalSuite
                metadata:
                  name: purchase-clerk-ext-smoke
                  namespace: tenant.t001.evals
                  version: 0.1.0
                  displayName: 采购订单处理扩展 · 冒烟集
                  owner: chengong
                spec:
                  target: tenant.t001.extensions/purchase-order-clerk-ext
                  visibility: internal
                  tier: smoke
                  minPassRate: 1
                  cases:
                    - id: alternative-requisition
                      user: zhangmin
                      question: 百润精密这批货到不了，帮我从备选供应商里找有现货的下单
                      assertions:
                        - { type: calledTool, tool: alternative-supplier-lookup, description: 走备选供应商补货技能 }
                        - { type: contains, text: 请购单草稿, description: 回复说明请购单草稿的处理结果；评测里确认点被拒绝时说明原因 }
                """;
        ok(call("PUT", "/studio/drafts/" + extDraftId + "/extension/evals", "chengong", Map.of("yaml", evalYaml)));

        JsonNode evals = ok(call("POST", "/studio/drafts/" + extDraftId + "/extension/evals/run", "chengong", null));
        List<String> suites = new ArrayList<>();
        evals.path("suites")
                .forEach(s -> suites.add(
                        s.path("suite").asString() + ":" + s.path("ok").asBoolean()));
        assertThat(suites).as(evals.toString()).contains("tenant.t001.evals/purchase-clerk-ext-smoke:true");
        assertThat(evals.path("status").asString()).as(evals.toString()).isEqualTo("passed");

        JsonNode published =
                ok(call("POST", "/studio/drafts/" + extDraftId + "/extension/publish", "chengong", Map.of()));
        assertThat(published.path("ref").asString()).isEqualTo("tenant.t001.extensions/purchase-order-clerk-ext");
        assertThat(published.path("version").asString()).isEqualTo("0.1.0");
        assertThat(services.audit().list(AuditFilter.all().withAction("studio.extension.publish")))
                .isNotEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("故事 E：张敏重新提问，扩展生效，金额超 10 万元先确认，确认后按备选供应商协议价创建请购单草稿")
    void confirmAndCreateRequisition() throws Exception {
        JsonNode result = ask("zhangmin", QUESTION);
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        String runId = result.path("runId").asString();
        JsonNode run = ok(call("GET", "/runs/" + runId, "zhangmin", null));
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("suspended");
        assertThat(run.path("pending").toString()).contains("purchase_requisition_draft_create");

        JsonNode done = ok(call("POST", "/runs/" + runId + "/confirmation", "zhangmin", Map.of("approved", true)));
        assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("completed");
        assertThat(done.path("text").asString()).contains("PR-2026-", "S-0009", "112000");
        assertThat(done.path("output").path("requisitionCreated").asBoolean()).isTrue();
        assertThat(done.path("output").path("requisitionId").asString()).isEqualTo("PR-2026-0001");

        JsonNode trace = ok(call("GET", "/traces/" + result.path("traceId").asString(), "zhangmin", null));
        assertThat(trace.toString())
                .contains("tenant-instructions", "alternative-supplier-lookup", "purchase_requisition_draft_create")
                .doesNotContain(VENDOR_TEXT);
    }

    @Test
    @Order(5)
    @DisplayName("拒绝确认：大额请购的确认被拒绝时不写入，回复说明没有创建请购单草稿")
    void rejectConfirmation() throws Exception {
        JsonNode result = ask("zhangmin", QUESTION);
        String runId = result.path("runId").asString();
        JsonNode run = ok(call("GET", "/runs/" + runId, "zhangmin", null));
        assertThat(run.path("status").asString()).as(run.toString()).isEqualTo("suspended");
        JsonNode done = ok(call("POST", "/runs/" + runId + "/confirmation", "zhangmin", Map.of("approved", false)));
        assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("completed");
        assertThat(done.path("text").asString()).contains("没有创建请购单草稿", "用户拒绝");
        assertThat(done.path("output").path("requisitionCreated").asBoolean()).isFalse();
    }
}

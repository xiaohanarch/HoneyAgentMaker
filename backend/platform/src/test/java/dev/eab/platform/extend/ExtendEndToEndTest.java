package dev.eab.platform.extend;

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
 * 故事 B 的后端端到端测试：陈工在标品 Agent 目录里看到交易异常解析 Agent（指令原文不可见），新建扩展：追加责任部门要求、
 * 增加 IM 通知工具并要求确认、挂 onResult 钩子；有效定义预览里标品部分是占位块；发布前强制跑标品公开评测集和扩展评测集；
 * 发布后王芳在统一入口提问，扩展生效，IM 通知前需要确认。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtendEndToEndTest {

    static final String SECRET = "extend-e2e-secret";
    static final String TARGET = "vendor.fin/transaction-anomaly-analyst";
    /** 标品指令原文的第一句，租户在任何接口里都不应该看到 */
    static final String VENDOR_TEXT = "你是交易异常解析专家";

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
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort,
                        "--eab.connections.IM_WEBHOOK=" + platformUrl + "/channels/im");
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

    /** 依次确认运行中的确认点，直到运行结束；返回最后的结果，pending 里依次出现过的确认标题记在 titles 里 */
    JsonNode confirmAll(String path, JsonNode run, String user, List<String> titles) throws Exception {
        JsonNode current = run;
        for (int i = 0; i < 5 && current.path("status").asString().equals("suspended"); i++) {
            titles.add(current.path("pending").toString());
            current = ok(call(
                    "POST", path.replace("{runId}", current.path("runId").asString()), user, Map.of("approved", true)));
        }
        return current;
    }

    JsonNode ok(JsonNode response) {
        assertThat(response.path("success").asBoolean()).as(response.toString()).isTrue();
        return response.path("data");
    }

    @Test
    @Order(1)
    @DisplayName("标品 Agent 目录：租户看到描述、扩展点、工具清单和公开评测集，看不到指令原文")
    void catalog() throws Exception {
        JsonNode list = ok(call("GET", "/studio/vendor-agents", "chengong", null));
        assertThat(list.toString())
                .contains(TARGET, "instructions.append", "vendor.fin.evals/anomaly-public")
                .doesNotContain(VENDOR_TEXT);
        JsonNode detail =
                ok(call("GET", "/studio/vendor-agents/vendor.fin/transaction-anomaly-analyst", "chengong", null));
        assertThat(detail.toString()).contains("标品指令（不可见，运行时注入）").doesNotContain(VENDOR_TEXT);
        assertThat(call("GET", "/studio/vendor-agents/vendor.fin/no-such-agent", "chengong", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
        assertThat(call("GET", "/studio/vendor-agents", "wangfang", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    @Order(2)
    @DisplayName("扩展编辑：按扩展点分页编辑；有效定义预览里标品指令是占位块，租户追加指令带来源；未声明的扩展点被静态检查拦下")
    void editExtension() throws Exception {
        JsonNode created = ok(call("POST", "/studio/extensions", "chengong", Map.of("target", TARGET)));
        draftId = created.path("draft").path("id").asString();
        assertThat(created.path("yaml").asString()).contains("kind: AgentExtension", "target: " + TARGET + "@^1.0");

        ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of("section", "instructions", "value", Map.of("append", "本公司规定：单笔金额超过 50 万元的异常，结论中必须给出责任部门。"))));
        ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of("section", "tools", "value", Map.of("add", List.of("tenant.t001.tools/notify-im")))));
        ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "hooks",
                        "value",
                        Map.of(
                                "onResult",
                                Map.of(
                                        "workflow",
                                        "tenant.t001.wf/create-followup-task",
                                        "when",
                                        "result.severity in ['high','critical']")))));
        JsonNode draft = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "approvals",
                        "value",
                        Map.of(
                                "add",
                                List.of(Map.of(
                                        "tool",
                                        "tenant.t001.tools/notify-im",
                                        "policy",
                                        "require-confirmation",
                                        "description",
                                        "发 IM 通知前确认"))))));
        assertThat(draft.path("issues")).isEmpty();

        JsonNode effective = ok(call("GET", "/studio/drafts/" + draftId + "/effective", "chengong", null));
        assertThat(effective.path("ok").asBoolean()).as(effective.toString()).isTrue();
        String view = effective.path("view").toString();
        assertThat(view)
                .contains("标品指令（不可见，运行时注入）", "单笔金额超过 50 万元", "tenant-instructions", "notify_im")
                .doesNotContain(VENDOR_TEXT);

        JsonNode lint = ok(call("POST", "/studio/drafts/" + draftId + "/extension/lint", "chengong", null));
        assertThat(lint.path("ok").asBoolean()).as(lint.toString()).isTrue();

        // 挂在标品没有开放的事件上：静态检查报错
        JsonNode bad = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "hooks",
                        "value",
                        Map.of("beforeToolCall", Map.of("workflow", "tenant.t001.wf/create-followup-task")))));
        assertThat(bad.path("yaml").asString()).contains("beforeToolCall");
        JsonNode badLint = ok(call("POST", "/studio/drafts/" + draftId + "/extension/lint", "chengong", null));
        assertThat(badLint.path("ok").asBoolean()).isFalse();
        ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension",
                "chengong",
                Map.of(
                        "section",
                        "hooks",
                        "value",
                        Map.of(
                                "onResult",
                                Map.of(
                                        "workflow",
                                        "tenant.t001.wf/create-followup-task",
                                        "when",
                                        "result.severity in ['high','critical']")))));
    }

    @Test
    @Order(3)
    @DisplayName("私有环境测试：扩展草稿叠加生效，IM 通知前需要确认，结论里有责任部门")
    void privateTest() throws Exception {
        JsonNode run = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension/test",
                "chengong",
                Map.of("message", "本月有哪些高风险交易异常", "asUser", "wangfang")));
        List<String> titles = new ArrayList<>();
        JsonNode done = confirmAll("/studio/test-runs/{runId}/confirmation", run, "chengong", titles);
        assertThat(titles).as(run.toString()).anyMatch(t -> t.contains("notify_im"));
        assertThat(done.path("status").asString()).isEqualTo("completed");
        assertThat(done.path("text").asString()).as(done.toString()).contains("责任部门", "ANO-2026-0003");
    }

    @Test
    @Order(4)
    @DisplayName("发布前强制评测：标品公开评测集和扩展评测集都要通过；发布后扩展对本租户生效")
    void evalsAndPublish() throws Exception {
        // 没有扩展评测集时不能发布
        assertThat(call("POST", "/studio/drafts/" + draftId + "/extension/publish", "chengong", Map.of())
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NO_EVALS");
        JsonNode generated = ok(call(
                "POST",
                "/studio/drafts/" + draftId + "/extension/evals/generate",
                "chengong",
                Map.of("testUser", "wangfang")));
        assertThat(generated.path("evalYaml").asString()).contains("calledTool", "notify_im");

        JsonNode evals = ok(call("POST", "/studio/drafts/" + draftId + "/extension/evals/run", "chengong", null));
        List<String> suites = new ArrayList<>();
        evals.path("suites")
                .forEach(s -> suites.add(
                        s.path("suite").asString() + ":" + s.path("ok").asBoolean()));
        assertThat(suites).as(evals.toString()).contains("vendor.fin.evals/anomaly-public:true");
        assertThat(evals.path("status").asString()).as(evals.toString()).isEqualTo("passed");

        JsonNode published = ok(call("POST", "/studio/drafts/" + draftId + "/extension/publish", "chengong", Map.of()));
        assertThat(published.path("ref").asString())
                .isEqualTo("tenant.t001.extensions/transaction-anomaly-analyst-ext");
        assertThat(published.path("evalRun").asString())
                .isEqualTo(evals.path("id").asString());
        assertThat(services.audit().list(AuditFilter.all().withAction("studio.extension.publish")))
                .isNotEmpty();

        JsonNode detail =
                ok(call("GET", "/studio/vendor-agents/vendor.fin/transaction-anomaly-analyst", "chengong", null));
        assertThat(detail.path("summary").path("extensions").toString()).contains("published");
    }

    @Test
    @Order(5)
    @DisplayName("故事 B：王芳在统一入口问本月高风险交易异常，扩展生效，IM 通知前弹出确认，确认后结论给出责任部门")
    void usedFromAssistant() throws Exception {
        JsonNode result = ok(call("POST", "/assist", "wangfang", Map.of("message", "本月有哪些高风险交易异常")));
        assertThat(result.path("kind").asString()).as(result.toString()).isEqualTo("run");
        JsonNode run = ok(call("GET", "/runs/" + result.path("runId").asString(), "wangfang", null));
        assertThat(run.path("status").asString()).isEqualTo("suspended");
        List<String> titles = new ArrayList<>();
        JsonNode first = Json.MAPPER
                .createObjectNode()
                .put("status", "suspended")
                .put("runId", result.path("runId").asString())
                .set("pending", run.path("pending"));
        JsonNode done = confirmAll("/runs/{runId}/confirmation", first, "wangfang", titles);
        assertThat(titles).anyMatch(t -> t.contains("notify_im"));
        assertThat(done.path("text").asString()).as(done.toString()).contains("责任部门");
        JsonNode trace = ok(call("GET", "/traces/" + result.path("traceId").asString(), "wangfang", null));
        assertThat(trace.toString()).contains("tenant-instructions").doesNotContain(VENDOR_TEXT);
    }

    @Test
    @Order(6)
    @DisplayName("复制为租户 Agent：副本只含租户可见部分，指令是公开版，并提示不再随标品升级")
    void copyAsTenant() throws Exception {
        JsonNode copy =
                ok(call("POST", "/studio/vendor-agents/vendor.fin/transaction-anomaly-analyst/copy", "chengong", null));
        String yaml = copy.path("draft").path("yaml").asString();
        assertThat(yaml)
                .contains(
                        "namespace: tenant.t001.agents",
                        "transaction-anomaly-analyst-copy",
                        "copiedFrom",
                        "取出指定期间的交易异常清单")
                .doesNotContain(VENDOR_TEXT, "extensionPoints", "不得根据异常类型直接推断个人舞弊");
        assertThat(copy.path("notice").asString()).contains("不再随标品升级");
        assertThat(copy.path("draft").path("issues")).isEmpty();
    }
}

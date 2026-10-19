package dev.eab.platform.console;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.testing.Fixtures;
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
 * 演示控制台（P10）的后端端到端测试：平台与 DemoERP 经随机端口互通，验证重置、故事起点、
 * 一键完成态的语义。启动时只灌入常规资产（完成态快照由控制台按需发布），模型为脚本化模型。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoConsoleEndToEndTest {

    static final String SECRET = "console-e2e-secret";
    static final String ANALYST = "vendor.pur/supplier-performance-analyst";

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
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

    static String text(JsonNode assist) {
        for (JsonNode e : assist.path("events"))
            if (e.path("type").asString().equals("final"))
                return e.path("data").path("text").asString();
        return "";
    }

    /** 统一入口提问；遇到确认点时由本人确认，返回最终文本 */
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

    @Test
    @Order(1)
    @DisplayName("重置：清空运行、通知、扩展需求并重建演示委托，DemoERP 数据重新生成后业务照常")
    void resetClearsRunData() throws Exception {
        // 先造运行期数据：一次提问 + 一份扩展需求
        assertThat(ask("zhangmin", "列出我们逾期超过 30 天的供应商", ANALYST)).contains("华东");
        ok(call("POST", "/dev-requests", "zhangmin", Map.of("text", "想看到供应商的产能利用率")));
        assertThat(ok(call("GET", "/runs", "zhaoadmin", null)).size()).isGreaterThan(0);
        assertThat(ok(call("GET", "/registry/assets?kind=DevRequest", "zhaoadmin", null))
                        .size())
                .isGreaterThan(0);

        JsonNode report = ok(call("POST", "/demo/v1/reset", "zhaoadmin", null));
        assertThat(report.path("erpReset").asBoolean()).isTrue();
        assertThat(report.path("delegation").asString()).isEqualTo("DLG-0001");

        assertThat(ok(call("GET", "/runs", "zhaoadmin", null)).size()).isEqualTo(0);
        assertThat(ok(call("GET", "/registry/assets?kind=DevRequest", "zhaoadmin", null))
                        .size())
                .isEqualTo(0);
        assertThat(ok(call("GET", "/notifications", "zhangmin", null)).size()).isEqualTo(0);
        assertThat(ok(call("GET", "/identity/v1/delegations", "zhaoadmin", null))
                        .toString())
                .contains("DLG-0001");
        // DemoERP 已按今天重新生成，业务问题照常回答
        assertThat(ask("zhangmin", "列出我们逾期超过 30 天的供应商", ANALYST)).contains("华东").doesNotContain("（华南）");
    }

    @Test
    @Order(2)
    @DisplayName("权限：业务用户与租户开发者不能操作，story 只能是 A 到 F")
    void permissions() throws Exception {
        assertThat(call("POST", "/demo/v1/reset", "zhangmin", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("POST", "/demo/v1/reset", "chengong", null)
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call("POST", "/demo/v1/story-start", "zhaoadmin", Map.of("story", "G"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    @Order(3)
    @DisplayName("故事 F 起点：先清掉演示中途发布的同名扩展，快照重播不被门禁拒绝")
    void storyFOverwritesDemoResidue() throws Exception {
        // 故事 B 结束后的残留：经 Studio 发布到 tenant.t001.extensions 的扩展，
        // 与快照扩展一样给标品挂 notify_im；不清掉时组装门禁（工具重名）会拒绝快照发布
        var residue = Fixtures.extension(Fixtures.with()
                .namespace("tenant.t001.extensions")
                .name("transaction-anomaly-analyst-ext")
                .version("0.1.0")
                .spec("tools", Map.of("add", List.of("tenant.t001.tools/notify-im"))));
        TestPlatform.publish(services, Viewer.tenant("t001"), residue);
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.extensions", "zhaoadmin", null))
                        .size())
                .isEqualTo(1);

        JsonNode report = ok(call("POST", "/demo/v1/story-start", "zhaoadmin", Map.of("story", "F")));
        assertThat(report.path("published").toString()).contains("tenant.t001.finance/anomaly-analyst-ext");
        // 残留被快照替代：tenant.t001.extensions 清空，完成态回到快照定义的状态
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.extensions", "zhaoadmin", null))
                        .size())
                .isEqualTo(0);
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.finance", "zhaoadmin", null))
                        .size())
                .isGreaterThan(0);
    }

    @Test
    @Order(4)
    @DisplayName("故事 F 起点：发布完成态快照，升级回归能暴露扩展的钩子用例失败")
    void storyFStart() throws Exception {
        JsonNode report = ok(call("POST", "/demo/v1/story-start", "zhaoadmin", Map.of("story", "F")));
        assertThat(report.path("published").toString())
                .contains(
                        "tenant.t001.finance/anomaly-analyst-ext",
                        "tenant.t001.pur/purchase-order-clerk-ext",
                        "tenant.t001.evals/anomaly-ext-smoke");
        JsonNode results = ok(call("POST", "/hub/demo/upgrade", "zhaoadmin", null));
        assertThat(results.get(0).path("status").asString()).isEqualTo("failed");
        assertThat(results.get(0).path("regressions").toString()).contains("followup-hook-fires");
        assertThat(ok(call("GET", "/notifications", "zhaoadmin", null)).toString())
                .contains("标品升级回归未通过");
    }

    @Test
    @Order(5)
    @DisplayName("一键完成态：完成态快照 + 30 天模拟历史，治理中心有趋势数据")
    void complete() throws Exception {
        JsonNode report = ok(call("POST", "/demo/v1/complete", "zhaoadmin", null));
        assertThat(report.path("history").path("metricPoints").asInt()).isGreaterThan(0);
        JsonNode metrics = ok(call("GET", "/hub/metrics?days=30", "zhaoadmin", null));
        assertThat(metrics.path("agents").size()).isGreaterThan(3);
        assertThat(metrics.path("daily").size()).isGreaterThanOrEqualTo(29);
    }

    @Test
    @Order(6)
    @DisplayName("收尾重置：回到干净起点，快照资产撤回、基础设施保留（模拟一次演示结束后重置）")
    void trailingReset() throws Exception {
        ok(call("POST", "/demo/v1/reset", "zhaoadmin", null));
        assertThat(ok(call("GET", "/runs", "zhaoadmin", null)).size()).isEqualTo(0);
        assertThat(ok(call("GET", "/registry/assets?kind=DevRequest", "zhaoadmin", null))
                        .size())
                .isEqualTo(0);
        // 纯重置把快照发布的完成态资产一并撤回，注册中心回到首次启动的状态；
        // 之后故事 B 再发布挂 notify_im 的扩展不会与快照扩展撞工具重名门禁
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.finance", "zhaoadmin", null))
                        .size())
                .isZero();
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.pur", "zhaoadmin", null))
                        .size())
                .isZero();
        assertThat(ok(call("GET", "/registry/assets?namespace=tenant.t001.tools", "zhaoadmin", null))
                        .size())
                .isGreaterThan(0);
    }
}

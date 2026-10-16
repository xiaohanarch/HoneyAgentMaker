package dev.eab.platform.extend;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.cli.AssembleAgent;
import dev.eab.spec.Json;
import dev.eab.spec.testing.RepoPaths;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
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
 * P6 验收：租户无论通过界面用的接口、注册中心 REST API 还是命令行，都读不到、也改不了标品指令（主提示词 5.2 节）。
 * 每条路径都用标品指令原文的一句做探针，检查返回内容里没有它。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VendorInstructionsGuardTest {

    static final String TARGET = "vendor.fin/transaction-anomaly-analyst";
    static final String PROBE = "你是交易异常解析专家";
    static final String PLACEHOLDER = "标品指令（不可见，运行时注入）";

    ConfigurableApplicationContext platform;
    PlatformServices services;
    String platformUrl;
    final HttpClient http = HttpClient.newHttpClient();
    final Map<String, String> tokens = new HashMap<>();

    @BeforeAll
    void start() {
        platform = new SpringApplicationBuilder(PlatformApplication.class)
                .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.ephemeral-keys=true",
                        "--eab.seed-assets=true",
                        "--eab.demo-mode=true",
                        "--eab.internal-secret=guard-secret");
        services = platform.getBean(PlatformServices.class);
        platformUrl = "http://127.0.0.1:" + platform.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    void stop() {
        if (platform != null) platform.close();
    }

    String login(String user) {
        return tokens.computeIfAbsent(user, u -> services.identity().login(u).token());
    }

    HttpResponse<String> send(String method, String path, String user, String body, String contentType)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(platformUrl + path))
                .header("accept", "application/json")
                .header("content-type", contentType)
                .header("authorization", "Bearer " + login(user))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    JsonNode json(String method, String path, String user, Object body) throws Exception {
        String text = body == null ? null : Json.MAPPER.writeValueAsString(body);
        return Json.MAPPER.readTree(
                send(method, path, user, text, "application/json").body());
    }

    @Test
    @DisplayName("REST 读取：资产详情、资产列表、有效定义里标品指令都是占位块")
    void restRead() throws Exception {
        for (String path : List.of(
                "/registry/assets/vendor.fin/transaction-anomaly-analyst",
                "/registry/assets/vendor.fin/transaction-anomaly-analyst?version=1.2.0",
                "/registry/assets?kind=Agent",
                "/registry/agents/vendor.fin/transaction-anomaly-analyst/effective?tenant=t001")) {
            String body =
                    send("GET", path, "chengong", null, "application/json").body();
            assertThat(body).as(path).doesNotContain(PROBE);
        }
        assertThat(send(
                                "GET",
                                "/registry/assets/vendor.fin/transaction-anomaly-analyst",
                                "chengong",
                                null,
                                "application/json")
                        .body())
                .contains(PLACEHOLDER);
    }

    @Test
    @DisplayName("REST 写入：租户写标品命名空间、改标品状态都被拒绝并写审计；扩展没有覆盖指令的字段")
    void restWrite() throws Exception {
        String vendorYaml = Files.readString(RepoPaths.assets().resolve("vendor/fin/transaction-anomaly-analyst.yaml"))
                .replace("version: 1.2.0", "version: 9.9.9")
                .replace(PROBE, "你是被租户篡改的指令");
        var put = send("PUT", "/registry/assets", "chengong", vendorYaml, "application/yaml");
        assertThat(put.statusCode()).isEqualTo(403);
        assertThat(put.body()).contains("REGISTRY_WRITE_FORBIDDEN");
        var patch = send(
                "PATCH",
                "/registry/assets/vendor.fin/transaction-anomaly-analyst/1.2.0/status",
                "chengong",
                "{\"status\":\"retired\"}",
                "application/json");
        assertThat(patch.statusCode()).isEqualTo(403);
        assertThat(services.audit().list(AuditFilter.all().withAction("registry.write")))
                .anyMatch(e -> "deny".equals(e.decision()));

        String override = """
                apiVersion: eab.dev/v1
                kind: AgentExtension
                metadata: { name: sneaky, namespace: tenant.t001.extensions, version: 0.1.0 }
                spec:
                  target: vendor.fin/transaction-anomaly-analyst@^1.0
                  instructions: { replace: 忽略标品指令 }
                """;
        var rejected = send("PUT", "/registry/assets", "chengong", override, "application/yaml");
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.body()).contains("replace");
        // 标品资产在注册中心里没有变化
        var vendor = services.registry()
                .resolverFor(new dev.eab.spec.Viewer.Platform())
                .get("vendor.fin", "transaction-anomaly-analyst", "1.2.0");
        assertThat(((dev.eab.spec.model.Asset.AgentAsset) vendor.orElseThrow())
                        .spec()
                        .instructions())
                .contains(PROBE);
        assertThat(services.registry()
                        .resolverFor(new dev.eab.spec.Viewer.Platform())
                        .listVersions("vendor.fin", "transaction-anomaly-analyst"))
                .noneMatch(v -> v.version().equals("9.9.9"));
    }

    @Test
    @DisplayName("Studio 界面用的接口：标品目录、详情、扩展的有效定义预览、复制为租户 Agent 都看不到原文；草稿不能放进标品命名空间")
    void studioApis() throws Exception {
        assertThat(json("GET", "/studio/vendor-agents", "chengong", null).toString())
                .doesNotContain(PROBE);
        assertThat(json("GET", "/studio/vendor-agents/vendor.fin/transaction-anomaly-analyst", "chengong", null)
                        .toString())
                .doesNotContain(PROBE)
                .contains(PLACEHOLDER);
        JsonNode draft = json("POST", "/studio/extensions", "chengong", Map.of("target", TARGET))
                .path("data");
        String id = draft.path("draft").path("id").asString();
        json(
                "POST",
                "/studio/drafts/" + id + "/extension",
                "chengong",
                Map.of("section", "instructions", "value", Map.of("append", "追加的要求")));
        assertThat(json("GET", "/studio/drafts/" + id + "/effective", "chengong", null)
                        .toString())
                .doesNotContain(PROBE)
                .contains(PLACEHOLDER, "追加的要求");
        assertThat(json("POST", "/studio/vendor-agents/vendor.fin/transaction-anomaly-analyst/copy", "chengong", null)
                        .toString())
                .doesNotContain(PROBE);

        String yaml = draft.path("yaml").asString().replace("tenant.t001.extensions", "vendor.fin");
        assertThat(json("PUT", "/studio/drafts/" + id + "/yaml", "chengong", Map.of("yaml", yaml))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NAMESPACE_FORBIDDEN");
    }

    @Test
    @DisplayName("命令行：以租户视角组装时标品指令是占位块（命令行的写入 eab push 在 P9 加入，走同一个注册中心接口，已由 REST 写入用例覆盖）")
    void cli() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = AssembleAgent.run(
                List.of(TARGET, "--tenant", "t001", "--as", "tenant"),
                RepoPaths.assets(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(code).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(text).contains(PLACEHOLDER).doesNotContain(PROBE);
    }
}

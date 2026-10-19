package dev.eab.platform.procode;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.spec.Json;
import dev.eab.spec.testing.RepoPaths;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * 草稿导出端点的端到端测试（ADR 0014）：GET /studio/drafts/{id}/export 下发的 zip 与 eab init 的产物同构，
 * 清单里带 draftId 与资产引用，推回平台（pnpm eab push）时据此找回同一条草稿；只有本租户的开发者能导出。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProCodeExportEndToEndTest {

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
                        "--eab.internal-secret=export-e2e-secret",
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort);
        services = platform.getBean(PlatformServices.class);
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .run(
                        "--server.port=" + erpPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl,
                        "--eab.internal-secret=export-e2e-secret",
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

    byte[] export(String user, String draftId) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(platformUrl + "/studio/drafts/" + draftId + "/export"))
                        .header("authorization", "Bearer " + login(user))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode())
                .as(new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(200);
        return response.body();
    }

    static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    static String text(Map<String, byte[]> files, String path) {
        return new String(files.get(path), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("扩展草稿导出：zip 与 init 产物同构，清单带 draftId 与资产引用，文件名是资产名")
    void exportsExtensionDraft() throws Exception {
        JsonNode created =
                ok(call("POST", "/studio/extensions", "chengong", Map.of("target", "vendor.pur/purchase-order-clerk")));
        String draftId = created.path("draft").path("id").asString();
        String extensionYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/pur/purchase-order-clerk-ext.yaml"), StandardCharsets.UTF_8);
        String evalYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/evals/purchase-clerk-ext-smoke.yaml"), StandardCharsets.UTF_8);
        ok(call("PUT", "/studio/drafts/" + draftId + "/yaml", "chengong", Map.of("yaml", extensionYaml)));
        ok(call("PUT", "/studio/drafts/" + draftId + "/extension/evals", "chengong", Map.of("yaml", evalYaml)));

        var response = http.send(
                HttpRequest.newBuilder(URI.create(platformUrl + "/studio/drafts/" + draftId + "/export"))
                        .header("authorization", "Bearer " + login("chengong"))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).isEqualTo("application/zip");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .isEqualTo("attachment; filename=\"purchase-order-clerk-ext-project.zip\"");

        Map<String, byte[]> files = unzip(response.body());
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "assets/purchase-order-clerk-ext.yaml",
                        "evals/purchase-clerk-ext-smoke.yaml",
                        ProjectManifest.FILE,
                        "README.md",
                        ".gitignore");
        assertThat(text(files, "assets/purchase-order-clerk-ext.yaml")).isEqualTo(extensionYaml);
        assertThat(text(files, "evals/purchase-clerk-ext-smoke.yaml")).isEqualTo(evalYaml);
        assertThat(text(files, "README.md"))
                .contains("purchase-order-clerk-ext")
                .doesNotContain("{{");

        ProjectManifest manifest = ProjectManifest.read(text(files, ProjectManifest.FILE));
        assertThat(manifest.template()).isEqualTo("extension");
        assertThat(manifest.user()).isEqualTo("chengong");
        assertThat(manifest.target()).isEqualTo("vendor.pur/purchase-order-clerk");
        assertThat(manifest.assets()).hasSize(2);
        ProjectManifest.Entry asset =
                manifest.asset("assets/purchase-order-clerk-ext.yaml").orElseThrow();
        assertThat(asset.kind()).isEqualTo("AgentExtension");
        assertThat(asset.ref()).isEqualTo("tenant.t001.pur/purchase-order-clerk-ext");
        assertThat(asset.draftId()).isEqualTo(draftId);
        ProjectManifest.Entry eval =
                manifest.asset("evals/purchase-clerk-ext-smoke.yaml").orElseThrow();
        assertThat(eval.kind()).isEqualTo("EvalSuite");
        assertThat(eval.ref()).isEqualTo("tenant.t001.evals/purchase-clerk-ext-smoke");
        assertThat(eval.draftId()).isEqualTo(draftId);
    }

    @Test
    @DisplayName("技能草稿导出：技能文件在 skills/ 下，没有评测集条目")
    void exportsSkillDraft() throws Exception {
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

        Map<String, byte[]> files = unzip(export("chengong", draftId));
        assertThat(files.keySet())
                .containsExactlyInAnyOrder(
                        "skills/alternative-supplier-lookup.yaml", ProjectManifest.FILE, "README.md", ".gitignore");
        assertThat(text(files, "skills/alternative-supplier-lookup.yaml")).isEqualTo(skillYaml);

        ProjectManifest manifest = ProjectManifest.read(text(files, ProjectManifest.FILE));
        assertThat(manifest.template()).isEqualTo("skill");
        ProjectManifest.Entry asset =
                manifest.asset("skills/alternative-supplier-lookup.yaml").orElseThrow();
        assertThat(asset.kind()).isEqualTo("Skill");
        assertThat(asset.ref()).isEqualTo("tenant.t001.skills/alternative-supplier-lookup");
        assertThat(asset.draftId()).isEqualTo(draftId);
    }

    @Test
    @DisplayName("业务用户不能导出；草稿不存在时 404")
    void rejectsUnauthorizedAndUnknown() throws Exception {
        JsonNode created = ok(
                call("POST", "/studio/skills", "chengong", Map.of("name", "export-perm-check", "displayName", "权限检查")));
        String draftId = created.path("draft").path("id").asString();

        JsonNode denied = call("GET", "/studio/drafts/" + draftId + "/export", "zhangmin", null);
        assertThat(denied.path("success").asBoolean()).isFalse();
        assertThat(denied.path("error").path("code").asString()).isEqualTo("FORBIDDEN");

        JsonNode missing = call("GET", "/studio/drafts/drf-nothing-here/export", "chengong", null);
        assertThat(missing.path("success").asBoolean()).isFalse();
        assertThat(missing.path("error").path("code").asString()).isEqualTo("NOT_FOUND");
    }
}

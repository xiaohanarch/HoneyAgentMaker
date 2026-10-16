package dev.eab.platform.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.http.ApiExceptionHandler;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

class ConnectionCenterTest {

    PlatformServices services;
    MockMvc mvc;

    static Asset connection() {
        return Fixtures.build(
                "Connection",
                "demo-erp",
                "tenant.t001.connections",
                Map.of(
                        "alias", "DEMO_ERP",
                        "type", "bo-api",
                        "bindings",
                                Map.of(
                                        "dev",
                                                Map.of(
                                                        "url",
                                                        "http://127.0.0.1:4100",
                                                        "auth",
                                                        Map.of("type", "token-exchange")),
                                        "prod",
                                                Map.of(
                                                        "url",
                                                        "https://erp.example.test",
                                                        "auth",
                                                        Map.of(
                                                                "type",
                                                                "client-credentials",
                                                                "credentialRef",
                                                                "vault:erp/client")))));
    }

    static Asset toolSource() {
        return Fixtures.build(
                "ToolSource",
                "erp-tools",
                "tenant.t001.tools",
                Map.of(
                        "type", "bo-api",
                        "connection", "DEMO_ERP",
                        "tools", List.of(Map.of("name", "supplier_list", "description", "查供应商", "writeLevel", "L0"))));
    }

    static Asset agent() {
        return Fixtures.build(
                "Agent",
                "buyer",
                "tenant.t001.agents",
                Map.of(
                        "expertise", "查供应商",
                        "instructions", "查供应商",
                        "model", Map.of("profile", "fast"),
                        "writePolicy", Map.of("maxLevel", "L0"),
                        "routing", Map.of("description", "查供应商"),
                        "tools", List.of(Map.of("api", Map.of("source", "tenant.t001.tools/erp-tools")))));
    }

    @BeforeEach
    void setUp() {
        services = TestPlatform.services();
        var tenant = Viewer.tenant("t001");
        TestPlatform.publish(services, tenant, connection());
        TestPlatform.publish(services, tenant, toolSource());
        TestPlatform.publish(services, tenant, agent());
        mvc = MockMvcBuilders.standaloneSetup(new ConnectionController(services))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter(Json.MAPPER))
                .build();
    }

    JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String who)
            throws Exception {
        if (who != null) request.header("Authorization", TestPlatform.bearer(services, who));
        String body = mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Json.MAPPER.readTree(body);
    }

    JsonNode update(String who, String env, Map<String, Object> body) throws Exception {
        return call(
                put("/studio/connections/DEMO_ERP/bindings/" + env)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Json.MAPPER.writeValueAsString(body)),
                who);
    }

    @Test
    @DisplayName("租户开发者能看到本租户的连接别名、各环境绑定，以及直接和间接使用它的资产")
    void listsConnections() throws Exception {
        JsonNode body = call(get("/studio/connections"), "chengong");
        assertThat(body.path("success").asBoolean()).as(body.toString()).isTrue();
        JsonNode demo = body.path("data").get(0);
        assertThat(demo.path("alias").asString()).isEqualTo("DEMO_ERP");
        assertThat(demo.path("type").asString()).isEqualTo("bo-api");
        assertThat(demo.path("ref").asString()).isEqualTo("tenant.t001.connections/demo-erp");
        assertThat(demo.path("bindings")
                        .path("prod")
                        .path("auth")
                        .path("credentialRef")
                        .asString())
                .isEqualTo("vault:erp/client");
        assertThat(demo.path("bindings").has("test")).isFalse();
        assertThat(demo.path("editable").asBoolean()).isFalse();
        assertThat(call(get("/studio/connections"), "zhaoadmin")
                        .path("data")
                        .get(0)
                        .path("editable")
                        .asBoolean())
                .isTrue();
        assertThat(Json.MAPPER.convertValue(demo.path("usedBy"), List.class))
                .containsExactly("tenant.t001.tools/erp-tools", "tenant.t001.agents/buyer");
    }

    @Test
    @DisplayName("业务用户、厂商和未登录时拒绝")
    void readForbidden() throws Exception {
        assertThat(call(get("/studio/connections"), "zhangmin")
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call(get("/studio/connections"), "vendordev")
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("FORBIDDEN");
        assertThat(call(get("/studio/connections"), null).path("success").asBoolean())
                .isFalse();
    }

    @Test
    @DisplayName("租户管理员修改绑定：写入并发布 Connection 的新补丁版本，写审计；其他环境的绑定保持不变")
    void adminUpdatesBinding() throws Exception {
        JsonNode body = update(
                "zhaoadmin",
                "test",
                Map.of(
                        "url",
                        "https://erp-test.example.test",
                        "authType",
                        "client-credentials",
                        "credentialRef",
                        "env:ERP_TEST_SECRET"));
        assertThat(body.path("success").asBoolean()).as(body.toString()).isTrue();
        JsonNode view = body.path("data");
        assertThat(view.path("version").asString()).isEqualTo("1.0.1");
        assertThat(view.path("bindings").path("test").path("url").asString())
                .isEqualTo("https://erp-test.example.test");
        assertThat(view.path("bindings")
                        .path("prod")
                        .path("auth")
                        .path("credentialRef")
                        .asString())
                .isEqualTo("vault:erp/client");
        assertThat(services.audit().list(AuditFilter.all().withAction("connection.binding")))
                .anyMatch(e -> e.decision().equals("allow") && e.userId().equals("zhaoadmin"));

        JsonNode again =
                update("zhaoadmin", "dev", Map.of("url", "http://127.0.0.1:4101", "authType", "token-exchange"));
        assertThat(again.path("data").path("version").asString()).isEqualTo("1.0.2");
        assertThat(again.path("data").path("bindings").path("test").path("url").asString())
                .isEqualTo("https://erp-test.example.test");
    }

    @Test
    @DisplayName("租户开发者不能修改绑定；参数不合法时 400：环境、地址、认证方式、凭据写成明文")
    void updateRejected() throws Exception {
        Map<String, Object> ok = Map.of("url", "https://erp.example.test", "authType", "none");
        assertThat(update("chengong", "dev", ok).path("error").path("code").asString())
                .isEqualTo("FORBIDDEN");
        assertThat(update("zhaoadmin", "staging", ok).path("error").path("code").asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(update("zhaoadmin", "dev", Map.of("url", "erp.example.test", "authType", "none"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        assertThat(update("zhaoadmin", "dev", Map.of("url", "https://erp.example.test", "authType", "basic"))
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("BAD_REQUEST");
        JsonNode secret = update(
                "zhaoadmin",
                "dev",
                Map.of(
                        "url",
                        "https://erp.example.test",
                        "authType",
                        "client-credentials",
                        "credentialRef",
                        "s3cr3t-value"));
        assertThat(secret.path("error").path("code").asString()).isEqualTo("BAD_REQUEST");
        assertThat(secret.path("error").path("message").asString()).contains("只能写引用");
        assertThat(call(
                                put("/studio/connections/NO_SUCH/bindings/dev")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(Json.MAPPER.writeValueAsString(ok)),
                                "zhaoadmin")
                        .path("error")
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("下一个补丁版本号")
    void nextPatch() {
        assertThat(ConnectionCenter.nextPatch("1.0.0")).isEqualTo("1.0.1");
        assertThat(ConnectionCenter.nextPatch("2.3.9")).isEqualTo("2.3.10");
    }
}

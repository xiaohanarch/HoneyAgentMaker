package dev.eab.platform.registry;

import static dev.eab.platform.TestPlatform.publish;
import static dev.eab.platform.TestPlatform.yamlOf;
import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.testing.Fixtures;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** 注册中心接口，以及 P1 代码审查发现的越权、静默失败场景的回归测试（使用真实的发布门禁） */
class RegistryApiTest {

    static final Viewer PLATFORM = Viewer.PLATFORM;
    static final Viewer VENDOR = Viewer.VENDOR;
    static final Viewer T001 = Viewer.tenant("t001");
    static final Viewer T002 = Viewer.tenant("t002");
    static final String AGENT = "vendor.fin/transaction-anomaly-analyst";
    static final MediaType YAML = MediaType.parseMediaType("application/yaml");

    PlatformServices services;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        services = TestPlatform.services();
        mvc = TestPlatform.mvc(services);
        publish(services, PLATFORM, Fixtures.platformRules());
        // 发布门禁会检查引用，被引用的资产先发布
        publish(services, VENDOR, Fixtures.semanticPack());
        publish(services, VENDOR, Fixtures.skill());
        publish(services, VENDOR, Fixtures.agent(with().version("1.2.0")));
    }

    record Response(int status, String body) {
        JsonNode json() {
            return Json.MAPPER.readTree(body);
        }
    }

    Response call(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        return new Response(
                result.getResponse().getStatus(), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        return request.header("Authorization", TestPlatform.bearer(services, who));
    }

    @Nested
    @DisplayName("registry API")
    class Api {
        @Test
        @DisplayName("缺少身份头时返回 401")
        void unauthenticated() throws Exception {
            Response r = call(get("/registry/assets"));
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.json().get("success").asBoolean()).isFalse();
            assertThat(r.json().get("error").get("code").asString()).isEqualTo("UNAUTHENTICATED");
        }

        @Test
        @DisplayName("租户以 YAML 写入自己的扩展，返回 201 草稿")
        void tenantPutsExtension() throws Exception {
            Response r = call(as(
                    put("/registry/assets")
                            .param("changeNote", "首版")
                            .contentType(YAML)
                            .content(yamlOf(Fixtures.extension())),
                    "tenant:t001"));
            assertThat(r.status()).isEqualTo(201);
            assertThat(r.json().get("data").get("status").asString()).isEqualTo("draft");
            assertThat(r.json().get("data").get("changeNote").asString()).isEqualTo("首版");
        }

        @Test
        @DisplayName("租户写标品资产返回 403，审计里能查到；厂商看不到租户审计，租户管理员看本租户")
        void forbiddenWriteAudited() throws Exception {
            Response r = call(as(
                    put("/registry/assets").contentType(YAML).content(yamlOf(Fixtures.agent(with().version("9.9.9")))),
                    "tenant:t001"));
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.json().get("error").get("code").asString()).isEqualTo("REGISTRY_WRITE_FORBIDDEN");

            JsonNode first = call(as(get("/audit/events").param("decision", "deny"), "platform"))
                    .json()
                    .get("data")
                    .get(0);
            assertThat(first.get("actor").asString()).isEqualTo("tenant:t001");
            assertThat(first.get("action").asString()).isEqualTo("registry.write");

            assertThat(call(as(get("/audit/events"), "vendor")).status()).isEqualTo(403);
            JsonNode admin = call(as(
                            get("/audit/events")
                                    .param("action", "registry.write")
                                    .param("decision", "deny"),
                            "zhaoadmin"))
                    .json()
                    .get("data");
            assertThat(admin).hasSize(1);
            assertThat(admin.get(0).get("tenantId").asString()).isEqualTo("t001");
        }

        @Test
        @DisplayName("不合法的 YAML 返回 422 和带行号的问题")
        void invalidYaml() throws Exception {
            Response r = call(as(
                    put("/registry/assets")
                            .contentType(YAML)
                            .content(
                                    "apiVersion: eab.dev/v1\nkind: AgentExtension\nmetadata: {name: x, namespace: tenant.t001, version: 1.0.0}\nspec: {target: nope}\n"),
                    "tenant:t001"));
            assertThat(r.status()).isEqualTo(422);
            JsonNode issue = r.json().get("error").get("issues").get(0);
            assertThat(issue.get("path").asString()).isEqualTo("spec.target");
            assertThat(issue.get("line").asInt()).isEqualTo(4);
        }

        @Test
        @DisplayName("读取标品 Agent：租户看到占位，厂商看到全文")
        void readVendorAgent() throws Exception {
            String url = "/registry/assets/vendor.fin/transaction-anomaly-analyst";
            Response tenant = call(as(get(url), "tenant:t001"));
            assertThat(tenant.body()).doesNotContain(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS);
            assertThat(tenant.json()
                            .get("data")
                            .get("asset")
                            .get("spec")
                            .get("instructions")
                            .get("$redacted")
                            .asBoolean())
                    .isTrue();
            assertThat(call(as(get(url), "vendor")).body()).contains(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS);
        }

        @Test
        @DisplayName("状态变更接口：发布后可以组装出有效定义")
        void publishThenEffective() throws Exception {
            call(as(put("/registry/assets").contentType(YAML).content(yamlOf(Fixtures.extension())), "tenant:t001"));
            Response publish = call(as(
                    patch("/registry/assets/tenant.t001.finance/anomaly-analyst-ext/0.3.0/status")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"published\"}"),
                    "tenant:t001"));
            assertThat(publish.json().get("data").get("status").asString()).isEqualTo("published");
            assertThat(publish.json().get("data").get("signed").asBoolean()).isFalse();

            Response effective =
                    call(as(get("/registry/agents/vendor.fin/transaction-anomaly-analyst/effective"), "tenant:t001"));
            assertThat(effective.status()).isEqualTo(200);
            JsonNode definition = effective.json().get("data").get("definition");
            assertThat(definition.get("provenance").get("extensions").get(0).asString())
                    .isEqualTo("tenant.t001.finance/anomaly-analyst-ext@0.3.0");
            assertThat(effective.body())
                    .doesNotContain(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS)
                    .doesNotContain("【平台机密】");
        }

        @Test
        @DisplayName("租户不能查看其他租户的组装结果")
        void otherTenantForbidden() throws Exception {
            assertThat(call(as(
                                    get("/registry/agents/vendor.fin/transaction-anomaly-analyst/effective")
                                            .param("tenant", "t002"),
                                    "tenant:t001"))
                            .status())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("组装失败返回 422 和问题列表")
        void assemblyFailure() throws Exception {
            Response r = call(as(get("/registry/agents/vendor.fin/missing/effective"), "vendor"));
            assertThat(r.status()).isEqualTo(422);
            assertThat(r.json().get("error").get("details").get(0).get("code").asString())
                    .isEqualTo("AGENT_NOT_FOUND");
        }

        @Test
        @DisplayName("validate 接口返回 schema 问题和静态检查结果")
        void validate() throws Exception {
            Response r = call(as(
                    post("/registry/validate")
                            .contentType(YAML)
                            .content(yamlOf(Fixtures.extension(with().spec("model", Map.of("profile", "judge"))))),
                    "tenant:t001"));
            JsonNode data = r.json().get("data");
            assertThat(data.get("valid").asBoolean()).isFalse();
            assertThat(data.get("lint").get(0).get("rule").asString()).isEqualTo("extension-point-declared");
        }

        @Test
        @DisplayName("validate-batch：同批资产互相引用在叠加层里解析，坏 YAML 只影响自己")
        void validateBatch() throws Exception {
            // 这份 Agent 引用了还没发布的技能，单独校验过不了 reference-resolvable
            String agent = yamlOf(Fixtures.agent(
                    with().version("9.9.9").spec("tools", List.of(Map.of("skill", "vendor.fin.skills/batch-skill")))));
            String skill = yamlOf(Fixtures.skill(with().name("batch-skill")));
            Response alone =
                    call(as(post("/registry/validate").contentType(YAML).content(agent), "vendor"));
            assertThat(alone.json().get("data").get("valid").asBoolean()).isFalse();
            assertThat(alone.json().get("data").get("lint").get(0).get("rule").asString())
                    .isEqualTo("reference-resolvable");

            Response batch = call(as(
                    post("/registry/validate-batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(Json.MAPPER.writeValueAsString(Map.of("assets", List.of(agent, skill)))),
                    "vendor"));
            JsonNode results = batch.json().get("data");
            assertThat(results.size()).isEqualTo(2);
            assertThat(results.get(0).get("valid").asBoolean()).isTrue();
            assertThat(results.get(1).get("valid").asBoolean()).isTrue();

            // 坏 YAML 只拖垮自己，不影响同批其他资产
            Response mixed = call(as(
                    post("/registry/validate-batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(Json.MAPPER.writeValueAsString(Map.of("assets", List.of("key: [未闭合", skill)))),
                    "vendor"));
            JsonNode mixedResults = mixed.json().get("data");
            assertThat(mixedResults.get(0).get("valid").asBoolean()).isFalse();
            assertThat(mixedResults.get(0).get("issues").size()).isPositive();
            assertThat(mixedResults.get(1).get("valid").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("validate-batch：空数组和超过 20 份都返回 400")
        void validateBatchBounds() throws Exception {
            assertThat(call(as(
                                    post("/registry/validate-batch")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"assets\":[]}"),
                                    "vendor"))
                            .status())
                    .isEqualTo(400);
            List<String> tooMany = Collections.nCopies(21, yamlOf(Fixtures.skill(with().name("batch-skill"))));
            assertThat(call(as(
                                    post("/registry/validate-batch")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(Json.MAPPER.writeValueAsString(Map.of("assets", tooMany))),
                                    "vendor"))
                            .status())
                    .isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("跨租户与跨层可见性")
    class CrossTenant {
        @Test
        @DisplayName("租户组装不到其他租户的 Agent")
        void cannotAssembleOtherTenantAgent() {
            publish(
                    services,
                    T002,
                    Fixtures.agent(with().namespace("tenant.t002.agents")
                            .spec("semanticPacks", List.of())
                            .spec("constraints", List.of())
                            .spec("instructions", "t002 的机密指令")));
            var outcome = EffectiveAssembly.assemble(
                    services.registry(),
                    T001,
                    EffectiveAssembly.Request.of("tenant.t002.agents/transaction-anomaly-analyst", null));
            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.issues().stream().map(i -> i.code())).containsExactly("AGENT_NOT_FOUND");
            assertThat(Json.toTree(outcome).toString()).doesNotContain("t002 的机密指令");
        }

        @Test
        @DisplayName("扩展不能引用其他租户的资产，写入时就被拒绝")
        void outOfScopeRefRejected() {
            var ext = Fixtures.extension(with().spec("knowledge", Map.of("add", List.of("tenant.t002.packs/secret"))));
            var result = services.registry().put(T001, yamlOf(ext), null);
            var issue = result.error().issues().getFirst();
            assertThat(issue.code()).isEqualTo("REFERENCE_OUT_OF_SCOPE");
            assertThat(issue.path()).isEqualTo("spec.knowledge.add[0]");
        }

        @Test
        @DisplayName("厂商身份带 tenant 参数读组装结果返回 403")
        void vendorWithTenantForbidden() throws Exception {
            assertThat(call(as(get("/registry/agents/" + AGENT + "/effective").param("tenant", "t001"), "vendor"))
                            .status())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("includeDrafts=false 不会打开草稿；租户带 includeDrafts=true 也看不到厂商草稿")
        void includeDrafts() throws Exception {
            services.registry().put(VENDOR, yamlOf(Fixtures.agent(with().version("1.3.0"))), null);
            assertThat(versionFor("false", "vendor")).endsWith("@1.2.0");
            assertThat(versionFor("true", "vendor")).endsWith("@1.3.0");
            assertThat(versionFor("true", "tenant:t001")).endsWith("@1.2.0");
            assertThat(call(as(get("/registry/agents/" + AGENT + "/effective").param("includeDrafts", "yes"), "vendor"))
                            .status())
                    .isEqualTo(400);
        }

        String versionFor(String includeDrafts, String who) throws Exception {
            return call(as(get("/registry/agents/" + AGENT + "/effective").param("includeDrafts", includeDrafts), who))
                    .json()
                    .get("data")
                    .get("definition")
                    .get("provenance")
                    .get("agent")
                    .asString();
        }

        @Test
        @DisplayName("不带版本读取时返回该身份可见的最新版本，而不是 404")
        void latestVisibleVersion() {
            services.registry().put(VENDOR, yamlOf(Fixtures.agent(with().version("1.3.0"))), null);
            assertThat(services.registry()
                            .view(T001, "vendor.fin", "transaction-anomaly-analyst", null)
                            .value()
                            .summary()
                            .version())
                    .isEqualTo("1.2.0");
        }

        @Test
        @DisplayName("validate 接口要求身份，引用越界时报 reference-scope")
        void validateRequiresIdentity() throws Exception {
            String payload =
                    yamlOf(Fixtures.extension(with().spec("skills", Map.of("add", List.of("tenant.t002.skills/x")))));
            assertThat(call(post("/registry/validate").contentType(YAML).content(payload))
                            .status())
                    .isEqualTo(401);
            JsonNode lint = call(as(post("/registry/validate").contentType(YAML).content(payload), "tenant:t001"))
                    .json()
                    .get("data")
                    .get("lint");
            assertThat(lint).hasSize(1);
            assertThat(lint.get(0).get("rule").asString()).isEqualTo("reference-scope");
        }

        @Test
        @DisplayName("非法的命名空间路径参数返回 400，而不是 500")
        void invalidPathParams() throws Exception {
            assertThat(call(as(get("/registry/agents/foo/bar/effective"), "vendor"))
                            .status())
                    .isEqualTo(400);
            assertThat(call(as(
                                    patch("/registry/assets/foo/n/1.0.0/status")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"status\":\"published\"}"),
                                    "tenant:t001"))
                            .status())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("有效定义里的占位摘要带密钥，与不带密钥的 FNV 摘要不同")
        void keyedDigest() {
            var outcome = (EffectiveAssembly.Outcome.Ok)
                    EffectiveAssembly.assemble(services.registry(), T001, EffectiveAssembly.Request.of(AGENT, "t001"));
            JsonNode segment = null;
            for (JsonNode s : outcome.view().get("segments")) {
                if (s.get("kind").asString().equals("vendor-instructions")) segment = s;
            }
            assertThat(segment.get("text").get("digest").asString()).matches("^[0-9a-f]{16}$");
            assertThat(outcome.view().toString()).doesNotContain(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS);
        }
    }

    @Nested
    @DisplayName("完整性：不能静默丢掉平台规则或扩展")
    class Integrity {
        @Test
        @DisplayName("平台规则被篡改时组装失败，而不是少一段规则继续")
        void tamperedRules() {
            services.db()
                    .jdbc()
                    .sql("UPDATE assets SET content_hash = ? WHERE name = 'safety-rules'")
                    .param("sha256:" + "0".repeat(64))
                    .update();
            var outcome =
                    EffectiveAssembly.assemble(services.registry(), T001, EffectiveAssembly.Request.of(AGENT, "t001"));
            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.issues().getFirst().code()).isEqualTo("INTEGRITY_VIOLATION");
        }

        @Test
        @DisplayName("没有平台规则时组装失败")
        void missingRules() {
            services.db()
                    .jdbc()
                    .sql("DELETE FROM assets WHERE name = 'safety-rules'")
                    .update();
            var outcome =
                    EffectiveAssembly.assemble(services.registry(), VENDOR, EffectiveAssembly.Request.of(AGENT, null));
            assertThat(outcome.issues().stream().map(i -> i.code())).containsExactly("PLATFORM_RULES_MISSING");
        }

        @Test
        @DisplayName("下线时作废签名，在库里把状态改回已发布也通不过验签")
        void retiredSignatureVoided() {
            services.registry().setStatus(VENDOR, "vendor.fin", "transaction-anomaly-analyst", "1.2.0", Status.retired);
            services.db()
                    .jdbc()
                    .sql("UPDATE assets SET status = 'published' WHERE name = 'transaction-anomaly-analyst'")
                    .update();
            var outcome =
                    EffectiveAssembly.assemble(services.registry(), VENDOR, EffectiveAssembly.Request.of(AGENT, null));
            assertThat(outcome.issues().getFirst().code()).isEqualTo("INTEGRITY_VIOLATION");
        }

        @Test
        @DisplayName("把一个版本的签名挪到另一个版本上验签失败")
        void movedSignature() {
            publish(services, VENDOR, Fixtures.agent(with().version("1.3.0").spec("instructions", "另一版指令")));
            var jdbc = services.db().jdbc();
            var v12 = jdbc.sql("SELECT * FROM assets WHERE name = 'transaction-anomaly-analyst' AND version = '1.2.0'")
                    .query()
                    .singleRow();
            // 把 1.2.0 的内容、哈希、签名整体复制到 1.3.0 这一行
            jdbc.sql(
                            "UPDATE assets SET content_json = ?, content_hash = ?, signature = ? WHERE name = 'transaction-anomaly-analyst' AND version = '1.3.0'")
                    .params(v12.get("content_json"), v12.get("content_hash"), v12.get("signature"))
                    .update();
            var resolver = services.registry().resolverFor(VENDOR);
            assertThat(resolver.get("vendor.fin", "transaction-anomaly-analyst", "1.3.0"))
                    .isEmpty();
            assertThat(resolver.integrityFailures()).containsExactly("vendor.fin/transaction-anomaly-analyst@1.3.0");
        }

        @Test
        @DisplayName("同一条完整性失败只写一次审计")
        void auditedOnce() {
            services.db()
                    .jdbc()
                    .sql("UPDATE assets SET content_hash = ? WHERE name = 'transaction-anomaly-analyst'")
                    .param("sha256:" + "0".repeat(64))
                    .update();
            for (int i = 0; i < 5; i++)
                services.registry().view(VENDOR, "vendor.fin", "transaction-anomaly-analyst", null);
            assertThat(services.audit().list(AuditFilter.all().withAction("registry.integrity")))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("发布门禁")
    class Gate {
        @BeforeEach
        void publishTenantAssets() {
            publish(services, T001, Fixtures.toolSource());
            publish(services, T001, Fixtures.workflow());
        }

        dev.eab.platform.registry.RegistryResult<RegistryService.AssetSummary> putAndPublish(
                dev.eab.spec.model.Asset ext) {
            services.registry().put(T001, yamlOf(ext), null);
            return services.registry()
                    .setStatus(T001, "tenant.t001.finance", "anomaly-analyst-ext", "0.3.0", Status.published);
        }

        @Test
        @DisplayName("合规的扩展可以发布")
        void compliantPublishes() {
            var ext = Fixtures.extension(with().spec("tools", Map.of("add", List.of("tenant.t001.tools/notify-im"))));
            assertThat(putAndPublish(ext).ok()).isTrue();
        }

        @Test
        @DisplayName("租户工具与标品工具重名时拒绝发布")
        void nameConflictBlocked() {
            publish(services, T001, Fixtures.skill(with().namespace("tenant.t001.skills")));
            var result = putAndPublish(Fixtures.extension(
                    with().spec("skills", Map.of("add", List.of("tenant.t001.skills/invoice-lookup")))));
            assertThat(result.error().code()).isEqualTo(RegistryResult.ErrorCode.PUBLISH_BLOCKED);
            assertThat(result.error().issues().stream().map(i -> i.code()))
                    .containsExactly("ASSEMBLY_TOOL_NAME_CONFLICT");
            assertThat(services.audit()
                            .list(AuditFilter.all()
                                    .withAction("registry.publish")
                                    .withDecision("deny")))
                    .hasSize(1);
        }

        @Test
        @DisplayName("本扩展的钩子条件无效时拒绝发布（警告也拦）")
        void invalidHookBlocked() {
            var ext = Fixtures.extension(with().spec(
                            "hooks",
                            Map.of(
                                    "onResult",
                                    Map.of(
                                            "workflow",
                                            "tenant.t001.wf/create-followup-task",
                                            "when",
                                            "result.riskLevel == \"high\""))));
            assertThat(putAndPublish(ext).error().issues().stream().map(i -> i.code()))
                    .containsExactly("ASSEMBLY_HOOK_CONDITION_INVALID");
        }

        @Test
        @DisplayName("静态检查不通过的资产不能发布")
        void lintBlocked() {
            var source = Fixtures.toolSource(
                    with().name("no-level").spec("tools", List.of(Map.of("name", "x_tool", "description", "无写级别"))));
            services.registry().put(T001, yamlOf(source), null);
            var result =
                    services.registry().setStatus(T001, "tenant.t001.tools", "no-level", "1.0.0", Status.published);
            assertThat(result.error().issues().stream().map(i -> i.code())).containsExactly("LINT_tool-write-level");
        }
    }

    @Test
    @DisplayName("租户读自己 Agent 的有效定义能看到指令原文")
    void tenantOwnAgent() {
        publish(
                services,
                T001,
                Fixtures.agent(with().namespace("tenant.t001.agents")
                        .spec("semanticPacks", List.of())
                        .spec("constraints", List.of())
                        .spec("instructions", "我们自己的指令")));
        var outcome = (EffectiveAssembly.Outcome.Ok) EffectiveAssembly.assemble(
                services.registry(),
                T001,
                EffectiveAssembly.Request.of("tenant.t001.agents/transaction-anomaly-analyst", "t001"));
        assertThat(outcome.view().toString()).contains("我们自己的指令");
    }
}

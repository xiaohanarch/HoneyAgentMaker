package dev.eab.platform.identity;

import static dev.eab.platform.TestPlatform.publish;
import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.events.EventController;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.testing.Fixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** 身份、委托、事件推送、策略试算的接口（对应 TS 版 identity/routes.test.ts） */
class IdentityApiTest {

    PlatformServices services;
    MockMvc app;

    @BeforeEach
    void setUp() {
        services = TestPlatform.services();
        services.ensureDemoDefaults();
        app = TestPlatform.mvc(services, true);
        publish(services, Viewer.PLATFORM, Fixtures.platformRules());
        publish(services, Viewer.VENDOR, Fixtures.semanticPack());
        publish(services, Viewer.VENDOR, Fixtures.skill());
        publish(services, Viewer.VENDOR, Fixtures.agent());
        // BO 工具来源：委托登记时据此判断操作的写级别
        publish(services, Viewer.VENDOR, Fixtures.toolSource(with().name("demo-erp-bo").namespace("vendor.erp.tools")
                .spec("type", "bo-api").spec("connection", "DEMO_ERP")
                .spec("tools", List.of(
                        Map.of("name", "purchase_order_list", "description", "查订单", "writeLevel", "L0"),
                        Map.of("name", "purchase_order_change_approve", "description", "审批", "writeLevel", "L2")))));
    }

    record Response(int status, String body) {
        JsonNode json() {
            return Json.MAPPER.readTree(body);
        }
    }

    Response call(MockMvc mvc, RequestBuilder request) throws Exception {
        var result = mvc.perform(request).andReturn().getResponse();
        return new Response(result.getStatus(), result.getContentAsString(StandardCharsets.UTF_8));
    }

    Response call(RequestBuilder request) throws Exception {
        return call(app, request);
    }

    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String who) {
        return request.header("Authorization", TestPlatform.bearer(services, who));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(Json.MAPPER.writeValueAsString(body));
    }

    @Nested
    @DisplayName("身份接口")
    class Identity {
        @Test
        @DisplayName("用户列表与选人登录")
        void usersAndLogin() throws Exception {
            assertThat(call(get("/identity/v1/users")).json().get("data").toString()).contains("zhangmin");
            JsonNode user = call(json(post("/identity/v1/login"), Map.of("userId", "zhangmin"))).json().get("data").get("user");
            assertThat(user.get("name").asString()).isEqualTo("张敏");
            assertThat(user.get("role").asString()).isEqualTo("采购经理");
            assertThat(user.get("dataScope").asString()).isEqualTo("采购组织：华东");
        }

        @Test
        @DisplayName("JWKS 公开 EdDSA 公钥")
        void jwks() throws Exception {
            JsonNode key = call(get("/identity/v1/jwks.json")).json().get("keys").get(0);
            assertThat(key.get("kty").asString()).isEqualTo("OKP");
            assertThat(key.get("crv").asString()).isEqualTo("Ed25519");
            assertThat(key.get("alg").asString()).isEqualTo("EdDSA");
        }

        @Test
        @DisplayName("令牌端点接受表单编码的客户端凭证，失败时返回 RFC 6749 格式错误")
        void clientCredentials() throws Exception {
            String secret = services.clients().secretOf("t002-integration").orElseThrow();
            var ok = call(post("/identity/v1/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .content("grant_type=client_credentials&client_id=t002-integration&client_secret=" + secret));
            assertThat(ok.json().get("token_type").asString()).isEqualTo("Bearer");
            assertThat(ok.json().get("expires_in").asInt()).isEqualTo(3600);
            var bad = call(json(post("/identity/v1/token"),
                    Map.of("grant_type", "client_credentials", "client_id", "t002-integration", "client_secret", "x")));
            assertThat(bad.status()).isEqualTo(400);
            assertThat(bad.json().get("error").asString()).isEqualTo("invalid_client");
        }

        @Test
        @DisplayName("演示模式下平台运维为 Agent 签发令牌，其 scope 来自有效定义里的 BO 操作")
        void demoAgentToken() throws Exception {
            var body = Map.of("namespace", "vendor.fin", "name", "transaction-anomaly-analyst", "tenantId", "t001");
            assertThat(call(as(json(post("/identity/v1/demo/agent-token"), body), "zhangmin")).status()).isEqualTo(403);
            // 测试夹具里的技能调用 invoice_list，不是 bo-api 来源，所以没有 BO 操作
            assertThat(call(as(json(post("/identity/v1/demo/agent-token"), body), "platform")).json().get("data").get("operations"))
                    .isEmpty();
        }

        @Test
        @DisplayName("非演示模式不暴露代签、选人登录和 HTTP 令牌交换")
        void nonDemoMode() throws Exception {
            MockMvc plain = TestPlatform.mvc(services, false);
            assertThat(call(plain, as(json(post("/identity/v1/demo/agent-token"),
                    Map.of("namespace", "vendor.fin", "name", "transaction-anomaly-analyst", "tenantId", "t001")), "platform")).status())
                    .isEqualTo(404);
            assertThat(call(plain, json(post("/identity/v1/login"), Map.of("userId", "platformops"))).status()).isEqualTo(404);
            var exchange = call(plain, json(post("/identity/v1/token"), Map.of(
                    "grant_type", "urn:ietf:params:oauth:grant-type:token-exchange",
                    "subject_token", "x",
                    "subject_token_type", "urn:ietf:params:oauth:token-type:jwt",
                    "actor_token", "y",
                    "audience", "demo-erp-bo")));
            assertThat(exchange.json().get("error").asString()).isEqualTo("unsupported_grant_type");
        }
    }

    @Nested
    @DisplayName("委托管理")
    class Delegations {
        @Test
        @DisplayName("只有租户管理员能登记和撤销，撤销后状态即时变为不可用")
        void adminOnly() throws Exception {
            var body = Map.of("delegatorUserId", "zhangmin", "agent", "vendor.fin/transaction-anomaly-analyst",
                    "scope", List.of("purchase_order_list"));
            assertThat(call(as(json(post("/identity/v1/delegations"), body), "zhangmin")).status()).isEqualTo(403);
            assertThat(call(as(json(post("/identity/v1/delegations"), body), "zhaoadmin")).status()).isEqualTo(201);

            var revoke = call(as(delete("/identity/v1/delegations/" + PlatformServices.DEMO_DELEGATION_ID), "zhaoadmin"));
            assertThat(revoke.json().get("data").get("revokedBy").asString()).isEqualTo("zhaoadmin");

            var status = call(get("/identity/v1/delegations/" + PlatformServices.DEMO_DELEGATION_ID + "/status")
                    .header(IdentityController.INTERNAL_HEADER, TestPlatform.INTERNAL_SECRET));
            assertThat(status.json().get("data")).isEqualTo(Json.MAPPER.valueToTree(Map.of("active", false, "reason", "revoked")));

            JsonNode audit = call(as(get("/audit/events").param("action", "identity.delegation.revoke"), "zhaoadmin"))
                    .json().get("data").get(0);
            assertThat(audit.get("target").asString()).isEqualTo(PlatformServices.DEMO_DELEGATION_ID);
            assertThat(audit.get("agent").asString()).isEqualTo("vendor.scm/inventory-risk-analyst");
        }

        @Test
        @DisplayName("委托状态接口只接受内部调用；登记未知的 BO 操作返回 422")
        void internalOnlyAndUnknownOps() throws Exception {
            assertThat(call(get("/identity/v1/delegations/" + PlatformServices.DEMO_DELEGATION_ID + "/status")).status()).isEqualTo(401);
            assertThat(call(as(json(post("/identity/v1/delegations"), Map.of("delegatorUserId", "sunkg",
                    "agent", "vendor.scm/inventory-risk-analyst", "scope", List.of("drop_database"))), "zhaoadmin")).status())
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("委托只能包含只读操作，被委托的 Agent 必须存在")
        void readOnlyAndExistingAgent() throws Exception {
            var write = call(as(json(post("/identity/v1/delegations"), Map.of("delegatorUserId", "zhangmin",
                    "agent", "vendor.fin/transaction-anomaly-analyst", "scope", List.of("purchase_order_change_approve"))), "zhaoadmin"));
            assertThat(write.json().get("error").get("code").asString()).isEqualTo("WRITE_NOT_DELEGABLE");
            assertThat(call(as(json(post("/identity/v1/delegations"), Map.of("delegatorUserId", "zhangmin",
                    "agent", "vendor.fin/nope", "scope", List.of("purchase_order_list"))), "zhaoadmin")).status())
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("页面链接带短期页面令牌，路径只能是供应商或采购订单")
        void pageLinks() throws Exception {
            var link = call(as(json(post("/identity/v1/page-links"), Map.of("kind", "purchase-order", "id", "PO-2026-0117")), "zhangmin"));
            assertThat(link.json().get("data").get("path").asString()).matches("^/erp/pages/purchase-orders/PO-2026-0117\\?t=ey.*");
            assertThat(call(as(json(post("/identity/v1/page-links"), Map.of("kind", "purchase-order", "id", "../../bo")), "zhangmin"))
                    .status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("事件推送")
    class Events {
        MockHttpServletRequestBuilder signed(String body, String timestamp) {
            return post("/events/v1/ingest").contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(EventController.TIMESTAMP_HEADER, timestamp)
                    .header(EventController.SIGNATURE_HEADER, EventController.signPayload(TestPlatform.INTERNAL_SECRET, timestamp, body));
        }

        String now() {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }

        String overdue(String id, String tenantId) {
            return Json.MAPPER.writeValueAsString(Map.of("id", id, "type", "delivery.overdue", "tenantId", tenantId,
                    "payload", Map.of("poId", "PO-2026-0117", "overdueDays", 120)));
        }

        @Test
        @DisplayName("签名正确的业务事件入库并广播")
        void accepted() throws Exception {
            List<String> received = new ArrayList<>();
            services.events().subscribe(e -> received.add(e.type()));
            assertThat(call(signed(overdue("evt-1", "t001"), now())).status()).isEqualTo(202);
            assertThat(received).containsExactly("delivery.overdue");
            assertThat(services.events().list("t001", 50).getFirst().payload()).containsEntry("poId", "PO-2026-0117");
        }

        @Test
        @DisplayName("签名不对、时间戳过期、重放、租户不存在的事件都被拒绝")
        void rejected() throws Exception {
            String body = overdue("evt-2", "t001");
            // header() 会追加而不是替换，伪造签名的请求单独构造
            String ts = now();
            assertThat(call(post("/events/v1/ingest").contentType(MediaType.APPLICATION_JSON).content(body)
                    .header(EventController.TIMESTAMP_HEADER, ts)
                    .header(EventController.SIGNATURE_HEADER, "sha256=00")).status()).isEqualTo(401);
            String old = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
            assertThat(call(signed(body, old)).json().get("error").get("code").asString()).isEqualTo("STALE_EVENT");
            assertThat(call(signed(body, now())).status()).isEqualTo(202);
            assertThat(call(signed(body, now())).json().get("error").get("code").asString()).isEqualTo("REPLAYED_EVENT");
            assertThat(call(signed(overdue("evt-3", "t999"), now())).json().get("error").get("code").asString())
                    .isEqualTo("UNKNOWN_TENANT");
        }

        @Test
        @DisplayName("BO API 的裁决写入审计，可以按单据反查")
        void boAccessAudited() throws Exception {
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("at", "2026-10-15T08:00:00.000Z");
            payload.put("operation", "purchase_order_change_delivery_date");
            payload.put("userId", "zhangmin");
            payload.put("agent", "vendor.pur/supplier-performance-analyst@1.0.0");
            payload.put("mode", "on-behalf");
            payload.put("outcome", "allow");
            payload.put("code", "OK");
            payload.put("reason", "允许");
            payload.put("dataScope", "采购组织：华东");
            payload.put("target", "PO-2026-0117");
            payload.put("resultSummary", "生成交期变更单 DC-0001");
            String access = Json.MAPPER.writeValueAsString(Map.of("id", "evt-access-1", "type", "bo.access", "tenantId", "t001", "payload", payload));
            call(signed(access, now()));
            JsonNode event = call(as(get("/audit/events").param("target", "PO-2026-0117"), "zhaoadmin")).json().get("data").get(0);
            assertThat(event.get("userId").asString()).isEqualTo("zhangmin");
            assertThat(event.get("agent").asString()).isEqualTo("vendor.pur/supplier-performance-analyst@1.0.0");
            assertThat(event.get("checkpoint").asString()).isEqualTo("bo-api");
            assertThat(event.get("tool").asString()).isEqualTo("purchase_order_change_delivery_date");
        }
    }

    @Nested
    @DisplayName("策略试算")
    class Policy {
        @Test
        @DisplayName("入口与工具裁决写入审计；普通用户只能查到自己的审计记录")
        void trials() throws Exception {
            JsonNode entry = call(as(json(post("/policy/v1/evaluate"),
                    Map.of("checkpoint", "entry", "assistant", "vendor.fin/finance-assistant")), "zhangmin")).json().get("data");
            assertThat(entry.get("outcome").asString()).isEqualTo("deny");
            assertThat(entry.get("ruleId").asString()).isEqualTo("ENTRY-003");
            JsonNode tool = call(as(json(post("/policy/v1/evaluate"), Map.of("checkpoint", "tool",
                    "agent", "vendor.fin/transaction-anomaly-analyst", "tool", "wire_transfer")), "zhangmin")).json().get("data");
            assertThat(tool.get("ruleId").asString()).isEqualTo("TOOL-001");
            assertThat(call(as(get("/audit/events").param("checkpoint", "entry"), "zhangmin")).json().get("data")).hasSize(1);
            assertThat(call(as(get("/audit/events").param("checkpoint", "entry"), "liqiang")).json().get("data")).isEmpty();
        }

        @Test
        @DisplayName("策略试算不认其他租户的委托，试算记录与真实裁决分开")
        void otherTenantDelegation() throws Exception {
            var other = services.delegations().create(new DelegationStore.NewDelegation("t002", "zhouting",
                    "vendor.pur/supplier-performance-analyst", List.of("purchase_order_list"),
                    "2000-01-01T00:00:00.000Z", "2999-01-01T00:00:00.000Z", null, "x"));
            JsonNode data = call(as(json(post("/policy/v1/evaluate"), Map.of("checkpoint", "tool",
                    "agent", "vendor.fin/transaction-anomaly-analyst", "tool", "invoice-lookup", "mode", "delegated",
                    "delegationId", other.id())), "zhangmin")).json().get("data");
            assertThat(data.get("facts").get("delegation").get("id").asString()).isEmpty();
            assertThat(services.audit().list(AuditFilter.all().withAction("policy.trial.tool"))).hasSize(1);
        }

        @Test
        @DisplayName("规则清单公开可查")
        void rules() throws Exception {
            assertThat(call(get("/policy/v1/rules")).body()).contains("TOOL-005");
        }
    }
}

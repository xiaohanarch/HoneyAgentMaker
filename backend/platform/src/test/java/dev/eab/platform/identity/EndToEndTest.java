package dev.eab.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.AuditEvent;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.identity.DelegatedClient.BoCall;
import dev.eab.platform.identity.DelegatedClient.DelegationException;
import dev.eab.platform.identity.DelegatedClient.Subject;
import dev.eab.spec.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * P2 验收的端到端测试（对应 TS 版 identity/end-to-end.test.ts）：平台与 DemoERP 各自监听随机端口，
 * ERP 通过 HTTP 读取平台的 JWKS、核实委托、推送审计事件，链路与 pnpm demo 下完全相同。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndTest {

    static final String SECRET = "e2e-internal-secret";
    static final String PURCHASING = "vendor.pur/supplier-performance-analyst";
    static final String INVENTORY = "vendor.scm/inventory-risk-analyst";
    static final List<String> PO_OPS = List.of(
            "purchase_order_list",
            "purchase_order_change_delivery_date",
            "purchase_order_change_approve",
            "supplier_list");

    ConfigurableApplicationContext platform;
    ConfigurableApplicationContext erp;
    PlatformServices services;
    String platformUrl;
    String erpUrl;
    final HttpClient http = HttpClient.newHttpClient();

    static String urlOf(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @BeforeAll
    void start() {
        platform = new SpringApplicationBuilder(PlatformApplication.class)
                .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.ephemeral-keys=true",
                        "--eab.seed-assets=false",
                        "--eab.demo-mode=true",
                        "--eab.internal-secret=" + SECRET);
        services = platform.getBean(PlatformServices.class);
        platformUrl = urlOf(platform);
        // 测试里不缓存委托状态，撤销后立即可见
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .run(
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl,
                        "--eab.internal-secret=" + SECRET,
                        "--eab.delegation-cache-millis=0");
        erpUrl = urlOf(erp);
    }

    @AfterAll
    void stop() {
        if (erp != null) erp.close();
        if (platform != null) platform.close();
    }

    String agentToken(String agent, List<String> operations) {
        return services.identity().issueAgentToken(agent, "1.0.0", "t001", operations);
    }

    DelegatedClient client() {
        return new DelegatedClient(services.identity(), erpUrl, null);
    }

    DelegatedClient.BoResponse asUser(
            String userId, String operation, String method, String path, Map<String, String> query, Object body) {
        String token = services.identity().login(userId).token();
        return client().callBoApi(new BoCall(
                Subject.user(token), agentToken(PURCHASING, PO_OPS), operation, method, path, query, body, null));
    }

    HttpResponse<String> fetch(String method, String url, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) builder.header("authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** ERP 的审计推送是异步的，等它落到平台审计里 */
    List<AuditEvent> waitForAudit(Predicate<AuditEvent> filter) throws InterruptedException {
        List<AuditEvent> events = List.of();
        for (int i = 0; i < 50; i++) {
            events = services.audit().list(AuditFilter.all()).stream()
                    .filter(filter)
                    .toList();
            if (!events.isEmpty()) return events;
            Thread.sleep(50);
        }
        return events;
    }

    static List<String> ids(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("items").forEach(item -> ids.add(item.path("id").asString()));
        return ids;
    }

    @Nested
    @DisplayName("P2 验收")
    class Acceptance {

        @Test
        @DisplayName("张敏、李强用委托令牌调同一个 BO API，结果不同且带过滤说明")
        void sameQueryDifferentUsers() {
            Map<String, String> query = Map.of("overdueDaysGt", "30");
            var zhang = asUser("zhangmin", "purchase_order_list", "GET", "/bo/v1/purchase-orders", query, null);
            var li = asUser("liqiang", "purchase_order_list", "GET", "/bo/v1/purchase-orders", query, null);
            assertThat(zhang.body().path("filtered").asBoolean()).isTrue();
            assertThat(zhang.body().path("visibleScope").toString()).isEqualTo("[\"华东\"]");
            assertThat(li.body().path("filtered").asBoolean()).isTrue();
            assertThat(li.body().path("visibleScope").toString()).isEqualTo("[\"华南\"]");
            List<String> zhangIds = ids(zhang.body());
            List<String> liIds = ids(li.body());
            assertThat(zhangIds).contains("PO-2026-0117");
            assertThat(zhangIds.stream().filter(liIds::contains)).isEmpty();
        }

        @Test
        @DisplayName("集成账号调 Open API 返回全部数据；调 BO API 被拒绝")
        void integrationClient() throws Exception {
            String secret = services.clients().secretOf("t001-integration").orElse("");
            String token = services.identity().clientCredentials("t001-integration", secret);
            JsonNode body =
                    Json.MAPPER.readTree(fetch("GET", erpUrl + "/open/v1/purchase-orders?overdueDaysGt=30", token)
                            .body());
            assertThat(body.path("filtered").asBoolean()).isFalse();
            Set<String> orgs = new HashSet<>();
            body.path("items").forEach(p -> orgs.add(p.path("purchasingOrgName").asString()));
            assertThat(orgs).containsExactlyInAnyOrder("华东", "华南");

            var bo = fetch("GET", erpUrl + "/bo/v1/purchase-orders", token);
            assertThat(bo.statusCode()).isEqualTo(401);
            assertThat(Json.MAPPER
                            .readTree(bo.body())
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("AGENT_IDENTITY_REQUIRED");
        }

        @Test
        @DisplayName("SoD：张敏提交交期变更后审批自己的变更单被拒绝，审计可以反查")
        void segregationOfDuties() throws Exception {
            var submit = asUser(
                    "zhangmin",
                    "purchase_order_change_delivery_date",
                    "POST",
                    "/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes",
                    null,
                    Map.of("newDate", "2099-11-15", "reason", "供应商产能不足"));
            assertThat(submit.status()).isEqualTo(201);
            String changeId = submit.body().path("item").path("id").asString();
            var approve = asUser(
                    "zhangmin",
                    "purchase_order_change_approve",
                    "POST",
                    "/bo/v1/delivery-date-changes/" + changeId + "/approve",
                    null,
                    null);
            assertThat(approve.status()).isEqualTo(403);
            assertThat(approve.body().path("error").path("code").asString()).isEqualTo("SOD_VIOLATION");

            // 反查“PO-2026-0117 的交期是谁通过哪个 Agent 改的”
            var record = waitForAudit(e -> "allow".equals(e.decision())
                            && "purchase_order_change_delivery_date".equals(e.tool())
                            && e.target() != null
                            && e.target().contains("PO-2026-0117"))
                    .getFirst();
            assertThat(record.userId()).isEqualTo("zhangmin");
            assertThat(record.agent()).isEqualTo(PURCHASING + "@1.0.0");
            assertThat(record.checkpoint()).isEqualTo("bo-api");
            assertThat(record.mode()).isEqualTo("on-behalf");
            var denial = waitForAudit(e -> "bo-api".equals(e.checkpoint())
                            && "deny".equals(e.decision())
                            && "purchase_order_change_approve".equals(e.tool()))
                    .getFirst();
            assertThat(denial.ruleId()).isEqualTo("SOD_VIOLATION");
            assertThat(denial.userId()).isEqualTo("zhangmin");
        }

        @Test
        @DisplayName("受托运行：委托有效时可以调用；赵管理员撤销后，交换失败，已签发的令牌也被 ERP 拒绝")
        void delegationRevocation() throws Exception {
            services.ensureDemoDefaults();
            String actor = agentToken(INVENTORY, List.of("inventory_balance_list"));
            BoCall call = new BoCall(
                    Subject.delegation(PlatformServices.DEMO_DELEGATION_ID),
                    actor,
                    "inventory_balance_list",
                    "GET",
                    "/bo/v1/inventory-balances",
                    null,
                    null,
                    null);
            assertThat(client().callBoApi(call).status()).isEqualTo(200);

            // 撤销前先签好一张令牌，模拟运行中的 Agent 手里还握着令牌
            var held = services.identity()
                    .exchange(new IdentityService.ExchangeRequest(
                            "delegation:" + PlatformServices.DEMO_DELEGATION_ID,
                            IdentityService.TokenType.DELEGATION,
                            actor,
                            TokenService.Audience.BO,
                            null));
            String admin = services.identity().login("zhaoadmin").token();
            var revoke = fetch(
                    "DELETE", platformUrl + "/identity/v1/delegations/" + PlatformServices.DEMO_DELEGATION_ID, admin);
            assertThat(revoke.statusCode()).isEqualTo(200);

            assertThatThrownBy(() -> client().callBoApi(call))
                    .isInstanceOfSatisfying(
                            DelegationException.class, e -> assertThat(e.code()).isEqualTo("DELEGATION_INACTIVE"));
            var stale = fetch("GET", erpUrl + "/bo/v1/inventory-balances", held.accessToken());
            assertThat(stale.statusCode()).isEqualTo(403);
            assertThat(Json.MAPPER
                            .readTree(stale.body())
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("DELEGATION_REVOKED");
            var denied = waitForAudit(e -> "bo-api".equals(e.checkpoint())
                            && "deny".equals(e.decision())
                            && "inventory_balance_list".equals(e.tool()))
                    .getFirst();
            assertThat(denied.mode()).isEqualTo("delegated");
            assertThat(denied.ruleId()).isEqualTo("DELEGATION_REVOKED");
        }
    }

    @Nested
    @DisplayName("失败即拒绝，不退回技术账号")
    class FailClosed {

        /**
         * 指向一个没有服务监听的地址：如果真的发出了请求，错误码会是 BO_UNREACHABLE，
         * 断言里的错误码因此证明请求在发出之前就被拒绝了（对应 TS 版的 fetch 探针）
         */
        DelegatedClient guarded(String url) {
            return new DelegatedClient(services.identity(), url == null ? null : "http://127.0.0.1:9", null);
        }

        @Test
        @DisplayName("BO 路径不合法时拒绝，委托令牌不会发往配置之外的地址")
        void invalidPaths() {
            String token = services.identity().login("zhangmin").token();
            for (String path : List.of(
                    "//evil.example/bo/v1/x",
                    "https://evil.example/bo/v1/x",
                    "/bo/v1/../open/v1/suppliers",
                    "/open/v1/suppliers")) {
                assertThatThrownBy(() -> guarded(erpUrl)
                                .callBoApi(new BoCall(
                                        Subject.user(token),
                                        agentToken(PURCHASING, PO_OPS),
                                        "purchase_order_list",
                                        "GET",
                                        path,
                                        null,
                                        null,
                                        null)))
                        .as(path)
                        .isInstanceOfSatisfying(
                                DelegationException.class,
                                e -> assertThat(e.code()).isEqualTo("INVALID_PATH"));
            }
        }

        @Test
        @DisplayName("令牌交换失败时不会发出 BO 调用")
        void exchangeFailure() {
            String token = services.identity().login("chengong").token(); // 没有业务权限，交集为空
            assertThatThrownBy(() -> guarded(erpUrl)
                            .callBoApi(new BoCall(
                                    Subject.user(token),
                                    agentToken(PURCHASING, PO_OPS),
                                    "purchase_order_list",
                                    "GET",
                                    "/bo/v1/purchase-orders",
                                    null,
                                    null,
                                    null)))
                    .isInstanceOfSatisfying(
                            DelegationException.class, e -> assertThat(e.code()).isNotEqualTo("BO_UNREACHABLE"));
        }

        @Test
        @DisplayName("没有配置 ERP 连接时直接拒绝")
        void connectionMissing() {
            String token = services.identity().login("zhangmin").token();
            assertThatThrownBy(() -> guarded(null)
                            .callBoApi(new BoCall(
                                    Subject.user(token),
                                    agentToken(PURCHASING, PO_OPS),
                                    "purchase_order_list",
                                    "GET",
                                    "/bo/v1/purchase-orders",
                                    null,
                                    null,
                                    null)))
                    .isInstanceOfSatisfying(
                            DelegationException.class, e -> assertThat(e.code()).isEqualTo("CONNECTION_MISSING"));
        }

        @Test
        @DisplayName("Agent 令牌伪造或过期时交换失败，不发出调用")
        void forgedAgentToken() {
            String token = services.identity().login("zhangmin").token();
            assertThatThrownBy(() -> guarded(erpUrl)
                            .callBoApi(new BoCall(
                                    Subject.user(token),
                                    "not-a-token",
                                    "purchase_order_list",
                                    "GET",
                                    "/bo/v1/purchase-orders",
                                    null,
                                    null,
                                    null)))
                    .isInstanceOfSatisfying(
                            DelegationException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TOKEN"));
        }
    }
}

package dev.eab.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.TestPlatform;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.identity.IdentityService.ExchangeRequest;
import dev.eab.platform.identity.IdentityService.TokenType;
import dev.eab.platform.identity.TokenService.TokenException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentityServiceTest {

    static final String PURCHASING_AGENT = "vendor.pur/supplier-performance-analyst";
    static final String INVENTORY_AGENT = "vendor.scm/inventory-risk-analyst";

    static PlatformServices services;

    @BeforeEach
    void setUp() {
        services = TestPlatform.services();
        services.ensureDemoDefaults();
    }

    static String agentToken(String agent, List<String> operations) {
        return services.identity().issueAgentToken(agent, "1.0.0", "t001", operations);
    }

    static String login(String userId) {
        return services.identity().login(userId).token();
    }

    static IdentityService.ExchangeResult exchangeAs(String userId, String agent, List<String> operations) {
        return services.identity()
                .exchange(new ExchangeRequest(
                        login(userId), TokenType.JWT, agentToken(agent, operations), TokenService.Audience.BO, null));
    }

    @Test
    @DisplayName("用户令牌带用户、租户、角色，受众是平台；未知用户不能登录")
    void userToken() {
        var claims = IdentityService.decode(login("zhangmin"));
        assertThat(claims)
                .containsEntry("iss", "eab-identity")
                .containsEntry("sub", "zhangmin")
                .containsEntry("tid", "t001")
                .containsEntry("kind", "user");
        assertThat(claims.get("aud").toString()).contains(TokenService.Audience.PLATFORM);
        assertThat(claims.get("roles").toString()).contains("purchasing-manager");
        assertThatThrownBy(() -> login("nobody")).isInstanceOf(TokenException.class);
    }

    @Test
    @DisplayName("集成账号换到的令牌受众是 Open API，没有人的身份")
    void clientCredentials() {
        String secret = services.clients().secretOf("t001-integration").orElseThrow();
        var claims = IdentityService.decode(services.identity().clientCredentials("t001-integration", secret));
        assertThat(claims.get("aud").toString()).contains(TokenService.Audience.OPEN);
        assertThat(claims).containsEntry("sub", "client:t001-integration").containsEntry("kind", "client");
        assertThatThrownBy(() -> services.identity().clientCredentials("t001-integration", "wrong"))
                .hasMessageContaining("密钥错误");
    }

    @Test
    @DisplayName("代用户操作：sub 是用户，act.sub 是 Agent，scope 取用户权限与 Agent 声明的交集")
    void onBehalfExchange() {
        var result = exchangeAs(
                "liqiang",
                PURCHASING_AGENT,
                List.of("purchase_order_list", "purchase_order_change_approve", "transaction_anomaly_list"));
        // 李强是采购员：有订单查询权限，没有审批和财务权限
        assertThat(result.scope()).containsExactly("purchase_order_list");
        var claims = IdentityService.decode(result.accessToken());
        assertThat(claims.get("aud").toString()).contains(TokenService.Audience.BO);
        assertThat(claims)
                .containsEntry("sub", "liqiang")
                .containsEntry("tid", "t001")
                .containsEntry("scope", "purchase_order_list")
                .containsEntry("mode", "on-behalf")
                .containsEntry("kind", "delegated");
        assertThat(claims.get("act")).isEqualTo(Map.of("sub", "agent:" + PURCHASING_AGENT));
        assertThat(result.expiresIn()).isEqualTo(300);
    }

    @Test
    @DisplayName("受托运行：用委托编号换令牌，sub 是委托人，带 delegation_id，范围再与委托取交集")
    void delegatedExchange() {
        var result = services.identity()
                .exchange(new ExchangeRequest(
                        "delegation:" + PlatformServices.DEMO_DELEGATION_ID,
                        TokenType.DELEGATION,
                        agentToken(INVENTORY_AGENT, List.of("inventory_balance_list", "followup_task_create")),
                        TokenService.Audience.BO,
                        null));
        assertThat(result.scope()).containsExactly("inventory_balance_list");
        assertThat(result.claims())
                .containsEntry("sub", "sunkg")
                .containsEntry("mode", "delegated")
                .containsEntry("delegation_id", PlatformServices.DEMO_DELEGATION_ID);
    }

    @Test
    @DisplayName("撤销委托后交换失败，并写入带租户、委托人、Agent 的拒绝审计")
    void revokedDelegation() {
        services.delegations().revoke(PlatformServices.DEMO_DELEGATION_ID, "zhaoadmin");
        assertThatThrownBy(() -> services.identity()
                        .exchange(new ExchangeRequest(
                                "delegation:" + PlatformServices.DEMO_DELEGATION_ID,
                                TokenType.DELEGATION,
                                agentToken(INVENTORY_AGENT, List.of("inventory_balance_list")),
                                TokenService.Audience.BO,
                                null)))
                .isInstanceOfSatisfying(
                        TokenException.class, e -> assertThat(e.code()).isEqualTo("DELEGATION_INACTIVE"));
        var event = services.audit()
                .list(AuditFilter.all().withAction("identity.token-exchange").withDecision("deny"))
                .getFirst();
        assertThat(event.reason()).contains("revoked");
        assertThat(event.tenantId()).isEqualTo("t001");
        assertThat(event.userId()).isEqualTo("sunkg");
        assertThat(event.agent()).isEqualTo(INVENTORY_AGENT + "@1.0.0");
        assertThat(event.mode()).isEqualTo("delegated");
        assertThat(event.resultSummary()).contains("未退回任何技术账号");
    }

    static Stream<Arguments> rejectedExchanges() {
        return Stream.of(
                Arguments.of(
                        "委托授权的是另一个 Agent",
                        (Supplier<?>) () -> services.identity()
                                .exchange(new ExchangeRequest(
                                        "delegation:" + PlatformServices.DEMO_DELEGATION_ID,
                                        TokenType.DELEGATION,
                                        agentToken(PURCHASING_AGENT, List.of("purchase_order_list")),
                                        TokenService.Audience.BO,
                                        null)),
                        "DELEGATION_AGENT_MISMATCH"),
                Arguments.of(
                        "用户与 Agent 不属于同一租户",
                        (Supplier<?>) () -> services.identity()
                                .exchange(new ExchangeRequest(
                                        login("zhouting"),
                                        TokenType.JWT,
                                        agentToken(PURCHASING_AGENT, List.of("purchase_order_list")),
                                        TokenService.Audience.BO,
                                        null)),
                        "TENANT_MISMATCH"),
                Arguments.of(
                        "权限交集为空",
                        (Supplier<?>) () -> exchangeAs("chengong", PURCHASING_AGENT, List.of("purchase_order_list")),
                        "EMPTY_SCOPE"),
                Arguments.of(
                        "申请的受众不是 BO API",
                        (Supplier<?>) () -> services.identity()
                                .exchange(new ExchangeRequest(
                                        login("zhangmin"),
                                        TokenType.JWT,
                                        agentToken(PURCHASING_AGENT, List.of("purchase_order_list")),
                                        TokenService.Audience.OPEN,
                                        null)),
                        "INVALID_TARGET"),
                Arguments.of(
                        "用用户令牌冒充 Agent 令牌",
                        (Supplier<?>) () -> services.identity()
                                .exchange(new ExchangeRequest(
                                        login("zhangmin"),
                                        TokenType.JWT,
                                        login("zhangmin"),
                                        TokenService.Audience.BO,
                                        null)),
                        "INVALID_TOKEN"),
                Arguments.of(
                        "不支持的主体令牌类型",
                        (Supplier<?>) () -> services.identity()
                                .exchange(new ExchangeRequest(
                                        "x",
                                        "urn:example:unknown",
                                        agentToken(PURCHASING_AGENT, List.of("purchase_order_list")),
                                        TokenService.Audience.BO,
                                        null)),
                        "UNSUPPORTED_SUBJECT"));
    }

    @ParameterizedTest(name = "{0}时拒绝签发")
    @MethodSource("rejectedExchanges")
    void rejected(String label, Supplier<?> run, String code) {
        assertThatThrownBy(run::get)
                .isInstanceOfSatisfying(
                        TokenException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    @DisplayName("其他密钥签的令牌、过期令牌都校验失败")
    void forgedAndExpired() {
        var other = new TokenService(IdentityKeys.ephemeral(), Clock.systemUTC());
        String forged = other.issueUser(new TokenService.UserClaims("zhangmin", "t001", List.of("purchasing-manager")));
        assertThatThrownBy(() -> services.tokens().verifyUser(forged))
                .isInstanceOfSatisfying(
                        TokenException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TOKEN"));
        var keys = IdentityKeys.ephemeral();
        var past = new TokenService(keys, Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC));
        String old = past.issueUser(new TokenService.UserClaims("zhangmin", "t001", List.of()));
        assertThatThrownBy(() -> new TokenService(keys, Clock.systemUTC()).verifyUser(old))
                .isInstanceOfSatisfying(
                        TokenException.class, e -> assertThat(e.code()).isEqualTo("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("未生效、已过期、不存在的委托不可用；已撤销的委托不能再改写撤销人")
    void delegationStatus() {
        var future = services.delegations()
                .create(new DelegationStore.NewDelegation(
                        "t001",
                        "sunkg",
                        INVENTORY_AGENT,
                        List.of("inventory_balance_list"),
                        "2999-01-01T00:00:00.000Z",
                        "2999-12-31T00:00:00.000Z",
                        null,
                        "zhaoadmin"));
        assertThat(services.delegations().status(future.id()).reason()).isEqualTo("not-yet-valid");
        var expired = services.delegations()
                .create(new DelegationStore.NewDelegation(
                        "t001",
                        "sunkg",
                        INVENTORY_AGENT,
                        List.of("inventory_balance_list"),
                        "2000-01-01T00:00:00.000Z",
                        "2000-12-31T00:00:00.000Z",
                        null,
                        "zhaoadmin"));
        assertThat(services.delegations().status(expired.id()).reason()).isEqualTo("expired");
        assertThat(services.delegations().status("DLG-NOPE").reason()).isEqualTo("not-found");

        services.delegations().revoke(PlatformServices.DEMO_DELEGATION_ID, "zhaoadmin");
        var again = services.delegations()
                .revoke(PlatformServices.DEMO_DELEGATION_ID, "someone-else")
                .orElseThrow();
        assertThat(again.revokedBy()).isEqualTo("zhaoadmin");
    }
}

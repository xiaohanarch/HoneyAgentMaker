package dev.eab.a2a.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.eab.a2a.A2aOutbound;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 代码 Agent SDK 的最小示例：用几行 Java 写一个“目标价测算”Agent，以 A2A 对外提供服务，只接受平台签发的服务令牌，
 * 再生成 RemoteAgent 资产声明登记到平台。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeAgentSdkTest {

    static final CodeAgentServer.Description DESCRIPTION = new CodeAgentServer.Description(
            "目标价测算 Agent", "按最近一次询价的最低报价测算降价谈判的目标价", "target-price", List.of("目标价怎么定"));

    /** 示例 Agent：从话术里取报价，按 5% 的降幅给出目标价；没有报价时追问 */
    static final CodeAgent TARGET_PRICE = request -> {
        var matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(request.text());
        if (!matcher.find()) return CodeAgent.Reply.ask("请告诉我最近一次询价的最低报价");
        double quote = Double.parseDouble(matcher.group(1));
        return CodeAgent.Reply.done(String.format(
                "为 %s 测算：目标价 %.2f（最低报价 %.2f 下浮 5%%）", request.caller().user(), quote * 0.95, quote));
    };

    OctetKeyPair key;
    CodeAgentServer server;

    @BeforeAll
    void start() throws Exception {
        key = new OctetKeyPairGenerator(Curve.Ed25519).keyID("k1").generate();
        var tokens = PlatformTokens.fromKeys(new JWKSet(key.toPublicJWK()), Clock.systemUTC());
        server = CodeAgentServer.start(DESCRIPTION, TARGET_PRICE, tokens, 0);
    }

    @AfterAll
    void stop() {
        server.close();
    }

    String token(String audience, String kind, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder()
                .issuer("eab-identity")
                .audience(audience)
                .subject("zhangmin")
                .claim("tid", "t001")
                .claim("kind", kind)
                .claim("act", Map.of("sub", "agent:tenant.t001.agents/price-negotiation-assistant"))
                .expirationTime(Date.from(expires))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID("k1").build(), claims);
        jwt.sign(new Ed25519Signer(key));
        return jwt.serialize();
    }

    @Test
    @DisplayName("平台以张敏的身份调用代码 Agent：拿到 Agent Card，回复里用到调用方身份；缺少报价时追问")
    void callsCodeAgent() throws Exception {
        var card = A2aOutbound.fetchCard(server.url(), null, Map.of()).card();
        assertThat(card.name()).isEqualTo("目标价测算 Agent");
        String bearer = token("eab-a2a", "service", Instant.now().plusSeconds(300));
        var done = A2aOutbound.send(card, bearer, "最低报价 100", null, null, Duration.ofSeconds(10), null);
        assertThat(done.state()).isEqualTo("completed");
        assertThat(done.text()).contains("为 zhangmin 测算：目标价 95.00");
        var ask = A2aOutbound.send(card, bearer, "目标价怎么定", null, null, Duration.ofSeconds(10), null);
        assertThat(ask.state()).isEqualTo("input-required");
    }

    @Test
    @DisplayName("没有令牌、受众不对、种类不对、过期的令牌一律 401")
    void rejectsInvalidTokens() throws Exception {
        assertThat(post(null)).isEqualTo(401);
        assertThat(post(token("eab-platform", "service", Instant.now().plusSeconds(300)))).isEqualTo(401);
        assertThat(post(token("eab-a2a", "user", Instant.now().plusSeconds(300)))).isEqualTo(401);
        assertThat(post(token("eab-a2a", "service", Instant.now().minusSeconds(10)))).isEqualTo(401);
        var tokens = PlatformTokens.fromKeys(new JWKSet(), Clock.systemUTC());
        String forged = token("eab-a2a", "service", Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> tokens.verify("Bearer " + forged)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("生成的 RemoteAgent 声明只引用连接别名，不含地址")
    void declaration() {
        var asset = AgentDeclaration.remoteAgent(DESCRIPTION, "t001", "chengong", "TARGET_PRICE_AGENT", null);
        assertThat(asset.get("kind")).isEqualTo("RemoteAgent");
        assertThat(asset.toString())
                .contains("tenant.t001.remote", "TARGET_PRICE_AGENT", "platform-service-token")
                .doesNotContain("http");
    }

    int post(String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.url() + "/"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"SendMessage\"}"));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }
}

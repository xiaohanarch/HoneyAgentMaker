package dev.eab.erp;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.eab.erp.bo.BoController;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.events.EventTriggerController;
import dev.eab.erp.link.LocalPlatformLink;
import dev.eab.erp.meta.MetaController;
import dev.eab.erp.open.OpenController;
import dev.eab.erp.pages.PageController;
import dev.eab.erp.seed.Seeder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DemoERP 测试共用（对应 TS 版 test-support.ts）：本地签发与平台同格式的令牌、本地 JWKS 的平台连接、
 * 已灌好种子数据的应用，以及不启动 Spring 容器的 MockMvc。
 */
public final class TestErp {

    public static final Instant TODAY = Instant.parse("2026-10-15T08:00:00Z");
    public static final String INTERNAL_SECRET = "erp-test-secret";

    private static final OctetKeyPair KEY = generate();

    private static OctetKeyPair generate() {
        try {
            return new OctetKeyPairGenerator(Curve.Ed25519)
                    .keyID("test")
                    .algorithm(JWSAlgorithm.EdDSA)
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public final ErpServices services;
    public final LocalPlatformLink link;
    public final MockMvc mvc;

    private TestErp() {
        ErpDb db = ErpDb.open(":memory:");
        Seeder.seed(db, TODAY);
        link = new LocalPlatformLink(new JWKSet(KEY.toPublicJWK()));
        services = ErpServices.create(db, link, Clock.fixed(TODAY, ZoneOffset.UTC), INTERNAL_SECRET);
        mvc = MockMvcBuilders.standaloneSetup(
                        new HealthController(),
                        new OpenApiController(services),
                        new BoController(services),
                        new OpenController(services),
                        new MetaController(),
                        new PageController(services),
                        new EventTriggerController(services))
                .setControllerAdvice(new ErpExceptionHandler())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter(ErpJson.MAPPER))
                .build();
    }

    public static TestErp create() {
        return new TestErp();
    }

    public void close() {
        services.db().close();
    }

    /** 与平台签发的令牌格式相同：签发者 eab-identity，有效期 5 分钟 */
    static String sign(Map<String, Object> claims, String audience) {
        var builder = new JWTClaimsSet.Builder()
                .issuer("eab-identity")
                .audience(audience)
                .issueTime(Date.from(TODAY))
                .expirationTime(new Date(System.currentTimeMillis() + 300_000));
        claims.forEach(builder::claim);
        try {
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID("test").build(), builder.build());
            jwt.sign(new Ed25519Signer(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Delegation(
            String user, String tenant, List<String> scope, String agent, String mode, String delegationId) {
        public Delegation(String user, List<String> scope) {
            this(user, "t001", scope, "vendor.pur/supplier-performance-analyst", "on-behalf", null);
        }

        public Delegation tenant(String value) {
            return new Delegation(user, value, scope, agent, mode, delegationId);
        }

        public Delegation agent(String value) {
            return new Delegation(user, tenant, scope, value, mode, delegationId);
        }

        public Delegation delegated(String id) {
            return new Delegation(user, tenant, scope, agent, "delegated", id);
        }
    }

    /** 委托令牌：与平台令牌交换签出的格式相同 */
    public static String delegatedToken(Delegation d) {
        var claims = new java.util.LinkedHashMap<String, Object>();
        claims.put("kind", "delegated");
        claims.put("sub", d.user());
        claims.put("tid", d.tenant());
        claims.put("act", Map.of("sub", "agent:" + d.agent()));
        claims.put("scope", String.join(" ", d.scope()));
        claims.put("mode", d.mode());
        claims.put("agent_version", "1.0.0");
        if (d.delegationId() != null) claims.put("delegation_id", d.delegationId());
        return sign(claims, "demo-erp-bo");
    }

    public static String delegatedToken(String user, List<String> scope) {
        return delegatedToken(new Delegation(user, scope));
    }

    public static String clientToken(String tenant) {
        return sign(
                Map.of("kind", "client", "sub", "client:" + tenant + "-integration", "tid", tenant), "demo-erp-open");
    }

    /** 平台签发的只读页面令牌 */
    public static String pageToken(String user, String tenant) {
        return sign(Map.of("kind", "page", "sub", user, "tid", tenant), "demo-erp-pages");
    }

    public static String userToken(String user) {
        return sign(Map.of("kind", "user", "sub", user, "tid", "t001", "roles", List.of()), "eab-platform");
    }

    public static String bearer(String token) {
        return "Bearer " + token;
    }
}

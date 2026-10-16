package dev.eab.platform.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.Ed25519Signer;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 令牌签发与校验（ADR 0005），格式与 TS 版一致，DemoERP 和前端不需要改动。受众划分：
 *
 * <ul>
 *   <li>eab-platform：用户令牌，调用平台接口
 *   <li>eab-agent-runtime：Agent 令牌，由平台运行时持有，只用作令牌交换的 actor_token
 *   <li>demo-erp-bo：委托令牌（用户 + Agent），调用 BO API
 *   <li>demo-erp-open：集成令牌（客户端凭证），只能调用 Open API
 *   <li>demo-erp-pages：只读页面的深链接令牌
 * </ul>
 */
public final class TokenService {

    public static final String ISSUER = "eab-identity";

    public static final class Audience {
        private Audience() {}

        public static final String PLATFORM = "eab-platform";
        public static final String AGENT_RUNTIME = "eab-agent-runtime";
        public static final String BO = "demo-erp-bo";
        public static final String OPEN = "demo-erp-open";
        public static final String PAGES = "demo-erp-pages";
        /** 平台调用外部 Agent 时携带的服务令牌（A2A 出站，ADR 0012） */
        public static final String A2A = "eab-a2a";
    }

    /** 有效期（秒） */
    public static final long USER_TTL = 8 * 3600;

    public static final long AGENT_TTL = 15 * 60;
    public static final long CLIENT_TTL = 3600;
    public static final long DELEGATED_TTL = 300;
    public static final long PAGE_TTL = 600;
    public static final long SERVICE_TTL = 300;

    public static final class TokenException extends RuntimeException {
        private final String code;

        public TokenException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record UserClaims(String sub, String tid, List<String> roles) {}

    /** @param sub agent:&lt;namespace&gt;/&lt;name&gt;；scope 是 Agent 声明的 BO 操作，空格分隔 */
    public record AgentClaims(String sub, String tid, String scope, String agentVersion) {}

    private final IdentityKeys keys;
    private final Clock clock;

    public TokenService(IdentityKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public Map<String, Object> jwks() {
        return keys.jwks();
    }

    /** 签发时间和过期时间都用注入的时钟计算，测试可以固定时间 */
    String sign(Map<String, Object> claims, String audience, long ttlSeconds) {
        Instant now = clock.instant();
        long issuedAt = now.getEpochSecond();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(audience)
                .issueTime(new Date(issuedAt * 1000))
                .expirationTime(new Date((issuedAt + ttlSeconds) * 1000))
                .jwtID(UUID.randomUUID().toString());
        claims.forEach(builder::claim);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                .keyID(keys.kid())
                .type(JOSEObjectType.JWT)
                .build();
        SignedJWT jwt = new SignedJWT(header, builder.build());
        try {
            jwt.sign(new Ed25519Signer(keys.jwk()));
        } catch (JOSEException e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
        return jwt.serialize();
    }

    public String issueUser(UserClaims claims) {
        return sign(
                Map.of("kind", "user", "sub", claims.sub(), "tid", claims.tid(), "roles", claims.roles()),
                Audience.PLATFORM,
                USER_TTL);
    }

    public String issueAgent(AgentClaims claims) {
        return sign(
                Map.of(
                        "kind",
                        "agent",
                        "sub",
                        claims.sub(),
                        "tid",
                        claims.tid(),
                        "scope",
                        claims.scope(),
                        "agent_version",
                        claims.agentVersion()),
                Audience.AGENT_RUNTIME,
                AGENT_TTL);
    }

    public String issueClient(String sub, String tid) {
        return sign(Map.of("kind", "client", "sub", sub, "tid", tid), Audience.OPEN, CLIENT_TTL);
    }

    /** DemoERP 只读页面的深链接令牌：只能打开页面，按该用户的数据权限展示 */
    public String issuePage(String sub, String tid) {
        return sign(Map.of("kind", "page", "sub", sub, "tid", tid), Audience.PAGES, PAGE_TTL);
    }

    /**
     * 服务令牌：平台以某个 Agent 的身份、代表某个用户调用外部 Agent。sub 是最终用户，act 是发起调用的平台 Agent，
     * target 是被调用的远程 Agent 资产。外部 Agent 按平台的 JWKS 验签。
     */
    public String issueService(String user, String tenant, String actorAgent, String target) {
        return sign(
                Map.of(
                        "kind", "service",
                        "sub", user,
                        "tid", tenant,
                        "act", Map.of("sub", actorAgent),
                        "target", target),
                Audience.A2A,
                SERVICE_TTL);
    }

    public String issueDelegated(Map<String, Object> claims) {
        var all = new java.util.LinkedHashMap<>(claims);
        all.put("kind", "delegated");
        return sign(all, Audience.BO, DELEGATED_TTL);
    }

    /** 校验签名、签发者、受众、有效期和令牌种类，任何一项不符都抛 TokenException */
    public JWTClaimsSet verify(String token, String audience, String kind) {
        JWTClaimsSet claims;
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.EdDSA.equals(jwt.getHeader().getAlgorithm())) {
                throw new TokenException("INVALID_TOKEN", "令牌无效：签名算法不是 EdDSA");
            }
            if (!jwt.verify(new Ed25519Verifier(keys.jwk().toPublicJWK()))) {
                throw new TokenException("INVALID_TOKEN", "令牌无效：签名校验失败");
            }
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            throw new TokenException("INVALID_TOKEN", "令牌无效：" + e.getMessage());
        }
        if (!ISSUER.equals(claims.getIssuer())) throw new TokenException("INVALID_TOKEN", "令牌无效：签发者不符");
        if (claims.getAudience() == null || !claims.getAudience().contains(audience)) {
            throw new TokenException("INVALID_TOKEN", "令牌无效：受众不符，需要 " + audience);
        }
        Date expires = claims.getExpirationTime();
        if (expires == null || !expires.toInstant().isAfter(clock.instant())) {
            throw new TokenException("INVALID_TOKEN", "令牌无效：已过期");
        }
        Object actual = claims.getClaim("kind");
        if (!kind.equals(actual)) throw new TokenException("WRONG_TOKEN_KIND", "需要 " + kind + " 令牌，收到 " + actual);
        return claims;
    }

    public UserClaims verifyUser(String token) {
        JWTClaimsSet claims = verify(token, Audience.PLATFORM, "user");
        try {
            List<String> roles = claims.getStringListClaim("roles");
            return new UserClaims(claims.getSubject(), claims.getStringClaim("tid"), roles == null ? List.of() : roles);
        } catch (ParseException e) {
            throw new TokenException("INVALID_TOKEN", "令牌无效：声明格式不对");
        }
    }

    public AgentClaims verifyAgent(String token) {
        JWTClaimsSet claims = verify(token, Audience.AGENT_RUNTIME, "agent");
        try {
            return new AgentClaims(
                    claims.getSubject(),
                    claims.getStringClaim("tid"),
                    claims.getStringClaim("scope"),
                    claims.getStringClaim("agent_version"));
        } catch (ParseException e) {
            throw new TokenException("INVALID_TOKEN", "令牌无效：声明格式不对");
        }
    }
}

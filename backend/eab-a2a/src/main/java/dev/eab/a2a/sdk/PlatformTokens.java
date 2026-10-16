package dev.eab.a2a.sdk;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 校验平台签发的服务令牌：EdDSA 签名（公钥按 kid 从平台 JWKS 取）、签发者 eab-identity、受众 eab-a2a、种类 service、未过期。
 * 任何一项不符都抛 {@link SecurityException}，没有令牌也一样，不存在“匿名放行”。
 */
public final class PlatformTokens {

    public static final String ISSUER = "eab-identity";
    public static final String AUDIENCE = "eab-a2a";
    static final Duration JWKS_TTL = Duration.ofMinutes(5);

    private final Supplier<JWKSet> fetch;
    private final Clock clock;
    private JWKSet cached;
    private Instant cachedAt = Instant.EPOCH;

    PlatformTokens(Supplier<JWKSet> fetch, Clock clock) {
        this.fetch = fetch;
        this.clock = clock;
    }

    /** 从平台的 /identity/v1/jwks.json 取公钥 */
    public static PlatformTokens fromPlatform(String platformUrl) {
        URI jwks = URI.create(platformUrl.replaceAll("/+$", "") + "/identity/v1/jwks.json");
        return new PlatformTokens(
                () -> {
                    try {
                        return JWKSet.load(jwks.toURL());
                    } catch (Exception e) {
                        throw new SecurityException("取不到平台公钥：" + e.getMessage(), e);
                    }
                },
                Clock.systemUTC());
    }

    /** 测试或离线环境：直接给定公钥集合 */
    public static PlatformTokens fromKeys(JWKSet keys, Clock clock) {
        return new PlatformTokens(() -> keys, clock);
    }

    private synchronized JWKSet keys(boolean refresh) {
        Instant now = clock.instant();
        if (refresh || cached == null || cachedAt.plus(JWKS_TTL).isBefore(now)) {
            cached = fetch.get();
            cachedAt = now;
        }
        return cached;
    }

    public CodeAgent.Caller verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new SecurityException("缺少平台签发的服务令牌");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()));
            if (!JWSAlgorithm.EdDSA.equals(jwt.getHeader().getAlgorithm())) throw new SecurityException("签名算法不是 EdDSA");
            String kid = jwt.getHeader().getKeyID();
            var key = keys(false).getKeyByKeyId(kid);
            if (key == null) key = keys(true).getKeyByKeyId(kid);
            if (!(key instanceof OctetKeyPair okp) || !jwt.verify(new Ed25519Verifier(okp.toPublicJWK()))) {
                throw new SecurityException("签名校验失败");
            }
            var claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (!ISSUER.equals(claims.getIssuer())
                    || claims.getAudience() == null
                    || !claims.getAudience().contains(AUDIENCE)
                    || !"service".equals(claims.getStringClaim("kind"))
                    || exp == null
                    || exp.toInstant().isBefore(clock.instant())) {
                throw new SecurityException("令牌的签发者、受众、种类或有效期不符");
            }
            Object act = claims.getClaim("act");
            String agent = act instanceof Map<?, ?> m && m.get("sub") != null ? String.valueOf(m.get("sub")) : "";
            return new CodeAgent.Caller(claims.getSubject(), claims.getStringClaim("tid"), agent);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new SecurityException("服务令牌无效：" + e.getMessage(), e);
        }
    }
}

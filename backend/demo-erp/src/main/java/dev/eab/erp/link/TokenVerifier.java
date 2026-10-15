package dev.eab.erp.link;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按平台的 JWKS 校验 EdDSA 令牌：签发者固定为 eab-identity，受众按调用方要求。
 * Nimbus 的通用密钥选择器不能把 OKP 密钥转换成 JDK 密钥，这里按 kid 选出 Ed25519 公钥后直接验签。
 */
final class TokenVerifier {

    static final String ISSUER = "eab-identity";

    private final JWKSource<SecurityContext> keys;

    TokenVerifier(JWKSource<SecurityContext> keys) {
        this.keys = keys;
    }

    Map<String, Object> verify(String token, String audience) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.EdDSA.equals(jwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("签名算法不是 EdDSA");
            }
            var matcher = new JWKMatcher.Builder()
                    .keyType(KeyType.OKP)
                    .curve(Curve.Ed25519)
                    .keyID(jwt.getHeader().getKeyID())
                    .build();
            List<JWK> candidates = keys.get(new JWKSelector(matcher), null);
            boolean verified = false;
            for (JWK key : candidates) {
                if (jwt.verify(new Ed25519Verifier(((OctetKeyPair) key).toPublicJWK()))) {
                    verified = true;
                    break;
                }
            }
            if (!verified) throw new IllegalArgumentException("签名无效或没有匹配的密钥");
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            new DefaultJWTClaimsVerifier<SecurityContext>(
                            audience, new JWTClaimsSet.Builder().issuer(ISSUER).build(), Set.of("exp", "iat"))
                    .verify(claims, null);
            return claims.toJSONObject();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌无效：" + e.getMessage(), e);
        }
    }
}

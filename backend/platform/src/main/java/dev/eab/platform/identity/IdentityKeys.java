package dev.eab.platform.identity;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import dev.eab.platform.crypto.PemKeys;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;

/**
 * 身份服务的签名密钥（EdDSA / Ed25519），与资产签名密钥分开存放。
 * 生产环境应由企业 IdP 签发令牌，平台只做令牌交换与校验（ADR 0005）。
 */
public record IdentityKeys(OctetKeyPair jwk, String kid) {

    public static IdentityKeys of(KeyPair keys, String kid) {
        OctetKeyPair jwk = new OctetKeyPair.Builder(
                        Curve.Ed25519, Base64URL.encode(PemKeys.rawPublicKey(keys.getPublic())))
                .d(Base64URL.encode(PemKeys.rawPrivateKey(keys.getPrivate())))
                .keyID(kid)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.EdDSA)
                .build();
        return new IdentityKeys(jwk, kid);
    }

    public static IdentityKeys ephemeral() {
        return of(PemKeys.generateEd25519(), "eab-identity-test");
    }

    /** 从数据目录读取，首次启动时生成；文件格式与 TS 版相同 */
    public static IdentityKeys fromDirectory(Path dir) {
        return of(PemKeys.loadOrCreateEd25519(dir.resolve("identity-ed25519.pem")), "eab-identity-1");
    }

    /** 公开的校验公钥，DemoERP 等下游据此校验令牌 */
    public Map<String, Object> jwks() {
        return new JWKSet(jwk.toPublicJWK()).toJSONObject(true);
    }
}

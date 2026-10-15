package dev.eab.platform.registry;

import dev.eab.platform.crypto.PemKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Ed25519 签名，使用 JDK 自带的算法实现 */
public final class Ed25519Signer implements Signer {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final byte[] digestKey;

    public Ed25519Signer(KeyPair keys) {
        this.privateKey = keys.getPrivate();
        this.publicKey = keys.getPublic();
        // 摘要密钥由私钥派生，不另外保存
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("eab-redaction-digest".getBytes(StandardCharsets.UTF_8));
            sha.update(privateKey.getEncoded());
            this.digestKey = sha.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法派生摘要密钥", e);
        }
    }

    /** 测试和命令行用的临时密钥 */
    public static Ed25519Signer ephemeral() {
        return new Ed25519Signer(PemKeys.generateEd25519());
    }

    /** 从数据目录读取平台密钥（PKCS#8 PEM，与 TS 版文件格式相同），首次启动时生成。密钥文件不进仓库 */
    public static Ed25519Signer fromDirectory(Path dir) {
        return new Ed25519Signer(PemKeys.loadOrCreateEd25519(dir.resolve("platform-ed25519.pem")));
    }

    @Override
    public String sign(String payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    @Override
    public boolean verify(String payload, String signatureText) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureText));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String digest(String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(digestKey, "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }
}

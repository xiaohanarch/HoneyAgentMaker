package dev.eab.platform.crypto;

import com.google.crypto.tink.subtle.Ed25519Sign;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/** Ed25519 密钥的生成与 PEM 读写（PKCS#8），文件格式与 Node 的 crypto 导出的一致 */
public final class PemKeys {

    private PemKeys() {}

    public static KeyPair generateEd25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 不支持 Ed25519", e);
        }
    }

    /** 文件不存在时生成并以 0600 权限写入 */
    public static KeyPair loadOrCreateEd25519(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.toAbsolutePath().getParent());
                KeyPair keys = generateEd25519();
                Files.writeString(path, toPem(keys.getPrivate()), StandardCharsets.US_ASCII);
                restrictPermissions(path);
                return keys;
            }
            PrivateKey privateKey = fromPem(Files.readString(path, StandardCharsets.US_ASCII));
            return new KeyPair(publicKeyOf(privateKey), privateKey);
        } catch (IOException e) {
            throw new UncheckedIOException("无法读写密钥文件 " + path, e);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows 没有 POSIX 权限，开发机上忽略
        }
    }

    static String toPem(PrivateKey key) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    static PrivateKey fromPem(String pem) {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("密钥文件不是 Ed25519 的 PKCS#8 私钥", e);
        }
    }

    /** X.509 SubjectPublicKeyInfo 里 Ed25519 公钥前的固定前缀（RFC 8410） */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** 由私钥种子推出公钥，派生算法用 Tink（Nimbus JOSE 依赖它做 EdDSA） */
    public static PublicKey publicKeyOf(PrivateKey privateKey) {
        try {
            byte[] seed = ((EdECPrivateKey) privateKey).getBytes().orElseThrow();
            byte[] raw = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).getPublicKey();
            byte[] encoded = new byte[X509_PREFIX.length + raw.length];
            System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
            System.arraycopy(raw, 0, encoded, X509_PREFIX.length, raw.length);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法由私钥推出公钥", e);
        }
    }

    /** 公钥的原始 32 字节，JWK 的 x 字段用它 */
    public static byte[] rawPublicKey(PublicKey publicKey) {
        byte[] encoded = publicKey.getEncoded();
        return java.util.Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
    }

    /** 私钥种子的原始 32 字节，JWK 的 d 字段用它 */
    public static byte[] rawPrivateKey(PrivateKey privateKey) {
        return ((EdECPrivateKey) privateKey).getBytes().orElseThrow();
    }
}

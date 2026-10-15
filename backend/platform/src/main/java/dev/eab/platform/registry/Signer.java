package dev.eab.platform.registry;

/**
 * 标品包签名（主提示词 5.2 节）。演示用内容哈希加平台私钥签名：
 * 对解析后资产的规范化 JSON（RFC 8785）取 sha256，再用 Ed25519 签名。
 * 生产环境应把私钥放进 KMS/HSM，签名在构建流水线里完成。
 */
public interface Signer {

    String sign(String payload);

    boolean verify(String payload, String signature);

    /**
     * 带密钥的摘要（HMAC-SHA256 截断到 64 位），用于裁剪占位块里的“是否已变更”标记。
     * 没有密钥就无法用字典比对猜出被隐藏的原文（ADR 0004）。
     */
    String digest(String text);

    /**
     * 签名内容同时绑定资产标识、版本、发布状态和内容哈希：
     * 把一个版本的签名挪到另一个版本、或者把已下线的版本在库里改回已发布，都会验签失败。
     */
    static String signingPayload(String ref, String contentHash) {
        return "eab.dev/v1|" + ref + "|published|" + contentHash;
    }
}

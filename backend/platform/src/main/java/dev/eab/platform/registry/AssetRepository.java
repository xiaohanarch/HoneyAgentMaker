package dev.eab.platform.registry;

import dev.eab.spec.AssetResolver.Status;
import java.util.List;
import java.util.Optional;

/** 资产存储接口。业务逻辑只依赖它，不直接写 SQL */
public interface AssetRepository {

    /**
     * 资产版本表的一行：一个资产的一个版本，版本发布后内容不可变。
     *
     * @param contentYaml 作者提交的 YAML 原文，用于展示和差异对比
     * @param contentJson 解析后（含默认值）的规范化 JSON，签名和哈希都基于它
     * @param signature 标品层、平台层发布时的 Ed25519 签名（base64）
     */
    record AssetRecord(
            long id,
            String kind,
            String namespace,
            String name,
            String version,
            String layer,
            String tenantId,
            Status status,
            String contentYaml,
            String contentJson,
            String contentHash,
            String signature,
            String changeNote,
            String createdBy,
            String createdAt,
            String updatedAt) {

        public String ref() {
            return namespace + "/" + name + "@" + version;
        }
    }

    record NewAssetRecord(
            String kind,
            String namespace,
            String name,
            String version,
            String layer,
            String tenantId,
            String contentYaml,
            String contentJson,
            String contentHash,
            String changeNote,
            String createdBy,
            String createdAt) {}

    /** 查询条件，字段为 null 表示不限 */
    record AssetFilter(String kind, String namespace, String layer, String tenantId, Status status) {

        public static AssetFilter all() {
            return new AssetFilter(null, null, null, null, null);
        }

        public AssetFilter withStatus(Status value) {
            return new AssetFilter(kind, namespace, layer, tenantId, value);
        }
    }

    /** 新版本一律以草稿写入 */
    AssetRecord insert(NewAssetRecord record);

    Optional<AssetRecord> find(String namespace, String name, String version);

    List<AssetRecord> listVersions(String namespace, String name);

    List<AssetRecord> list(AssetFilter filter);

    AssetRecord updateStatus(long id, Status status, String signature, String updatedAt);
}

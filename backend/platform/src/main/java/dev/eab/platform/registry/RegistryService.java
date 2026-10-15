package dev.eab.platform.registry;

import dev.eab.common.IsoTime;
import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.AssetRepository.AssetRecord;
import dev.eab.platform.registry.AssetRepository.NewAssetRecord;
import dev.eab.platform.registry.RegistryResult.ErrorCode;
import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetParser.AssetIssue;
import dev.eab.spec.AssetParser.ParseResult;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Json;
import dev.eab.spec.Layers;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.Lint;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import dev.eab.spec.model.Asset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.node.ObjectNode;

/**
 * 资产注册中心：存储、版本、状态、命名空间与按层可见性（主提示词 5.2 节）。
 * 组装器通过 resolverFor(viewer) 取解析器，只能读到该身份可见的资产，读出的标品层资产都验过签名。
 */
public final class RegistryService {

    /** 发布门禁：发布前运行静态检查和试组装，返回阻止发布的问题。由装配层注入 */
    @FunctionalInterface
    public interface PublishGate {
        List<AssetIssue> check(Asset asset, Viewer publisher, RegistryService registry);
    }

    /**
     * @param signed 签名列非空；是否验签通过以读取时的完整性检查为准
     */
    public record AssetSummary(
            String kind,
            String namespace,
            String name,
            String version,
            String layer,
            Status status,
            String contentHash,
            boolean signed,
            String changeNote,
            String updatedAt) {}

    public record AssetView(AssetSummary summary, ObjectNode asset) {}

    /** @param rejected 完整性检查没通过而被拒绝加载的资产 */
    public record LatestPublished(List<Asset> assets, List<String> rejected) {}

    /** 状态流转（主提示词第 12 节）：草稿 → 测试中 → 已发布 → 已下线，草稿可以直接发布 */
    private static final Map<Status, List<Status>> TRANSITIONS = Map.of(
            Status.draft, List.of(Status.testing, Status.published, Status.retired),
            Status.testing, List.of(Status.draft, Status.published, Status.retired),
            Status.published, List.of(Status.retired),
            Status.retired, List.of());

    private final AssetRepository assets;
    private final AuditStore audit;
    private final Signer signer;
    private final PublishGate publishGate;
    private final Clock clock;
    /** 同一条完整性失败只写一次审计，避免反复读取时把审计表写爆 */
    private final Set<String> reportedIntegrity = ConcurrentHashMap.newKeySet();
    /** 按内容哈希缓存解析结果；内容不可变，哈希相同即内容相同 */
    private final Map<String, Optional<Asset>> parsed = new ConcurrentHashMap<>();

    public RegistryService(
            AssetRepository assets, AuditStore audit, Signer signer, PublishGate publishGate, Clock clock) {
        this.assets = assets;
        this.audit = audit;
        this.signer = signer;
        this.publishGate = publishGate;
        this.clock = clock;
    }

    public Signer signer() {
        return signer;
    }

    /** 标品层和平台层资产发布时签名，读取时验签 */
    private static boolean isSignedLayer(String namespace) {
        return Layers.layerOfNamespace(namespace) != Layer.tenant;
    }

    /** 租户身份的操作在审计里带上租户编号，租户管理员可以查到本租户的记录 */
    private static AuditStore.Builder event(
            Viewer viewer, String action, String target, String decision, String reason) {
        AuditStore.Builder builder = NewAuditEvent.of(viewer.format(), action, target, decision, reason);
        if (viewer instanceof Viewer.Tenant t) builder.tenantId(t.tenantId());
        return builder;
    }

    private static String canonical(Asset asset) {
        return CanonicalJson.of(Json.toTree(asset));
    }

    // ── 写入 ───────────────────────────────────────────────────────────────

    /** 提交 YAML，创建一个新的草稿版本。版本号已存在时拒绝，已有版本不可改 */
    public RegistryResult<AssetSummary> put(Viewer viewer, String yaml, String changeNote) {
        ParseResult result = AssetParser.parseYaml(yaml);
        if (!result.ok()) return RegistryResult.fail(ErrorCode.ASSET_INVALID, "资产校验未通过", result.issues());
        return insert(viewer, result.asset(), yaml, changeNote);
    }

    /** 变更状态；发布前过门禁，标品层、平台层发布时签名，下线时作废签名 */
    public RegistryResult<AssetSummary> setStatus(
            Viewer viewer, String namespace, String name, String version, Status status) {
        String target = namespace + "/" + name + "@" + version;
        if (!authorizeWrite(viewer, namespace, target, "registry.status")) {
            return RegistryResult.fail(
                    ErrorCode.REGISTRY_WRITE_FORBIDDEN, "身份 " + viewer.format() + " 不能修改 " + namespace + " 下的资产");
        }
        Optional<AssetRecord> found = assets.find(namespace, name, version);
        if (found.isEmpty()) return RegistryResult.fail(ErrorCode.NOT_FOUND, "资产 " + target + " 不存在");
        AssetRecord record = found.get();
        if (!TRANSITIONS.get(record.status()).contains(status)) {
            return RegistryResult.fail(ErrorCode.INVALID_TRANSITION, "不能从 " + record.status() + " 变更为 " + status);
        }

        String signature = status == Status.retired ? null : record.signature();
        if (status == Status.published) {
            // 签名前重新核对内容哈希，库里被改过的内容不能拿到合法签名
            Optional<Asset> asset = verifyContent(record);
            if (asset.isEmpty()) {
                return RegistryResult.fail(ErrorCode.INTEGRITY_VIOLATION, target + " 的存储内容与哈希不一致，拒绝发布");
            }
            List<AssetIssue> blocked = publishGate == null ? List.of() : publishGate.check(asset.get(), viewer, this);
            if (!blocked.isEmpty()) {
                audit.append(event(viewer, "registry.publish", target, "deny", "发布门禁未通过：" + blocked.size() + " 个问题"));
                return RegistryResult.fail(ErrorCode.PUBLISH_BLOCKED, "发布门禁未通过", blocked);
            }
            if (isSignedLayer(namespace)) signature = signer.sign(Signer.signingPayload(target, record.contentHash()));
        }

        AssetRecord updated = assets.updateStatus(record.id(), status, signature, IsoTime.now(clock));
        audit.append(event(
                viewer,
                "registry.status",
                target,
                "allow",
                record.status() + " → " + status + (status == Status.published && signature != null ? "，已签名" : "")));
        return RegistryResult.ok(summarize(updated));
    }

    /**
     * 以本层身份写入一份已解析的资产并保持为草稿，用于启动灌数的第一轮。
     * 同版本同内容时跳过；同版本不同内容时报错，标品改动必须升版本号；已有版本完整性不过时报错。
     */
    public RegistryResult<AssetSummary> stageAsset(Viewer viewer, Asset asset, String yaml) {
        var m = asset.metadata();
        Optional<AssetRecord> existing = assets.find(m.namespace(), m.name(), m.version());
        if (existing.isEmpty()) return insert(viewer, asset, yaml, null);
        if (!existing.get().contentHash().equals(CanonicalJson.hashOf(canonical(asset)))) {
            return RegistryResult.fail(ErrorCode.VERSION_EXISTS, asset.versionedRef() + " 已存在且内容不同，修改资产必须升版本号");
        }
        if (trusted(existing.get()).isEmpty()) {
            return RegistryResult.fail(ErrorCode.INTEGRITY_VIOLATION, asset.versionedRef() + " 的已存版本完整性校验失败，拒绝加载");
        }
        return RegistryResult.ok(summarize(existing.get()));
    }

    /** 写入并发布；已发布的同内容版本直接返回 */
    public RegistryResult<AssetSummary> publishAsset(Viewer viewer, Asset asset, String yaml) {
        RegistryResult<AssetSummary> staged = stageAsset(viewer, asset, yaml);
        if (!staged.ok() || staged.value().status() == Status.published) return staged;
        var m = asset.metadata();
        return setStatus(viewer, m.namespace(), m.name(), m.version(), Status.published);
    }

    private RegistryResult<AssetSummary> insert(Viewer viewer, Asset asset, String yaml, String changeNote) {
        var m = asset.metadata();
        String target = asset.versionedRef();
        if (!authorizeWrite(viewer, m.namespace(), target, "registry.write")) {
            return RegistryResult.fail(
                    ErrorCode.REGISTRY_WRITE_FORBIDDEN, "身份 " + viewer.format() + " 不能写入 " + m.namespace() + " 下的资产");
        }
        // 引用越界在写入时就拒绝：租户不能引用其他租户的资产，标品不能引用租户资产
        var outOfScope = Lint.outOfScopeReferences(asset);
        if (!outOfScope.isEmpty()) {
            return RegistryResult.fail(
                    ErrorCode.ASSET_INVALID,
                    "资产引用了不允许引用的命名空间",
                    outOfScope.stream()
                            .map(r -> new AssetIssue(
                                    "REFERENCE_OUT_OF_SCOPE",
                                    "不能引用 " + r.ref() + "：只能引用平台层、标品层和本租户的资产",
                                    r.path(),
                                    null,
                                    null))
                            .toList());
        }
        if (assets.find(m.namespace(), m.name(), m.version()).isPresent()) {
            return RegistryResult.fail(ErrorCode.VERSION_EXISTS, target + " 已存在，资产版本不可修改，请使用新的版本号");
        }
        String json = canonical(asset);
        AssetRecord record = assets.insert(new NewAssetRecord(
                asset.kind(),
                m.namespace(),
                m.name(),
                m.version(),
                asset.layer().name(),
                Layers.tenantIdOfNamespace(m.namespace()).orElse(null),
                yaml,
                json,
                CanonicalJson.hashOf(json),
                changeNote,
                viewer.format(),
                IsoTime.now(clock)));
        audit.append(event(viewer, "registry.write", target, "allow", "新建 " + asset.kind() + " 版本"));
        return RegistryResult.ok(summarize(record));
    }

    /** 只有本层身份能写本层命名空间，越层写入在这里拒绝并写审计 */
    private boolean authorizeWrite(Viewer viewer, String namespace, String target, String action) {
        if (Visibility.canWriteNamespace(viewer, namespace)) return true;
        audit.append(event(viewer, action, target, "deny", viewer.format() + " 无权写入命名空间 " + namespace));
        return false;
    }

    // ── 按查看者读取 ───────────────────────────────────────────────────────

    /** 按查看者读取单个资产（省略版本时取该身份可见的最新版本），返回裁剪后的视图 */
    public RegistryResult<AssetView> view(Viewer viewer, String namespace, String name, String version) {
        List<AssetRecord> candidates = version == null
                ? assets.listVersions(namespace, name)
                : assets.find(namespace, name, version).map(List::of).orElse(List.of());
        Optional<AssetRecord> record = candidates.stream()
                .filter(r -> canRead(viewer, r))
                .max(Comparator.comparing(AssetRecord::version, Versions::compare));
        Optional<Asset> asset = record.flatMap(this::trusted);
        // 无权读取与不存在返回同样的结果，不泄露资产是否存在
        if (asset.isEmpty()) {
            return RegistryResult.fail(
                    ErrorCode.NOT_FOUND,
                    "资产 " + namespace + "/" + name + (version == null ? "" : "@" + version) + " 不存在");
        }
        return RegistryResult.ok(
                new AssetView(summarize(record.get()), Visibility.redactAsset(asset.get(), viewer, signer::digest)));
    }

    /** 列出查看者能看到的资产版本摘要 */
    public List<AssetSummary> list(Viewer viewer, AssetFilter filter) {
        return assets.list(filter).stream()
                .filter(r -> canRead(viewer, r))
                .map(RegistryService::summarize)
                .toList();
    }

    /**
     * 能否读取某条记录：按层可见性，另外租户只能看到其他层的已发布版本。
     * 只看元数据和 kind 相关字段，不做完整性检查。
     */
    public boolean canRead(Viewer viewer, AssetRecord record) {
        Optional<Asset> asset = parseStored(record);
        if (asset.isEmpty() || !Visibility.canReadAsset(viewer, asset.get())) return false;
        return !(viewer instanceof Viewer.Tenant)
                || record.layer().equals("tenant")
                || record.status() == Status.published;
    }

    // ── 组装用的解析器 ─────────────────────────────────────────────────────

    /** 按查看者限定范围的解析器，组装器只能读到该身份可见的资产 */
    public ScopedResolver resolverFor(Viewer viewer) {
        return new ScopedResolver(viewer, new ScopedResolver.Source() {
            @Override
            public List<AssetRecord> records(String namespace, String name) {
                return assets.listVersions(namespace, name);
            }

            @Override
            public boolean canRead(AssetRecord record) {
                return RegistryService.this.canRead(viewer, record);
            }

            @Override
            public Optional<Asset> trusted(AssetRecord record) {
                return RegistryService.this.trusted(record);
            }
        });
    }

    /** 已发布的某类资产，每个资产取最新版本；可按查看者过滤，完整性不过的单独列出 */
    public LatestPublished latestPublished(AssetFilter filter, Viewer viewer) {
        Map<String, AssetRecord> byKey = new LinkedHashMap<>();
        for (AssetRecord record : assets.list(filter.withStatus(Status.published))) {
            if (viewer != null && !canRead(viewer, record)) continue;
            String key = record.namespace() + "/" + record.name();
            AssetRecord current = byKey.get(key);
            if (current == null || Versions.compare(record.version(), current.version()) > 0) byKey.put(key, record);
        }
        List<Asset> loaded = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (AssetRecord record : byKey.values()) {
            trusted(record).ifPresentOrElse(loaded::add, () -> rejected.add(record.ref()));
        }
        return new LatestPublished(loaded, rejected);
    }

    /** 内容与哈希一致时返回解析后的资产（不验签） */
    private Optional<Asset> verifyContent(AssetRecord record) {
        if (!CanonicalJson.hashOf(record.contentJson()).equals(record.contentHash())) return Optional.empty();
        return parseStored(record);
    }

    /**
     * 取出可信的资产内容：内容哈希必须与存储内容一致；标品层、平台层的已发布版本还必须验签。
     * 不符时拒绝加载并写审计（主提示词 5.2 节“签名不符拒绝加载”）。
     */
    Optional<Asset> trusted(AssetRecord record) {
        String target = record.ref();
        if (!CanonicalJson.hashOf(record.contentJson()).equals(record.contentHash())) {
            return reject(record, "内容哈希与存储内容不一致，可能被篡改");
        }
        if (record.status() == Status.published && isSignedLayer(record.namespace())) {
            if (record.signature() == null) return reject(record, "标品资产缺少签名");
            if (!signer.verify(Signer.signingPayload(target, record.contentHash()), record.signature())) {
                return reject(record, "签名校验失败");
            }
        }
        Optional<Asset> asset = parseStored(record);
        return asset.isPresent() ? asset : reject(record, "存储内容无法通过 schema 校验");
    }

    private Optional<Asset> reject(AssetRecord record, String reason) {
        String key = record.ref() + "|" + record.contentHash() + "|" + record.signature() + "|" + reason;
        if (reportedIntegrity.add(key)) {
            audit.append(NewAuditEvent.of("platform", "registry.integrity", record.ref(), "deny", reason));
        }
        return Optional.empty();
    }

    private Optional<Asset> parseStored(AssetRecord record) {
        return parsed.computeIfAbsent(
                record.contentHash() + "|" + record.contentJson().hashCode(), k -> {
                    try {
                        ParseResult result = AssetParser.parseObject(Json.MAPPER.readTree(record.contentJson()));
                        return result.ok() ? Optional.of(result.asset()) : Optional.empty();
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                });
    }

    static AssetSummary summarize(AssetRecord r) {
        return new AssetSummary(
                r.kind(),
                r.namespace(),
                r.name(),
                r.version(),
                r.layer(),
                r.status(),
                r.contentHash(),
                r.signature() != null,
                r.changeNote(),
                r.updatedAt());
    }
}

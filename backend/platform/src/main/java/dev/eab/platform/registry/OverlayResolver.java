package dev.eab.platform.registry;

import dev.eab.spec.AssetResolver;
import dev.eab.spec.model.Asset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 私有环境的解析器（ADR 0010）：在租户的解析范围之上叠加开发者工作区里的草稿。叠加的资产按“已发布”对待，
 * 并遮住注册中心里同名资产的全部版本，这样草稿和它引用的已发布资产可以一起组装、运行。只用于测试运行和评测，
 * 不改变注册中心，也不影响其他用户。
 */
public final class OverlayResolver implements AssetResolver {

    private final ScopedResolver base;
    private final Map<String, Asset> overlay = new LinkedHashMap<>();

    public OverlayResolver(ScopedResolver base, List<? extends Asset> drafts) {
        this.base = base;
        for (Asset asset : drafts)
            overlay.put(key(asset.metadata().namespace(), asset.metadata().name()), asset);
    }

    private static String key(String namespace, String name) {
        return namespace + "/" + name;
    }

    public ScopedResolver base() {
        return base;
    }

    @Override
    public List<VersionInfo> listVersions(String namespace, String name) {
        Asset draft = overlay.get(key(namespace, name));
        if (draft != null) return List.of(new VersionInfo(draft.metadata().version(), Status.published));
        return base.listVersions(namespace, name);
    }

    @Override
    public Optional<Asset> get(String namespace, String name, String version) {
        Asset draft = overlay.get(key(namespace, name));
        if (draft != null) {
            return draft.metadata().version().equals(version) ? Optional.of(draft) : Optional.empty();
        }
        return base.get(namespace, name, version);
    }

    public List<String> integrityFailures() {
        return base.integrityFailures();
    }
}

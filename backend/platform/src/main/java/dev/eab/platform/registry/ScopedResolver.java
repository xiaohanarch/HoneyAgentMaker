package dev.eab.platform.registry;

import dev.eab.platform.registry.AssetRepository.AssetRecord;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 按查看者限定范围的解析器。组装器经由它读资产时：
 * 看不到该身份无权读取的资产（其他租户的资产、对租户而言其他层的草稿），表现为“不存在”；
 * 完整性检查失败的资产被拒绝加载，并记录在 integrityFailures 里，由调用方让组装失败，
 * 而不是静默地少一条平台规则或一个扩展。
 */
public final class ScopedResolver implements AssetResolver {

    /** 记录来源：读取记录、判断可见性、取出可信内容（完整性不过时返回空，并由来源写审计） */
    public interface Source {
        List<AssetRecord> records(String namespace, String name);

        boolean canRead(AssetRecord record);

        Optional<Asset> trusted(AssetRecord record);
    }

    private final Viewer viewer;
    private final Source source;
    private final Set<String> failures = new LinkedHashSet<>();

    public ScopedResolver(Viewer viewer, Source source) {
        this.viewer = viewer;
        this.source = source;
    }

    public Viewer viewer() {
        return viewer;
    }

    @Override
    public List<VersionInfo> listVersions(String namespace, String name) {
        return source.records(namespace, name).stream()
                .filter(source::canRead)
                .map(r -> new VersionInfo(r.version(), r.status()))
                .sorted((a, b) -> Versions.compare(b.version(), a.version()))
                .toList();
    }

    @Override
    public Optional<Asset> get(String namespace, String name, String version) {
        Optional<AssetRecord> record = source.records(namespace, name).stream()
                .filter(r -> r.version().equals(version) && source.canRead(r))
                .findFirst();
        if (record.isEmpty()) return Optional.empty();
        Optional<Asset> asset = source.trusted(record.get());
        if (asset.isEmpty()) failures.add(namespace + "/" + name + "@" + version);
        return asset;
    }

    public List<String> integrityFailures() {
        return List.copyOf(failures);
    }
}

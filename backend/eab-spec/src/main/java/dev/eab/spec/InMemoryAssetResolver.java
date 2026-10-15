package dev.eab.spec;

import dev.eab.spec.model.Asset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 内存实现，用于测试、命令行和静态检查 */
public final class InMemoryAssetResolver implements AssetResolver {

    public record Entry(Asset asset, Status status) {}

    private final Map<String, Map<String, Entry>> entries = new LinkedHashMap<>();

    public InMemoryAssetResolver(List<Entry> initial) {
        initial.forEach(this::add);
    }

    public static InMemoryAssetResolver published(Asset... assets) {
        List<Entry> list = new ArrayList<>();
        for (Asset asset : assets) list.add(new Entry(asset, Status.published));
        return new InMemoryAssetResolver(list);
    }

    public void add(Entry entry) {
        var metadata = entry.asset().metadata();
        entries.computeIfAbsent(AssetRef.key(metadata.namespace(), metadata.name()), k -> new LinkedHashMap<>())
                .put(metadata.version(), entry);
    }

    @Override
    public List<VersionInfo> listVersions(String namespace, String name) {
        Map<String, Entry> versions = entries.get(AssetRef.key(namespace, name));
        if (versions == null) return List.of();
        return versions.values().stream()
                .map(e -> new VersionInfo(e.asset().metadata().version(), e.status()))
                .sorted((a, b) -> Versions.compare(b.version(), a.version()))
                .toList();
    }

    @Override
    public Optional<Asset> get(String namespace, String name, String version) {
        return Optional.ofNullable(entries.get(AssetRef.key(namespace, name)))
                .map(v -> v.get(version))
                .map(Entry::asset);
    }

    public List<Entry> all() {
        return entries.values().stream().flatMap(v -> v.values().stream()).toList();
    }
}

package dev.eab.platform.registry;

import dev.eab.spec.AssetLoader;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把资产目录下指定子目录的 YAML 发布进注册中心（同版本同内容时跳过）。
 * 分两轮：先全部写成草稿，再逐个发布。发布门禁要解析资产之间的引用，一个文件引用的资产可能排在它后面，
 * 两轮可以避免顺序依赖。平台启动时灌入 platform、vendor 和各租户的连接（连接是环境配置，连接中心和向导都要用）；
 * tenants 下的其他资产是演示完成态快照，按需加载。
 */
public final class AssetSeeder {

    private AssetSeeder() {}

    public record Failure(String file, String message) {}

    public record Report(List<String> published, List<Failure> failed) {}

    /** 资产按所在层，以本层身份发布：平台规则由平台发布，标品由厂商发布，租户资产由该租户发布 */
    static Viewer publisherOf(Asset asset) {
        return switch (asset.layer()) {
            case platform -> Viewer.PLATFORM;
            case vendor -> Viewer.VENDOR;
            case tenant ->
                Viewer.tenant(
                        dev.eab.spec.Layers.tenantIdOfNamespace(asset.metadata().namespace())
                                .orElseThrow());
        };
    }

    private record Staged(String file, Asset asset) {}

    /** 启动时入库的子目录：platform、vendor，以及 tenants/<租户>/connections 和 tenants/<租户>/remote（登记的外部 Agent） */
    public static List<String> startupDirectories(Path assetsRoot) {
        List<String> dirs = new ArrayList<>(List.of("platform", "vendor"));
        Path tenants = assetsRoot.resolve("tenants");
        if (!Files.isDirectory(tenants)) return dirs;
        try (var stream = Files.list(tenants)) {
            stream.filter(Files::isDirectory)
                    .map(t -> t.getFileName().toString())
                    .sorted()
                    .forEach(t -> {
                        for (String sub : List.of("connections", "remote")) {
                            if (Files.isDirectory(tenants.resolve(t).resolve(sub)))
                                dirs.add("tenants/" + t + "/" + sub);
                        }
                    });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("读取租户资产目录失败", e);
        }
        return dirs;
    }

    public static Report seed(RegistryService registry, Path assetsRoot, List<String> subdirectories) {
        List<String> published = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        List<Staged> staged = new ArrayList<>();
        for (String sub : subdirectories) {
            Path dir = assetsRoot.resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            var loaded = AssetLoader.loadDirectory(dir);
            loaded.issues()
                    .forEach(i -> failed.add(
                            new Failure(sub + "/" + i.file(), i.issue().format(null))));
            for (var item : loaded.assets()) {
                var result = registry.stageAsset(publisherOf(item.asset()), item.asset(), item.text());
                if (result.ok()) staged.add(new Staged(sub + "/" + item.file(), item.asset()));
                else failed.add(new Failure(sub + "/" + item.file(), describe(result.error())));
            }
        }
        for (Staged s : staged) {
            var result = registry.publishAsset(publisherOf(s.asset()), s.asset(), "");
            if (result.ok()) published.add(s.asset().versionedRef());
            else failed.add(new Failure(s.file(), describe(result.error())));
        }
        return new Report(published, failed);
    }

    private static String describe(RegistryResult.RegistryError error) {
        if (error.issues() == null || error.issues().isEmpty()) return error.message();
        return error.message() + "："
                + error.issues().stream()
                        .map(i -> (i.path() + " " + i.message()).trim())
                        .collect(Collectors.joining("；"));
    }
}

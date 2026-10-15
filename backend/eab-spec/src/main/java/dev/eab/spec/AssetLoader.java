package dev.eab.spec;

import dev.eab.spec.AssetParser.AssetIssue;
import dev.eab.spec.AssetParser.ParseResult;
import dev.eab.spec.model.Asset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 从目录递归读取全部 .yaml/.yml 资产文件，按路径排序保证结果稳定 */
public final class AssetLoader {

    private AssetLoader() {}

    /**
     * @param file 相对于加载根目录的路径，统一用 / 分隔
     * @param text YAML 原文
     */
    public record LoadedAsset(String file, Asset asset, String text) {}

    public record LoadIssue(String file, AssetIssue issue) {}

    public record LoadResult(List<LoadedAsset> assets, List<LoadIssue> issues) {}

    public static LoadResult loadDirectory(Path root) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("(?i).*\\.ya?ml$"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取资产目录 " + root, e);
        }
        List<LoadedAsset> assets = new ArrayList<>();
        List<LoadIssue> issues = new ArrayList<>();
        for (Path path : files) {
            String file = root.relativize(path).toString().replace('\\', '/');
            String text;
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("无法读取资产文件 " + path, e);
            }
            ParseResult parsed = AssetParser.parseYaml(text);
            if (parsed.ok()) assets.add(new LoadedAsset(file, parsed.asset(), text));
            else parsed.issues().forEach(issue -> issues.add(new LoadIssue(file, issue)));
        }
        return new LoadResult(assets, issues);
    }
}

package dev.eab.spec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.AssetLoader.LoadResult;
import dev.eab.spec.InMemoryAssetResolver.Entry;
import dev.eab.spec.testing.RepoPaths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 仓库 assets/ 下的全部资产在 Java 侧解析通过，静态检查没有问题（与 TS 版 pnpm assets:validate 一致） */
class RepoAssetsTest {

    @Test
    @DisplayName("assets/ 下全部资产通过 schema 校验和静态检查")
    void repoAssetsAreValid() {
        LoadResult loaded = AssetLoader.loadDirectory(RepoPaths.assets());
        assertThat(loaded.issues()).isEmpty();
        assertThat(loaded.assets()).hasSizeGreaterThanOrEqualTo(25);

        var resolver = new InMemoryAssetResolver(loaded.assets().stream()
                .map(a -> new Entry(a.asset(), AssetResolver.Status.published))
                .toList());
        List<String> problems = loaded.assets().stream()
                .flatMap(a ->
                        Lint.lint(a.asset(), resolver).stream().map(i -> a.file() + " " + i.path() + " " + i.message()))
                .toList();
        assertThat(problems).isEmpty();
    }
}

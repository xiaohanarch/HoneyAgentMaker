package dev.eab.spec.testing;

import java.nio.file.Files;
import java.nio.file.Path;

/** 测试里定位仓库根目录：从当前目录往上找 pnpm-workspace.yaml */
public final class RepoPaths {

    private RepoPaths() {}

    public static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pnpm-workspace.yaml"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到仓库根目录（pnpm-workspace.yaml）");
    }

    public static Path assets() {
        return root().resolve("assets");
    }
}

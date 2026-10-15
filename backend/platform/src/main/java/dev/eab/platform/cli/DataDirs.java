package dev.eab.platform.cli;

import dev.eab.platform.PlatformProperties;
import java.nio.file.Path;

/** 命令行工具的数据目录：EAB_DATA_DIR，未设置时为仓库根目录下的 data，与服务的默认值一致 */
final class DataDirs {

    private DataDirs() {}

    static Path resolve() {
        String env = System.getenv("EAB_DATA_DIR");
        return env == null || env.isBlank()
                ? PlatformProperties.repoRoot().resolve("data")
                : Path.of(env).toAbsolutePath();
    }
}

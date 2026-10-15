package dev.eab.erp;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DemoERP 配置，绑定 application.yml 里的 eab.*，取值来自与 TS 版同名的环境变量。
 *
 * @param dataDir 数据目录；为空时用仓库根目录下的 data/java（迁移期间与 TS 版的数据分开存放）
 * @param dbPath 数据库路径；为空时为数据目录下的 erp.db，":memory:" 用于测试
 * @param platformUrl 平台地址，用于取 JWKS、核实委托、推送事件
 * @param internalSecret 与平台共享的内部调用密钥
 * @param seedData 启动时数据库为空则生成演示数据
 */
@ConfigurationProperties(prefix = "eab")
public record ErpProperties(
        String dataDir, String dbPath, String platformUrl, String internalSecret, boolean seedData) {

    /** 从当前目录往上找 pnpm-workspace.yaml，找不到时用当前目录 */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("pnpm-workspace.yaml"))) return p;
        }
        return dir;
    }

    public Path resolvedDataDir() {
        return blank(dataDir)
                ? repoRoot().resolve("data").resolve("java")
                : Path.of(dataDir).toAbsolutePath();
    }

    public String resolvedDbPath() {
        return blank(dbPath) ? resolvedDataDir().resolve("erp.db").toString() : dbPath;
    }

    public String resolvedPlatformUrl() {
        return blank(platformUrl) ? "http://127.0.0.1:4000" : platformUrl;
    }

    static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

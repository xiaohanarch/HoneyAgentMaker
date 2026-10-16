package dev.eab.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台配置，绑定 application.yml 里的 eab.*，取值来自与 TS 版同名的环境变量。
 *
 * @param dataDir 数据目录；为空时用仓库根目录下的 data
 * @param assetsDir 资产目录；为空时用仓库根目录下的 assets
 * @param dbPath 数据库路径；为空时为数据目录下的 platform.db，":memory:" 用于测试
 * @param ephemeralKeys 使用临时密钥（测试用），不读写数据目录里的密钥文件
 * @param seedAssets 启动时把平台规则和标品资产签名入库
 * @param demoMode 演示模式开放选人登录等接口，只允许监听本机回环地址
 * @param demoErpUrl DemoERP 的地址，即连接别名 DEMO_ERP 在本环境的绑定
 * @param scriptsDir 脚本化模型的剧本目录；为空时用仓库根目录下的 demo/scripts
 * @param otlpEndpoint 可选的 OTLP/HTTP 采集端，运行结束时导出追踪
 * @param connections 其他连接别名的绑定，例如外部 MCP Server 的地址；只在部署配置里出现，不进资产
 */
@ConfigurationProperties(prefix = "eab")
public record PlatformProperties(
        String dataDir,
        String assetsDir,
        String dbPath,
        boolean ephemeralKeys,
        boolean seedAssets,
        boolean demoMode,
        String internalSecret,
        String demoErpUrl,
        String scriptsDir,
        String otlpEndpoint,
        java.util.Map<String, String> connections) {

    /** 从当前目录往上找 pnpm-workspace.yaml，找不到时用当前目录 */
    public static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("pnpm-workspace.yaml"))) return p;
        }
        return dir;
    }

    public Path resolvedDataDir() {
        return blank(dataDir) ? repoRoot().resolve("data") : Path.of(dataDir).toAbsolutePath();
    }

    public Path resolvedAssetsDir() {
        return blank(assetsDir)
                ? repoRoot().resolve("assets")
                : Path.of(assetsDir).toAbsolutePath();
    }

    public Path resolvedScriptsDir() {
        return blank(scriptsDir)
                ? repoRoot().resolve("demo").resolve("scripts")
                : Path.of(scriptsDir).toAbsolutePath();
    }

    public java.util.Map<String, String> resolvedConnections() {
        return connections == null ? java.util.Map.of() : connections;
    }

    public String resolvedDbPath() {
        return blank(dbPath) ? resolvedDataDir().resolve("platform.db").toString() : dbPath;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

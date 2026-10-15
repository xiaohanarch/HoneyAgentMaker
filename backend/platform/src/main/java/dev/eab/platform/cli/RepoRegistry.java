package dev.eab.platform.cli;

import dev.eab.platform.PlatformProperties;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.identity.IdentityKeys;
import dev.eab.platform.registry.AssetSeeder;
import dev.eab.platform.registry.Ed25519Signer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 用内存数据库和临时密钥建一个注册中心，把仓库里的全部资产按本层身份签名发布。
 * 命令行和集成测试共用，走的是和平台启动时相同的发布路径。
 */
public final class RepoRegistry {

    private RepoRegistry() {}

    public record Loaded(PlatformServices services, AssetSeeder.Report seed) {}

    /** 资产目录下要加载的子目录：平台、标品，以及 tenants/ 下的每个租户快照 */
    public static List<String> subdirectories(Path root) {
        List<String> subs = new ArrayList<>(List.of("platform", "vendor"));
        Path tenants = root.resolve("tenants");
        if (!Files.isDirectory(tenants)) return subs;
        try (Stream<Path> dirs = Files.list(tenants)) {
            dirs.filter(Files::isDirectory).sorted().forEach(d -> subs.add("tenants/" + d.getFileName()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return subs;
    }

    public static Loaded load(Path assetsRoot) {
        PlatformServices services = PlatformServices.create(new PlatformServices.Options(
                ":memory:", Ed25519Signer.ephemeral(), IdentityKeys.ephemeral(), null, null));
        return new Loaded(services, AssetSeeder.seed(services.registry(), assetsRoot, subdirectories(assetsRoot)));
    }

    public static Loaded load() {
        return load(PlatformProperties.repoRoot().resolve("assets"));
    }
}

package dev.eab.platform.identity;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.IntegrationClient;
import dev.eab.spec.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;

/**
 * 机机集成账号（客户端凭证）。密钥在首次启动时随机生成，存放在数据目录的 keys/clients.json，不进仓库。
 * 集成账号没有人的身份，只能换到 Open API 的令牌（主提示词 7.2 节）。
 */
public final class ClientRegistry {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> secrets;

    public ClientRegistry(Map<String, String> secrets) {
        this.secrets = Map.copyOf(secrets);
    }

    private static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, String> generate() {
        Map<String, String> generated = new LinkedHashMap<>();
        for (IntegrationClient c : DemoDirectory.INTEGRATION_CLIENTS) generated.put(c.id(), newSecret());
        return generated;
    }

    public static ClientRegistry ephemeral() {
        return new ClientRegistry(generate());
    }

    public static ClientRegistry fromDirectory(Path dir) {
        Path path = dir.resolve("clients.json");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(dir);
                Files.writeString(path, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(generate()) + "\n",
                        StandardCharsets.UTF_8);
            }
            return new ClientRegistry(Json.MAPPER.readValue(Files.readString(path, StandardCharsets.UTF_8),
                    new TypeReference<Map<String, String>>() {}));
        } catch (IOException e) {
            throw new UncheckedIOException("无法读写集成账号密钥文件 " + path, e);
        }
    }

    /** 演示控制台和测试取密钥用；生产环境不应提供 */
    public Optional<String> secretOf(String clientId) {
        return Optional.ofNullable(secrets.get(clientId));
    }

    /** 常量时间比较密钥 */
    public Optional<IntegrationClient> authenticate(String clientId, String secret) {
        Optional<IntegrationClient> client = DemoDirectory.INTEGRATION_CLIENTS.stream()
                .filter(c -> c.id().equals(clientId))
                .findFirst();
        String expected = secrets.get(clientId);
        if (client.isEmpty() || expected == null || secret == null) return Optional.empty();
        boolean match = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
        return match ? client : Optional.empty();
    }
}

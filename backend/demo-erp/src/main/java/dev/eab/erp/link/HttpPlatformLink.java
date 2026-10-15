package dev.eab.erp.link;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import dev.eab.erp.ErpJson;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 经 HTTP 连接平台：远程 JWKS、委托状态接口（带内部调用密钥）、事件 webhook（HMAC 签名） */
public final class HttpPlatformLink implements PlatformLink {

    private static final Logger LOG = LoggerFactory.getLogger(HttpPlatformLink.class);

    private record Cached(boolean active, long at) {}

    private final String platformUrl;
    private final String internalSecret;
    /** 委托状态的缓存时间：撤销在缓存过期后对已签发的令牌生效，新的令牌交换立即失败 */
    private final long delegationCacheMillis;

    private final TokenVerifier verifier;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public HttpPlatformLink(String platformUrl, String internalSecret, long delegationCacheMillis) {
        this.platformUrl = platformUrl;
        this.internalSecret = internalSecret;
        this.delegationCacheMillis = delegationCacheMillis;
        try {
            this.verifier = new TokenVerifier(JWKSourceBuilder.<SecurityContext>create(URI.create(platformUrl)
                            .resolve("/identity/v1/jwks.json")
                            .toURL())
                    .build());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("平台地址不合法：" + platformUrl, e);
        }
    }

    @Override
    public Map<String, Object> verify(String token, String audience) {
        return verifier.verify(token, audience);
    }

    @Override
    public boolean delegationActive(String id) {
        Cached hit = cache.get(id);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < delegationCacheMillis) return hit.active();
        boolean active;
        try {
            var request = HttpRequest.newBuilder(URI.create(platformUrl)
                            .resolve("/identity/v1/delegations/" + URLEncoder.encode(id, StandardCharsets.UTF_8)
                                    + "/status"))
                    .header("x-eab-internal-token", internalSecret)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            active = response.statusCode() == 200
                    && ErpJson.MAPPER
                            .readTree(response.body())
                            .path("data")
                            .path("active")
                            .asBoolean(false);
        } catch (Exception e) {
            // 核实不了就当作无效
            active = false;
        }
        cache.put(id, new Cached(active, now));
        return active;
    }

    static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256="
                    + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** 签名覆盖时间戳和请求体，平台拒绝 5 分钟以外的事件；事件编号用于去重，防止重放。推送在后台进行，不阻塞业务请求 */
    @Override
    public void publish(String type, String tenantId, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("tenantId", tenantId);
        event.put("payload", payload);
        event.put("id", UUID.randomUUID().toString());
        String body = ErpJson.MAPPER.writeValueAsString(event);
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        var request = HttpRequest.newBuilder(URI.create(platformUrl).resolve("/events/v1/ingest"))
                .header("content-type", "application/json")
                .header("x-eab-timestamp", timestamp)
                .header("x-eab-signature", sign(internalSecret, timestamp, body))
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> {
            LOG.warn("事件推送到平台失败：{}", error.getMessage());
            return null;
        });
    }
}

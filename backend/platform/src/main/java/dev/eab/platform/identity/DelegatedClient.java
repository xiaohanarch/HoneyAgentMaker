package dev.eab.platform.identity;

import dev.eab.platform.identity.TokenService.TokenException;
import dev.eab.spec.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * 平台调用 DemoERP BO API 的唯一通道：先以“用户 + Agent”双重身份做令牌交换，再带委托令牌调用。
 * 身份透传失败即拒绝（主提示词 8.4 节）：令牌交换失败、委托缺失、配置不全时抛 DelegationException，
 * 这里没有任何使用集成账号或其他技术身份的代码路径。
 */
public final class DelegatedClient {

    public static final class DelegationException extends RuntimeException {
        private final String code;

        public DelegationException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 调用主体：用户令牌（代用户操作），或者委托编号（受托自主运行），二者只能有一个 */
    public record Subject(String userToken, String delegationId) {
        public static Subject user(String token) {
            return new Subject(token, null);
        }

        public static Subject delegation(String id) {
            return new Subject(null, id);
        }
    }

    /**
     * @param agentToken 平台运行时为本次运行签发的 Agent 令牌
     */
    public record BoCall(
            Subject subject,
            String agentToken,
            String operation,
            String method,
            String path,
            Map<String, String> query,
            Object body,
            String idempotencyKey) {}

    public record BoResponse(int status, JsonNode body) {}

    private final IdentityService identity;
    /** DemoERP 的地址，P3 起由连接别名 DEMO_ERP 按环境绑定 */
    private final String erpUrl;
    private final HttpClient http;

    public DelegatedClient(IdentityService identity, String erpUrl, HttpClient http) {
        this.identity = identity;
        this.erpUrl = erpUrl;
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() : http;
    }

    public BoResponse callBoApi(BoCall call) {
        if (erpUrl == null || erpUrl.isBlank()) {
            throw new DelegationException("CONNECTION_MISSING", "没有配置 DemoERP 的连接，拒绝调用");
        }
        String accessToken;
        try {
            var request = call.subject().userToken() != null
                    ? new IdentityService.ExchangeRequest(call.subject().userToken(), IdentityService.TokenType.JWT,
                            call.agentToken(), TokenService.Audience.BO, List.of(call.operation()))
                    : new IdentityService.ExchangeRequest("delegation:" + call.subject().delegationId(),
                            IdentityService.TokenType.DELEGATION, call.agentToken(), TokenService.Audience.BO,
                            List.of(call.operation()));
            accessToken = identity.exchange(request).accessToken();
        } catch (RuntimeException e) {
            String code = e instanceof TokenException t ? t.code() : "EXCHANGE_FAILED";
            throw new DelegationException(code, "身份透传失败，已拒绝调用 " + call.operation() + "：" + e.getMessage());
        }

        // 只允许 BO API 路径，并且必须落在配置的 ERP 地址上，委托令牌不会被发往别处
        String path = call.path();
        if (!path.startsWith("/bo/v1/") || path.contains("//") || path.contains("..")) {
            throw new DelegationException("INVALID_PATH", "不允许的 BO API 路径：" + path);
        }
        URI base = URI.create(erpUrl);
        String query = call.query() == null || call.query().isEmpty()
                ? ""
                : "?" + call.query().entrySet().stream()
                        .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
        URI uri = base.resolve(path + query);
        if (!uri.getScheme().equals(base.getScheme()) || !uri.getAuthority().equals(base.getAuthority())) {
            throw new DelegationException("INVALID_PATH", "BO API 地址与连接配置不一致");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "Bearer " + accessToken);
        if (call.idempotencyKey() != null) builder.header("idempotency-key", call.idempotencyKey());
        if (call.body() != null) {
            builder.header("content-type", "application/json")
                    .method(call.method(), HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(call.body())));
        } else {
            builder.method(call.method(), HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new BoResponse(response.statusCode(), Json.MAPPER.readTree(response.body()));
        } catch (IOException e) {
            throw new DelegationException("BO_UNREACHABLE", "调用 BO API 失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DelegationException("BO_UNREACHABLE", "调用 BO API 被中断");
        }
    }
}

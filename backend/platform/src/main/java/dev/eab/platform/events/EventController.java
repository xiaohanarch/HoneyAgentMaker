package dev.eab.platform.events;

import dev.eab.directory.DemoDirectory;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.spec.Json;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * 接收 DemoERP 推送的事件（webhook，HMAC 签名）。
 * bo.access 写入审计；业务事件写入事件表并在进程内广播，P4 起触发 Agent。
 */
@Hidden
@RestController
public class EventController {

    public static final String SIGNATURE_HEADER = "x-eab-signature";
    public static final String TIMESTAMP_HEADER = "x-eab-timestamp";
    /** 允许的时钟偏差，超过的事件按重放拒绝 */
    private static final long MAX_SKEW_SECONDS = 300;

    private static final long SEEN_TTL_MILLIS = 2 * MAX_SKEW_SECONDS * 1000;

    private final PlatformServices services;
    /** 最近见过的事件编号，带过期时间；同一编号再次出现按重放拒绝 */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public EventController(PlatformServices services) {
        this.services = services;
    }

    /** 事件推送的签名：sha256=&lt;HMAC-SHA256(内部调用密钥, "时间戳.原始请求体")&gt; */
    public static String signPayload(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256="
                    + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private static boolean verifySignature(String secret, String timestamp, String body, String header) {
        if (header == null) return false;
        return MessageDigest.isEqual(
                signPayload(secret, timestamp, body).getBytes(StandardCharsets.UTF_8),
                header.getBytes(StandardCharsets.UTF_8));
    }

    private static final List<String> ACCESS_FIELDS = List.of(
            "at",
            "operation",
            "userId",
            "agent",
            "mode",
            "outcome",
            "code",
            "reason",
            "dataScope",
            "target",
            "resultSummary");

    /** 签名基于原始请求体，这里按字符串接收 */
    @PostMapping("/events/v1/ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Object>> ingest(
            HttpServletRequest request, @RequestBody(required = false) String raw) {
        String body = raw == null ? "" : raw;
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        long skew;
        try {
            skew = Math.abs(services.clock().millis() / 1000 - Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            skew = Long.MAX_VALUE;
        }
        if (timestamp == null || skew > MAX_SKEW_SECONDS) {
            throw new ApiException(401, "STALE_EVENT", "事件时间戳缺失或超出允许范围");
        }
        if (!verifySignature(services.internalSecret(), timestamp, body, request.getHeader(SIGNATURE_HEADER))) {
            throw new ApiException(401, "BAD_SIGNATURE", "事件签名校验失败");
        }
        JsonNode event;
        try {
            event = Json.MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ApiException(400, "BAD_EVENT", "事件格式不合法");
        }
        String id = text(event, "id");
        String type = text(event, "type");
        String tenantId = text(event, "tenantId");
        JsonNode payload = event.get("payload");
        boolean access = "bo.access".equals(type);
        boolean valid = id != null
                && !id.isEmpty()
                && id.length() <= 64
                && tenantId != null
                && payload != null
                && payload.isObject()
                && (access
                        ? ACCESS_FIELDS.stream().allMatch(f -> payload.path(f).isString())
                                && List.of("allow", "deny")
                                        .contains(payload.get("outcome").asString())
                        : EventStore.BUSINESS_EVENT_TYPES.contains(type));
        if (!valid) throw new ApiException(400, "BAD_EVENT", "事件格式不合法");
        if (DemoDirectory.findTenant(tenantId).isEmpty()) throw new ApiException(400, "UNKNOWN_TENANT", "事件的租户不存在");

        long now = services.clock().millis();
        seen.entrySet().removeIf(e -> now - e.getValue() > SEEN_TTL_MILLIS);
        if (seen.putIfAbsent(id, now) != null) throw new ApiException(409, "REPLAYED_EVENT", "事件已处理过");

        if (access) {
            String delegationId = text(payload, "delegationId");
            services.audit()
                    .append(NewAuditEvent.of(
                                    text(payload, "userId"),
                                    "bo.access",
                                    text(payload, "target"),
                                    text(payload, "outcome"),
                                    "鉴权点 4 · BO API：" + text(payload, "reason"))
                            .tenantId(tenantId)
                            .userId(text(payload, "userId"))
                            .agent(text(payload, "agent"))
                            .mode(text(payload, "mode"))
                            .checkpoint("bo-api")
                            .tool(text(payload, "operation"))
                            .dataScope(text(payload, "dataScope"))
                            .ruleId(text(payload, "code"))
                            .resultSummary(text(payload, "resultSummary"))
                            .detail(delegationId == null ? null : Map.of("delegationId", delegationId)));
            return ApiResponse.success(Map.of("accepted", true));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = Json.MAPPER.treeToValue(payload, Map.class);
        var stored = services.events().append("demo-erp", type, tenantId, map);
        return ApiResponse.success(Map.of("accepted", true, "id", stored.id()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}

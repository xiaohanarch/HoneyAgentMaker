package dev.eab.platform.assist;

import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.events.EventStore;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.http.SseRuns;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一入口的接口：提问（路由后交给 Agent，或返回导航卡片、澄清选项、能力缺口）、提交扩展需求、通知区，
 * 以及演示控制台触发业务事件。身份只来自 Bearer 用户令牌。
 */
@RestController
@Tag(name = "assist")
public class AssistController {

    static final int MAX_MESSAGE = 2000;
    static final long SSE_TIMEOUT_MILLIS = 300_000;

    private final AssistService assist;
    private final PlatformServices services;
    private final RuntimeService runtime;
    private final NotificationStore notifications;
    private final boolean demoMode;
    private final RateLimiter limiter;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public AssistController(
            AssistService assist,
            PlatformServices services,
            RuntimeService runtime,
            NotificationStore notifications,
            DemoMode demoMode) {
        this.assist = assist;
        this.services = services;
        this.runtime = runtime;
        this.notifications = notifications;
        this.demoMode = demoMode.enabled();
        this.limiter = new RateLimiter(30, 60_000, services.clock());
    }

    private Principal tenantUser(HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)) {
            throw new ApiException(403, "FORBIDDEN", "统一入口只对租户用户开放");
        }
        return principal;
    }

    public record AssistBody(String message, String sessionId, String target) {}

    @PostMapping("/assist")
    @Operation(summary = "统一入口提问：路由到岗位助手与 Agent，SSE 推送路由说明与运行事件")
    public Object ask(HttpServletRequest request, @RequestBody AssistBody body) {
        Principal principal = tenantUser(request);
        if (body == null
                || body.message() == null
                || body.message().isBlank()
                || body.message().length() > MAX_MESSAGE) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：message 不能为空，最长 " + MAX_MESSAGE + " 字");
        }
        if (body.target() != null && !body.target().matches("^[a-z][a-z0-9.-]*/[a-z][a-z0-9:-]*$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：target 写作 namespace/name");
        }
        if (body.sessionId() != null && !body.sessionId().matches("^[A-Za-z0-9-]{1,80}$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：sessionId 只能包含字母、数字和连字符");
        }
        limiter.check("assist:" + principal.user().id());
        var ask = new AssistService.AssistRequest(
                principal.user(), principal.token(), body.message().strip(), body.sessionId(), body.target());
        return respond(request, sink -> assist.assist(ask, sink));
    }

    /** 同步返回时的结果 */
    public record AssistView(
            String kind,
            String runId,
            String traceId,
            String sessionId,
            Map<String, Object> payload,
            List<RunEvent> events) {}

    private static Object respond(
            HttpServletRequest request, Function<RunEvents.Sink, AssistService.AssistOutcome> work) {
        String accept = request.getHeader("Accept");
        if (accept != null
                && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                && !accept.contains("text/event-stream")) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = work.apply(events::add);
            return ApiResponse.success(new AssistView(
                    outcome.kind(),
                    outcome.runId(),
                    outcome.traceId(),
                    outcome.sessionId(),
                    outcome.payload(),
                    events));
        }
        return SseRuns.start(SSE_TIMEOUT_MILLIS, "ASSIST_FAILED", work::apply);
    }

    // ── 能力缺口：提交扩展需求（需求的评审与交付在 P9） ──

    public record DevRequestBody(String text, String assistant) {}

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @PostMapping("/dev-requests")
    @Operation(summary = "提交扩展需求：在本租户的 requests 命名空间里创建一份 DevRequest 草稿")
    public ApiResponse<Map<String, Object>> submit(HttpServletRequest request, @RequestBody DevRequestBody body) {
        Principal principal = tenantUser(request);
        if (body == null
                || body.text() == null
                || body.text().isBlank()
                || body.text().length() > MAX_MESSAGE) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：text 不能为空，最长 " + MAX_MESSAGE + " 字");
        }
        if (body.assistant() != null
                && !body.assistant().isBlank()
                && !body.assistant().matches("^[a-z][a-z0-9.-]*/[a-z][a-z0-9-]*$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：assistant 写作 namespace/name");
        }
        limiter.check("dev-request:" + principal.user().id());
        String tenant = principal.user().tenantId();
        String name = "req-" + STAMP.format(services.clock().instant()) + "-"
                + UUID.randomUUID().toString().substring(0, 4);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", "tenant." + tenant + ".requests");
        metadata.put("version", "0.1.0");
        metadata.put(
                "displayName",
                body.text().strip().length() > 30
                        ? body.text().strip().substring(0, 30) + "…"
                        : body.text().strip());
        metadata.put("description", "统一入口提交的扩展需求");
        metadata.put("owner", principal.user().id());
        Map<String, Object> context = new LinkedHashMap<>();
        if (body.assistant() != null && !body.assistant().isBlank()) context.put("assistant", body.assistant());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("requester", principal.user().id());
        spec.put("originalText", body.text().strip());
        spec.put("context", context);
        spec.put("status", "submitted");
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "DevRequest");
        asset.put("metadata", metadata);
        asset.put("spec", spec);
        var result =
                services.registry().put(Viewer.tenant(tenant), Json.MAPPER.writeValueAsString(asset), "统一入口提交的扩展需求");
        if (!result.ok()) throw new ApiException(400, "INVALID_REQUEST", "扩展需求没有保存：" + result);
        return ApiResponse.success(Map.of(
                "ref", "tenant." + tenant + ".requests/" + name,
                "status", "submitted",
                "message", "扩展需求已提交，开发者会在 Studio 的需求列表里看到它"));
    }

    // ── 通知区 ──

    @GetMapping("/notifications")
    @Operation(summary = "本人的通知：业务事件触发的受托运行结果")
    public ApiResponse<List<NotificationStore.Notification>> notifications(HttpServletRequest request) {
        Principal principal = tenantUser(request);
        return ApiResponse.success(
                notifications.list(principal.user().tenantId(), principal.user().id(), 30));
    }

    @PostMapping("/notifications/{id}/read")
    @Operation(summary = "标记通知已读")
    public ApiResponse<Map<String, Object>> read(HttpServletRequest request, @PathVariable long id) {
        Principal principal = tenantUser(request);
        boolean updated = notifications.markRead(
                principal.user().tenantId(), principal.user().id(), id);
        if (!updated) throw new ApiException(404, "NOT_FOUND", "通知 " + id + " 不存在");
        return ApiResponse.success(Map.of("id", id, "read", true));
    }

    // ── 演示控制台：触发业务事件（DemoERP 推送给平台，再由事件触发器运行 Agent） ──

    public record DemoEventBody(String type, Map<String, Object> payload) {}

    /** 演示控制台可以覆盖的载荷字段；事件内容会进入受托运行的话术，只接受短字符串和数字 */
    static final Map<String, Set<String>> EVENT_FIELDS = Map.of(
            "delivery.overdue", Set.of("poId", "supplierId", "supplierName", "overdueDays", "materialId"),
            "inventory.below_safety_stock",
                    Set.of("materialId", "materialName", "warehouseId", "quantity", "safetyStock"),
            "transaction.anomaly_created", Set.of("anomalyId", "severity", "amount", "currency"),
            "period.close_check_failed", Set.of("product", "companyId", "failed"));

    static final int MAX_EVENT_VALUE = 64;

    static Map<String, Object> eventPayload(String type, Map<String, Object> payload) {
        Set<String> allowed = EVENT_FIELDS.getOrDefault(type, Set.of());
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            boolean ok = allowed.contains(key)
                    && (value instanceof Number || (value instanceof String s && s.length() <= MAX_EVENT_VALUE));
            if (!ok) {
                throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：payload." + key + " 不是该事件的字段，或取值不是 64 字以内的字符串或数字");
            }
            result.put(key, value);
        });
        return result;
    }

    @PostMapping("/demo/v1/events")
    @Operation(summary = "演示模式：触发一个 DemoERP 业务事件")
    public ApiResponse<Map<String, Object>> trigger(HttpServletRequest request, @RequestBody DemoEventBody body) {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启");
        Principal principal = tenantUser(request);
        if (body == null || body.type() == null || !EventStore.BUSINESS_EVENT_TYPES.contains(body.type())) {
            throw new ApiException(
                    400, "BAD_REQUEST", "请求参数不合法：type 只能是 " + String.join("、", EventStore.BUSINESS_EVENT_TYPES));
        }
        limiter.check("demo-event:" + principal.user().id());
        String erpUrl = runtime.settings().erpUrl();
        Map<String, Object> forward = new LinkedHashMap<>();
        forward.put("type", body.type());
        forward.put("tenantId", principal.user().tenantId());
        if (body.payload() != null) forward.put("payload", eventPayload(body.type(), body.payload()));
        try {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(erpUrl).resolve("/demo/v1/events/trigger"))
                            .timeout(Duration.ofSeconds(5))
                            .header("content-type", "application/json")
                            .header("x-eab-internal-token", services.internalSecret())
                            .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(forward)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 202) {
                throw new ApiException(502, "ERP_ERROR", "DemoERP 拒绝了事件：HTTP " + response.statusCode());
            }
            return ApiResponse.success(Map.of("type", body.type(), "accepted", true));
        } catch (IOException e) {
            throw new ApiException(502, "ERP_UNREACHABLE", "无法连接 DemoERP：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "INTERRUPTED", "请求被中断");
        }
    }
}

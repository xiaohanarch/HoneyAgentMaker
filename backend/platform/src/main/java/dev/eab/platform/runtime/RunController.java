package dev.eab.platform.runtime;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.runtime.RuntimeStores.RunRecord;
import dev.eab.runtime.Journal;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.TraceView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行接口（主提示词 8.1.1 节）：发起运行、确认或拒绝、读取追踪、查看和清除记忆。
 *
 * <p>发起运行和确认默认以 SSE 推送运行事件（step、tool_call、tool_result、confirmation_required、text_delta、final、error）；
 * 请求头 Accept: application/json 时同步返回全部事件和结果，便于脚本和测试。身份只来自 Bearer 用户令牌，
 * BO API 以这个用户的身份做令牌交换。
 */
@RestController
@Tag(name = "runtime")
public class RunController {

    static final Pattern AGENT_REF = Pattern.compile("^[a-z0-9][a-z0-9.-]*/[a-z0-9][a-z0-9-]*$");
    static final int MAX_MESSAGE = 2000;
    static final long SSE_TIMEOUT_MILLIS = 300_000;
    static final int RUNS_PER_MINUTE = 30;

    private final RuntimeService runtime;
    private final Principals principals;
    private final RateLimiter limiter;

    public RunController(RuntimeService runtime, PlatformServices services) {
        this.runtime = runtime;
        this.principals = services.principals();
        this.limiter = new RateLimiter(RUNS_PER_MINUTE, 60_000, services.clock());
    }

    public record RunBody(String agent, String message, String sessionId) {}

    public record ConfirmationBody(Boolean approved) {}

    /** 同步返回时的结果 */
    public record RunView(
            String runId,
            String traceId,
            String sessionId,
            String status,
            String text,
            Map<String, Object> output,
            Journal.Pending pending,
            String error,
            List<RunEvent> events) {}

    private Principal tenantUser(HttpServletRequest request) {
        Principal principal = principals.require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)) {
            throw new ApiException(403, "FORBIDDEN", "只有租户用户可以运行 Agent（以用户身份访问业务数据）");
        }
        return principal;
    }

    private static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE) && !accept.contains("text/event-stream");
    }

    @PostMapping("/runs")
    @Operation(summary = "发起一次 Agent 运行，SSE 推送运行事件")
    public Object start(HttpServletRequest request, @RequestBody RunBody body) {
        Principal principal = tenantUser(request);
        if (body == null || body.agent() == null || !AGENT_REF.matcher(body.agent()).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：agent 写作 namespace/name");
        }
        if (body.message() == null || body.message().isBlank() || body.message().length() > MAX_MESSAGE) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：message 不能为空，最长 " + MAX_MESSAGE + " 字");
        }
        limiter.check("run:" + principal.user().id());
        var start = new RuntimeService.StartRequest(
                principal.user(), principal.token(), body.agent(), body.message().strip(), body.sessionId(), null);
        return respond(request, sink -> runtime.start(start, sink));
    }

    @PostMapping("/runs/{id}/confirmation")
    @Operation(summary = "确认或拒绝挂起的运行，继续以 SSE 推送事件")
    public Object confirm(HttpServletRequest request, @PathVariable String id, @RequestBody ConfirmationBody body) {
        Principal principal = tenantUser(request);
        if (body == null || body.approved() == null) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：approved 必须是 true 或 false");
        }
        RunRecord run = runtime.runs().get(id).filter(r -> r.userId().equals(principal.user().id()))
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "运行 " + id + " 不存在"));
        if (!run.status().equals("suspended")) {
            throw new ApiException(409, "NOT_SUSPENDED", "运行 " + id + " 当前状态为 " + run.status() + "，不在等待确认");
        }
        return respond(request, sink -> runtime.confirm(id, principal.user(), principal.token(), body.approved(), sink));
    }

    private Object respond(HttpServletRequest request, Function<dev.eab.runtime.RunEvents.Sink, RuntimeService.RunOutcome> work) {
        if (wantsJson(request)) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = work.apply(events::add);
            return ApiResponse.success(view(outcome, events));
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        Thread.ofVirtual().start(() -> {
            try {
                work.apply(event -> send(emitter, event));
                emitter.complete();
            } catch (RuntimeException e) {
                send(emitter, RunEvent.of("error", null, "code", "RUN_FAILED", "message", e.getMessage()));
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(Json.MAPPER.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // 浏览器断开后运行照常完成，结果和追踪仍可以通过 GET /runs/{id}、GET /traces/{traceId} 查看
        }
    }

    private static RunView view(RuntimeService.RunOutcome outcome, List<RunEvent> events) {
        var r = outcome.result();
        return new RunView(
                outcome.runId(), outcome.traceId(), outcome.sessionId(), r.status(), r.text(), r.output(), r.pending(),
                r.error(), events);
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "查看运行状态与结果")
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request, @PathVariable String id) {
        Principal principal = principals.require(request);
        RunRecord run = runtime.runs().get(id).filter(r -> canSeeRun(principal, r))
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "运行 " + id + " 不存在"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", run.id());
        data.put("traceId", run.traceId());
        data.put("sessionId", run.sessionId());
        data.put("agent", run.agent());
        data.put("mode", run.mode());
        data.put("status", run.status());
        data.put("pending", Journal.fromJson(run.journal()).pending());
        data.put("result", run.result() == null ? null : Json.MAPPER.readValue(run.result(), Map.class));
        data.put("createdAt", run.createdAt());
        data.put("updatedAt", run.updatedAt());
        return ApiResponse.success(data);
    }

    /** 本人的运行；租户管理员能看本租户全部；平台运维能看全部；厂商看不到任何租户的运行 */
    static boolean canSeeRun(Principal principal, RunRecord run) {
        return switch (principal.viewer()) {
            case Viewer.Platform p -> true;
            case Viewer.Vendor v -> false;
            case Viewer.Tenant t -> t.tenantId().equals(run.tenantId())
                    && (run.userId().equals(principal.user().id()) || principal.user().roles().contains("tenant-admin"));
        };
    }

    @GetMapping("/traces/{traceId}")
    @Operation(summary = "读取追踪，按查看者裁剪（租户视角看不到标品指令与平台规则原文）")
    public ApiResponse<Map<String, Object>> trace(HttpServletRequest request, @PathVariable String traceId) {
        Principal principal = principals.require(request);
        RunRecord run = runtime.runs().byTrace(traceId).filter(r -> canSeeRun(principal, r))
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "追踪 " + traceId + " 不存在"));
        List<SpanRecord> spans = runtime.traces().list(traceId);
        if (!TraceView.canView(principal.viewer(), spans)) {
            throw new ApiException(404, "NOT_FOUND", "追踪 " + traceId + " 不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("traceId", traceId);
        data.put("runId", run.id());
        data.put("agent", run.agent());
        data.put("spans", TraceView.view(spans, principal.viewer(), Visibility::digestOf));
        return ApiResponse.success(data);
    }

    @GetMapping("/memory")
    @Operation(summary = "查看本人的长期记忆")
    public ApiResponse<List<RuntimeStores.MemoryStore.MemoryItem>> memory(
            HttpServletRequest request, @RequestParam(required = false) String agent) {
        Principal principal = tenantUser(request);
        return ApiResponse.success(runtime.memory().list(principal.user().tenantId(), principal.user().id(), agent));
    }

    public record MemoryBody(String agent, String text) {}

    @PostMapping("/memory")
    @Operation(summary = "为某个 Agent 记住一条偏好或确认过的事实")
    public ApiResponse<RuntimeStores.MemoryStore.MemoryItem> remember(HttpServletRequest request, @RequestBody MemoryBody body) {
        Principal principal = tenantUser(request);
        if (body == null || body.agent() == null || !AGENT_REF.matcher(body.agent()).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：agent 写作 namespace/name");
        }
        if (body.text() == null || body.text().isBlank() || body.text().length() > 500) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：text 不能为空，最长 500 字");
        }
        return ApiResponse.success(
                runtime.memory().add(principal.user().tenantId(), principal.user().id(), body.agent(), body.text().strip()));
    }

    @DeleteMapping("/memory")
    @Operation(summary = "清除本人的长期记忆，可按 Agent 清除")
    public ApiResponse<Map<String, Object>> forget(HttpServletRequest request, @RequestParam(required = false) String agent) {
        Principal principal = tenantUser(request);
        int removed = runtime.memory().clear(principal.user().tenantId(), principal.user().id(), agent);
        return ApiResponse.success(Map.of("removed", removed));
    }

    @GetMapping("/runtime/alerts")
    @Operation(summary = "监督层的风险告警（租户管理员看本租户，平台运维看全部）")
    public ApiResponse<List<RuntimeStores.AlertStore.Alert>> alerts(HttpServletRequest request) {
        Principal principal = principals.require(request);
        String tenant = switch (principal.viewer()) {
            case Viewer.Platform p -> null;
            case Viewer.Tenant t when principal.user().roles().contains("tenant-admin") -> t.tenantId();
            default -> throw new ApiException(403, "FORBIDDEN", "只有租户管理员和平台运维可以查看风险告警");
        };
        return ApiResponse.success(runtime.alerts().list(tenant, 100));
    }

    @GetMapping("/channels/im/messages")
    @Operation(summary = "模拟 IM 渠道里本租户的消息")
    public ApiResponse<List<RuntimeStores.ImChannel.ImMessage>> imMessages(HttpServletRequest request) {
        Principal principal = tenantUser(request);
        return ApiResponse.success(new ArrayList<>(runtime.im().list(principal.user().tenantId(), 50)));
    }
}

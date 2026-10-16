package dev.eab.platform.extend;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.http.SseRuns;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService.DraftView;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 扩展标品 Agent 的接口（docs/studio-api.md 的 P6 一节）：只对本租户的开发者和管理员开放 */
@RestController
@Tag(name = "studio-extend")
public class ExtendController {

    static final int MAX_TEXT = 2000;
    static final int MAX_YAML = 200_000;
    static final long SSE_TIMEOUT_MILLIS = 600_000;
    static final List<String> ROLES = List.of("tenant-developer", "tenant-admin");
    static final String NAME = "^[a-z0-9][a-z0-9.-]*$";

    private final PlatformServices services;
    private final ExtendService extend;
    private final ExtensionReleases releases;
    private final boolean demoMode;
    private final RateLimiter limiter;

    public ExtendController(
            PlatformServices services, ExtendService extend, ExtensionReleases releases, DemoMode demoMode) {
        this.services = services;
        this.extend = extend;
        this.releases = releases;
        this.demoMode = demoMode.enabled();
        this.limiter = new RateLimiter(60, 60_000, services.clock());
    }

    static DemoUser developer(PlatformServices services, HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)
                || principal.user().roles().stream().noneMatch(ROLES::contains)) {
            throw new ApiException(403, "FORBIDDEN", "Studio 只对租户开发者和租户管理员开放");
        }
        return principal.user();
    }

    static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (StudioException e) {
            throw new ApiException(e.status(), new ApiError(e.code(), e.getMessage(), e.details(), null));
        }
    }

    static void name(String value, String field) {
        if (value == null || !value.matches(NAME) || value.length() > 120) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：" + field + " 只能包含小写字母、数字、点和连字符");
        }
    }

    // ── 标品 Agent 目录 ──

    @GetMapping("/studio/vendor-agents")
    @Operation(summary = "租户可见的标品 Agent 目录（指令原文不可见）")
    public ApiResponse<List<Map<String, Object>>> vendorAgents(HttpServletRequest request) {
        return ApiResponse.success(
                extend.vendorAgents(developer(services, request).tenantId()));
    }

    @GetMapping("/studio/vendor-agents/{namespace}/{name}")
    @Operation(summary = "单个标品 Agent：裁剪后的资产、租户视角的有效定义、本租户的扩展")
    public ApiResponse<Map<String, Object>> vendorAgent(
            HttpServletRequest request, @PathVariable String namespace, @PathVariable String name) {
        DemoUser user = developer(services, request);
        name(namespace, "namespace");
        name(name, "name");
        return ApiResponse.success(call(() -> extend.vendorAgent(user.tenantId(), namespace, name)));
    }

    @PostMapping("/studio/vendor-agents/{namespace}/{name}/copy")
    @Operation(summary = "复制为租户 Agent：生成只含租户可见部分的 Agent 草稿，不再随标品升级")
    public ApiResponse<ExtendService.CopyResult> copy(
            HttpServletRequest request, @PathVariable String namespace, @PathVariable String name) {
        DemoUser user = developer(services, request);
        name(namespace, "namespace");
        name(name, "name");
        limiter.check("extend:" + user.id());
        return ApiResponse.success(call(() -> extend.copyAsTenant(user, namespace, name)));
    }

    // ── 扩展草稿 ──

    public record TargetBody(String target) {}

    @PostMapping("/studio/extensions")
    @Operation(summary = "为标品 Agent 新建扩展草稿；已有已发布扩展时从它开始编辑")
    public ApiResponse<DraftView> create(HttpServletRequest request, @RequestBody TargetBody body) {
        DemoUser user = developer(services, request);
        if (body == null || body.target() == null || !body.target().matches("^vendor\\.[a-z0-9.-]+/[a-z0-9-]+$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：target 写作标品 Agent 的 namespace/name");
        }
        limiter.check("extend:" + user.id());
        return ApiResponse.success(call(() -> extend.createExtension(user, body.target())));
    }

    public record SectionBody(String section, Map<String, Object> value) {}

    @PostMapping("/studio/drafts/{id}/extension")
    @Operation(summary = "按扩展点类型编辑扩展草稿的一页")
    public ApiResponse<DraftView> apply(
            HttpServletRequest request, @PathVariable String id, @RequestBody SectionBody body) {
        DemoUser user = developer(services, request);
        if (body == null || body.section() == null || body.value() == null) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 section 和 value");
        }
        return ApiResponse.success(call(() -> extend.applySection(user, id, body.section(), body.value())));
    }

    @GetMapping("/studio/drafts/{id}/effective")
    @Operation(summary = "有效定义预览：叠加扩展草稿后组装，标品指令为占位块，每段带来源")
    public ApiResponse<Map<String, Object>> effective(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(services, request);
        return ApiResponse.success(call(() -> extend.effective(user, id)));
    }

    // ── 评测、静态检查、发布 ──

    public record GenerateBody(String testUser) {}

    @PostMapping("/studio/drafts/{id}/extension/evals/generate")
    @Operation(summary = "生成扩展评测集初稿")
    public ApiResponse<DraftView> generate(
            HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) GenerateBody body) {
        DemoUser user = developer(services, request);
        String testUser = body == null || body.testUser() == null ? "wangfang" : body.testUser();
        return ApiResponse.success(call(() -> releases.generateEvals(user, id, testUser)));
    }

    public record YamlBody(String yaml) {}

    @PutMapping("/studio/drafts/{id}/extension/evals")
    @Operation(summary = "替换扩展评测集草稿")
    public ApiResponse<DraftView> putEvals(
            HttpServletRequest request, @PathVariable String id, @RequestBody YamlBody body) {
        DemoUser user = developer(services, request);
        if (body == null
                || body.yaml() == null
                || body.yaml().isBlank()
                || body.yaml().length() > MAX_YAML) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：yaml 不能为空");
        }
        return ApiResponse.success(call(() -> releases.putEvals(user, id, body.yaml())));
    }

    @PostMapping("/studio/drafts/{id}/extension/evals/run")
    @Operation(summary = "运行标品公开评测集和扩展评测集")
    public ApiResponse<ExtensionReleases.ExtensionEvalView> runEvals(
            HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(services, request);
        requireDemo();
        limiter.check("extend:" + user.id());
        return ApiResponse.success(call(() -> releases.runEvals(user, id)));
    }

    @PostMapping("/studio/drafts/{id}/extension/lint")
    @Operation(summary = "扩展的静态检查")
    public ApiResponse<ExtensionReleases.LintReport> lint(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(services, request);
        return ApiResponse.success(call(() -> releases.lint(user, id)));
    }

    public record PublishBody(String changeNote) {}

    @PostMapping("/studio/drafts/{id}/extension/publish")
    @Operation(summary = "发布扩展：强制通过标品公开评测集与扩展评测集，发布后对本租户生效")
    public ApiResponse<ExtensionReleases.PublishResult> publish(
            HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) PublishBody body) {
        DemoUser user = developer(services, request);
        requireDemo();
        String note = body == null ? null : body.changeNote();
        if (note != null && note.length() > MAX_TEXT)
            throw new ApiException(400, "BAD_REQUEST", "变更说明最长 " + MAX_TEXT + " 字");
        return ApiResponse.success(call(() -> releases.publish(user, id, note)));
    }

    // ── 私有环境测试 ──

    private void requireDemo() {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启，私有环境测试不可用");
    }

    public record TestBody(String message, String asUser, String sessionId) {}

    @PostMapping("/studio/drafts/{id}/extension/test")
    @Operation(summary = "私有环境测试扩展：以测试用户的身份运行目标标品 Agent，扩展草稿叠加生效；确认点用 /studio/test-runs/{runId}/confirmation")
    public Object test(HttpServletRequest request, @PathVariable String id, @RequestBody TestBody body) {
        DemoUser user = developer(services, request);
        requireDemo();
        limiter.check("extend:" + user.id());
        if (body == null
                || body.message() == null
                || body.message().isBlank()
                || body.message().length() > MAX_TEXT) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：message 不能为空，最长 " + MAX_TEXT + " 字");
        }
        if (body.sessionId() != null && !body.sessionId().matches("^[A-Za-z0-9-]{1,80}$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：sessionId 只能包含字母、数字和连字符");
        }
        call(() -> ExtendService.parseExtension(extend.extensionDraft(user, id)));
        call(() -> extend.testUser(user, body.asUser()));
        return respond(
                request,
                sink -> call(() -> extend.test(user, id, body.message(), body.asUser(), body.sessionId(), sink)));
    }

    public record RunView(
            String runId,
            String traceId,
            String sessionId,
            String status,
            String text,
            Map<String, Object> output,
            Object pending,
            String error,
            List<RunEvent> events) {}

    static Object respond(HttpServletRequest request, Function<RunEvents.Sink, RuntimeService.RunOutcome> work) {
        String accept = request.getHeader("Accept");
        if (accept != null
                && accept.contains(MediaType.APPLICATION_JSON_VALUE)
                && !accept.contains("text/event-stream")) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = work.apply(events::add);
            var r = outcome.result();
            return ApiResponse.success(new RunView(
                    outcome.runId(),
                    outcome.traceId(),
                    outcome.sessionId(),
                    r.status(),
                    r.text(),
                    r.output(),
                    r.pending(),
                    r.error(),
                    events));
        }
        return SseRuns.start(SSE_TIMEOUT_MILLIS, "TEST_RUN_FAILED", work::apply);
    }
}

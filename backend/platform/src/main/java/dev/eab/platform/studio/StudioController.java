package dev.eab.platform.studio;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.http.SseRuns;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService.DraftView;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Studio 的接口（docs/studio-api.md）：只对本租户的开发者和管理员开放 */
@RestController
@Tag(name = "studio")
public class StudioController {

    static final int MAX_TEXT = 2000;
    static final int MAX_YAML = 200_000;
    static final long SSE_TIMEOUT_MILLIS = 600_000;
    static final List<String> ROLES = List.of("tenant-developer", "tenant-admin");

    private final PlatformServices services;
    private final StudioService studio;
    private final StudioReleases releases;
    private final boolean demoMode;
    private final RateLimiter limiter;

    public StudioController(
            PlatformServices services, StudioService studio, StudioReleases releases, DemoMode demoMode) {
        this.services = services;
        this.studio = studio;
        this.releases = releases;
        this.demoMode = demoMode.enabled();
        this.limiter = new RateLimiter(60, 60_000, services.clock());
    }

    private DemoUser developer(HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant) || principal.user().roles().stream().noneMatch(ROLES::contains)) {
            throw new ApiException(403, "FORBIDDEN", "Studio 只对租户开发者和租户管理员开放");
        }
        return principal.user();
    }

    /** Studio 的业务异常转成统一的错误信封，details 带问题列表或门禁结果 */
    private static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (StudioException e) {
            throw new ApiException(e.status(), new ApiError(e.code(), e.getMessage(), e.details(), null));
        }
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：" + field + " 不能为空，最长 " + max + " 字");
        }
        return value;
    }

    // ── 首页与能力清单 ──

    @GetMapping("/studio/home")
    @Operation(summary = "Studio 首页：我的草稿、本租户的 Agent 与各环境版本、待处理的开发需求")
    public ApiResponse<Map<String, Object>> home(HttpServletRequest request) {
        DemoUser user = developer(request);
        List<Map<String, Object>> drafts = studio.drafts().listByTenant(user.tenantId(), 30).stream()
                .map(d -> studio.view(d).draft())
                .toList();
        Viewer viewer = Viewer.tenant(user.tenantId());
        List<Map<String, Object>> agents = new ArrayList<>();
        var published = services.registry()
                .latestPublished(new AssetFilter("Agent", null, "tenant", user.tenantId(), null), viewer)
                .assets();
        for (var agent : published) {
            String ns = agent.metadata().namespace();
            String name = agent.metadata().name();
            Map<String, Object> envs = new LinkedHashMap<>();
            for (String env : StudioStores.ENVIRONMENTS) {
                releases.deployments().active(user.tenantId(), env, ns, name).ifPresent(d -> envs.put(env, d.version()));
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", ns + "/" + name);
            item.put("name", agent.metadata().displayName() == null ? name : agent.metadata().displayName());
            item.put("version", agent.metadata().version());
            item.put("environments", envs);
            agents.add(item);
        }
        List<Map<String, Object>> requests = new ArrayList<>();
        for (var summary : services.registry().list(viewer, new AssetFilter("DevRequest", null, null, user.tenantId(), null))) {
            var view = services.registry().view(viewer, summary.namespace(), summary.name(), summary.version());
            if (!view.ok()) continue;
            var spec = view.value().asset().path("spec");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", summary.namespace() + "/" + summary.name());
            item.put("originalText", spec.path("originalText").asString(""));
            item.put("requester", spec.path("requester").asString(""));
            item.put("status", spec.path("status").asString(""));
            item.put("createdAt", summary.updatedAt());
            requests.add(item);
        }
        return ApiResponse.success(Map.of("drafts", drafts, "agents", agents, "devRequests", requests));
    }

    @GetMapping("/studio/catalog")
    @Operation(summary = "向导推荐用的能力清单")
    public ApiResponse<StudioCatalog> catalog(HttpServletRequest request) {
        return ApiResponse.success(studio.catalog(developer(request).tenantId()));
    }

    // ── 草稿与向导 ──

    public record IntentBody(String intent) {}

    @PostMapping("/studio/drafts")
    @Operation(summary = "从一句话意图新建草稿")
    public ApiResponse<DraftView> create(HttpServletRequest request, @RequestBody IntentBody body) {
        DemoUser user = developer(request);
        limiter.check("studio:" + user.id());
        return ApiResponse.success(studio.create(user, text(body == null ? null : body.intent(), "intent", MAX_TEXT)));
    }

    private Draft draft(DemoUser user, String id) {
        return call(() -> studio.draft(user, id));
    }

    @GetMapping("/studio/drafts/{id}")
    @Operation(summary = "读取草稿")
    public ApiResponse<DraftView> get(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(request);
        return ApiResponse.success(studio.view(draft(user, id)));
    }

    @DeleteMapping("/studio/drafts/{id}")
    @Operation(summary = "删除草稿")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(request);
        if (!studio.drafts().delete(user.tenantId(), id)) throw new ApiException(404, "NOT_FOUND", "草稿 " + id + " 不存在");
        return ApiResponse.success(Map.of("id", id, "deleted", true));
    }

    public record StepBody(String step, Map<String, Object> value) {}

    @PostMapping("/studio/drafts/{id}/recommend")
    @Operation(summary = "向导一步的 AI 推荐（经模型网关）")
    public ApiResponse<StudioRecommender.Recommendation> recommend(
            HttpServletRequest request, @PathVariable String id, @RequestBody StepBody body) {
        DemoUser user = developer(request);
        limiter.check("studio:" + user.id());
        if (body == null || body.step() == null || !StudioRecommender.STEPS.containsKey(body.step())) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：step 只能是 " + String.join("、", StudioRecommender.STEPS.keySet()));
        }
        Draft draft = draft(user, id);
        return ApiResponse.success(call(() -> studio.recommend(user, draft, body.step())));
    }

    @PostMapping("/studio/drafts/{id}/apply")
    @Operation(summary = "采纳一步的取值，返回更新后的草稿")
    public ApiResponse<DraftView> apply(HttpServletRequest request, @PathVariable String id, @RequestBody StepBody body) {
        DemoUser user = developer(request);
        if (body == null || body.step() == null || body.value() == null) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 step 和 value");
        }
        Draft draft = draft(user, id);
        return ApiResponse.success(call(() -> studio.apply(user, draft, body.step(), body.value())));
    }

    public record YamlBody(String yaml) {}

    @PutMapping("/studio/drafts/{id}/yaml")
    @Operation(summary = "YAML 编辑模式：替换草稿内容")
    public ApiResponse<DraftView> putYaml(HttpServletRequest request, @PathVariable String id, @RequestBody YamlBody body) {
        DemoUser user = developer(request);
        Draft draft = draft(user, id);
        String yaml = text(body == null ? null : body.yaml(), "yaml", MAX_YAML);
        return ApiResponse.success(call(() -> studio.putYaml(user, draft, yaml)));
    }

    // ── 评测 ──

    public record GenerateBody(String testUser) {}

    @PostMapping("/studio/drafts/{id}/evals/generate")
    @Operation(summary = "从语义包的已验证任务生成评测集初稿")
    public ApiResponse<DraftView> generateEvals(
            HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) GenerateBody body) {
        DemoUser user = developer(request);
        Draft draft = draft(user, id);
        String testUser = body == null || body.testUser() == null ? "zhangmin" : body.testUser();
        return ApiResponse.success(call(() -> studio.generateEvals(user, draft, testUser)));
    }

    @PutMapping("/studio/drafts/{id}/evals")
    @Operation(summary = "替换评测集草稿")
    public ApiResponse<DraftView> putEvals(HttpServletRequest request, @PathVariable String id, @RequestBody YamlBody body) {
        DemoUser user = developer(request);
        Draft draft = draft(user, id);
        String yaml = text(body == null ? null : body.yaml(), "yaml", MAX_YAML);
        return ApiResponse.success(call(() -> studio.putEvals(user, draft, yaml)));
    }

    @PostMapping("/studio/drafts/{id}/evals/run")
    @Operation(summary = "在私有环境里逐个运行评测用例")
    public ApiResponse<StudioService.EvalRunView> runEvals(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(request);
        requireDemo();
        limiter.check("studio:" + user.id());
        Draft draft = draft(user, id);
        return ApiResponse.success(call(() -> studio.runEvals(user, draft)));
    }

    // ── 测试 ──

    private void requireDemo() {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启，私有环境测试不可用");
    }

    public record TestBody(String message, String asUser, String sessionId) {}

    @PostMapping("/studio/drafts/{id}/test")
    @Operation(summary = "私有环境测试：以所选测试用户的身份运行草稿，SSE 推送运行事件")
    public Object test(HttpServletRequest request, @PathVariable String id, @RequestBody TestBody body) {
        DemoUser user = developer(request);
        requireDemo();
        limiter.check("studio:" + user.id());
        String message = text(body == null ? null : body.message(), "message", MAX_TEXT);
        if (body.sessionId() != null && !body.sessionId().matches("^[A-Za-z0-9-]{1,80}$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：sessionId 只能包含字母、数字和连字符");
        }
        Draft draft = draft(user, id);
        // 草稿和测试用户在发 SSE 之前校验，错误按普通 JSON 返回
        call(() -> StudioService.parse(draft.yaml(), "草稿"));
        call(() -> studio.testUser(user, body.asUser()));
        return respond(request, sink -> call(() -> studio.test(user, draft, message, body.asUser(), body.sessionId(), sink)));
    }

    public record ConfirmationBody(Boolean approved) {}

    @PostMapping("/studio/test-runs/{runId}/confirmation")
    @Operation(summary = "确认或拒绝挂起的测试运行")
    public Object confirm(HttpServletRequest request, @PathVariable String runId, @RequestBody ConfirmationBody body) {
        DemoUser user = developer(request);
        requireDemo();
        if (body == null || body.approved() == null) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：approved 必须是 true 或 false");
        }
        return respond(request, sink -> call(() -> studio.confirmTest(user, runId, body.approved(), sink)));
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

    private static Object respond(HttpServletRequest request, Function<RunEvents.Sink, RuntimeService.RunOutcome> work) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE) && !accept.contains("text/event-stream")) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = work.apply(events::add);
            var r = outcome.result();
            return ApiResponse.success(new RunView(
                    outcome.runId(), outcome.traceId(), outcome.sessionId(), r.status(), r.text(), r.output(), r.pending(), r.error(), events));
        }
        return SseRuns.start(SSE_TIMEOUT_MILLIS, "TEST_RUN_FAILED", work::apply);
    }

    // ── 静态检查与发布 ──

    @PostMapping("/studio/drafts/{id}/lint")
    @Operation(summary = "静态检查")
    public ApiResponse<StudioReleases.LintReport> lint(HttpServletRequest request, @PathVariable String id) {
        DemoUser user = developer(request);
        Draft draft = draft(user, id);
        return ApiResponse.success(call(() -> releases.lint(user, draft)));
    }

    public record PublishBody(String changeNote) {}

    @PostMapping("/studio/drafts/{id}/publish")
    @Operation(summary = "发布：写入注册中心并签名，部署到 dev")
    public ApiResponse<StudioReleases.PublishResult> publish(
            HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) PublishBody body) {
        DemoUser user = developer(request);
        Draft draft = draft(user, id);
        String note = body == null ? null : body.changeNote();
        if (note != null && note.length() > MAX_TEXT) throw new ApiException(400, "BAD_REQUEST", "变更说明最长 " + MAX_TEXT + " 字");
        return ApiResponse.success(call(() -> releases.publish(user, draft, note)));
    }

    @GetMapping("/studio/deployments")
    @Operation(summary = "某个资产在各环境的部署与历史")
    public ApiResponse<Map<String, Object>> deployments(HttpServletRequest request, @RequestParam String ref) {
        DemoUser user = developer(request);
        return ApiResponse.success(call(() -> releases.deploymentsOf(user, ref)));
    }

    public record PromoteBody(String ref, String version, String environment) {}

    @PostMapping("/studio/deployments/promote")
    @Operation(summary = "晋级到 test 或 prod，门禁不过时 409 GATE_BLOCKED")
    public ApiResponse<StudioReleases.PromoteResult> promote(HttpServletRequest request, @RequestBody PromoteBody body) {
        DemoUser user = developer(request);
        if (body == null || body.version() == null || !body.version().matches("^\\d+\\.\\d+\\.\\d+$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 ref、version（x.y.z）和 environment");
        }
        return ApiResponse.success(call(() -> releases.promote(user, body.ref(), body.version(), String.valueOf(body.environment()))));
    }

    public record RollbackBody(String ref, String environment) {}

    @PostMapping("/studio/deployments/rollback")
    @Operation(summary = "回滚到上一个版本")
    public ApiResponse<Map<String, Object>> rollback(HttpServletRequest request, @RequestBody RollbackBody body) {
        DemoUser user = developer(request);
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 ref 和 environment");
        return ApiResponse.success(Map.of(
                "deployment", call(() -> releases.rollback(user, body.ref(), String.valueOf(body.environment())))));
    }
}

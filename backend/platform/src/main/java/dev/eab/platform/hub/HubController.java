package dev.eab.platform.hub;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.feedback.FeedbackService;
import dev.eab.platform.feedback.FeedbackService.FeedbackException;
import dev.eab.platform.feedback.FeedbackStore.Feedback;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.lifecycle.EvalService;
import dev.eab.platform.lifecycle.SolutionService;
import dev.eab.platform.lifecycle.SolutionService.SolutionException;
import dev.eab.platform.lifecycle.UpgradeService;
import dev.eab.platform.lifecycle.UpgradeService.UpgradeException;
import dev.eab.platform.lifecycle.VersionDiff;
import dev.eab.platform.studio.StudioReleases;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理中心的接口（主提示词第 11、12 节，ADR 0013）。租户管理员看本租户、执行审核与上架；租户开发者只读；
 * 平台运维用 tenant 参数查看任意租户。委托管理、风险告警、审计查询沿用 /identity/v1/delegations、/runtime/alerts、/audit/events。
 */
@RestController
@Tag(name = "hub")
public class HubController {

    static final String NAME = "^[a-z][a-z0-9-]{1,40}$";

    private final PlatformServices services;
    private final HubService hub;
    private final MetricsService metrics;
    private final FeedbackService feedback;
    private final UpgradeService upgrades;
    private final SolutionService solutions;
    private final EvalService evals;
    private final StudioReleases releases;
    private final IntegrationCompare integration;
    private final boolean demoMode;

    public HubController(
            PlatformServices services,
            HubService hub,
            MetricsService metrics,
            FeedbackService feedback,
            UpgradeService upgrades,
            SolutionService solutions,
            EvalService evals,
            StudioReleases releases,
            IntegrationCompare integration,
            DemoMode demoMode) {
        this.services = services;
        this.hub = hub;
        this.metrics = metrics;
        this.feedback = feedback;
        this.upgrades = upgrades;
        this.solutions = solutions;
        this.evals = evals;
        this.releases = releases;
        this.integration = integration;
        this.demoMode = demoMode.enabled();
    }

    /** 查看身份：租户管理员、租户开发者看本租户；平台运维要给出 tenant */
    record Scope(String tenantId, DemoUser user, boolean admin) {}

    private Scope scope(HttpServletRequest request, String tenant) {
        Principal principal = services.principals().require(request);
        DemoUser user = principal.user();
        return switch (principal.viewer()) {
            case Viewer.Platform p -> new Scope(tenant == null ? "t001" : tenant, user, true);
            case Viewer.Tenant t -> {
                boolean admin = user.roles().contains("tenant-admin");
                if (!admin && !user.roles().contains("tenant-developer")) {
                    throw new ApiException(403, "FORBIDDEN", "治理中心只对租户管理员、租户开发者和平台运维开放");
                }
                yield new Scope(t.tenantId(), user, admin);
            }
            case Viewer.Vendor v -> throw new ApiException(403, "FORBIDDEN", "厂商身份看不到租户的治理数据");
        };
    }

    private static Scope admin(Scope s) {
        if (!s.admin()) throw new ApiException(403, "FORBIDDEN", "这个操作只有租户管理员可以执行");
        return s;
    }

    private void requireDemo() {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启");
    }

    private static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (FeedbackException e) {
            throw new ApiException(e.status(), e.code(), e.getMessage());
        } catch (SolutionException e) {
            throw new ApiException(e.status(), new ApiError(e.code(), e.getMessage(), e.details(), null));
        } catch (UpgradeException e) {
            throw new ApiException(409, "UPGRADE_FAILED", e.getMessage());
        } catch (StudioException e) {
            throw new ApiException(e.status(), new ApiError(e.code(), e.getMessage(), e.details(), null));
        }
    }

    // ── 资产清单、版本差异 ──

    @GetMapping("/hub/inventory")
    @Operation(summary = "资产清单与血缘：岗位助手、Agent、扩展、远程 Agent、MCP Server、模型档位")
    public ApiResponse<HubService.Inventory> inventory(HttpServletRequest request, @RequestParam(required = false) String tenant) {
        return ApiResponse.success(hub.inventory(scope(request, tenant).tenantId()));
    }

    @GetMapping("/hub/assets/versions")
    @Operation(summary = "某个资产的版本列表，供版本差异选择")
    public ApiResponse<List<Map<String, Object>>> versions(
            HttpServletRequest request, @RequestParam String ref, @RequestParam(required = false) String tenant) {
        Scope s = scope(request, tenant);
        AssetRef r = parse(ref);
        var list = services.registry().resolverFor(Viewer.tenant(s.tenantId())).listVersions(r.namespace(), r.name());
        return ApiResponse.success(list.stream()
                .map(v -> Map.<String, Object>of("version", v.version(), "status", v.status().name()))
                .toList());
    }

    @GetMapping("/hub/assets/diff")
    @Operation(summary = "两个版本的差异；租户视角下标品指令只显示“已变更”")
    public ApiResponse<Map<String, Object>> diff(
            HttpServletRequest request,
            @RequestParam String ref,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String tenant) {
        Principal principal = services.principals().require(request);
        Viewer viewer = principal.viewer() instanceof Viewer.Platform ? Viewer.PLATFORM : Viewer.tenant(scope(request, tenant).tenantId());
        AssetRef r = parse(ref);
        var a = services.registry().view(viewer, r.namespace(), r.name(), from);
        var b = services.registry().view(viewer, r.namespace(), r.name(), to);
        if (!a.ok() || !b.ok()) throw new ApiException(404, "NOT_FOUND", "版本不存在");
        return ApiResponse.success(Map.of(
                "ref", r.key(),
                "from", from,
                "to", to,
                "changes", VersionDiff.diff(a.value().asset(), b.value().asset())));
    }

    static AssetRef parse(String ref) {
        if (ref == null || !AssetRef.isValid(ref)) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：ref 写作 namespace/name");
        return AssetRef.parse(ref);
    }

    // ── 上架准入 ──

    @GetMapping("/hub/admission")
    @Operation(summary = "上架准入队列：评测门禁、写级别合规、MCP Server 审批、完整性")
    public ApiResponse<List<HubService.AdmissionItem>> admission(HttpServletRequest request, @RequestParam(required = false) String tenant) {
        return ApiResponse.success(hub.admission(scope(request, tenant).tenantId()));
    }

    public record ApproveBody(String ref, String version) {}

    @PostMapping("/hub/admission/approve")
    @Operation(summary = "批准上架：晋级到 prod（晋级门禁照常检查）")
    public ApiResponse<StudioReleases.PromoteResult> approve(HttpServletRequest request, @RequestBody ApproveBody body) {
        Scope s = admin(scope(request, null));
        if (body == null || body.version() == null || !body.version().matches("^\\d+\\.\\d+\\.\\d+$")) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 ref 和 version");
        }
        parse(body.ref());
        return ApiResponse.success(call(() -> releases.promote(s.user(), body.ref(), body.version(), "prod")));
    }

    // ── 运行指标 ──

    @GetMapping("/hub/metrics")
    @Operation(summary = "运行指标：技术指标、流程指标、业务结果、岗位助手 KPI")
    public ApiResponse<MetricsService.Overview> metrics(
            HttpServletRequest request, @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) String tenant) {
        if (days < 1 || days > 90) throw new ApiException(400, "BAD_REQUEST", "days 应在 1 到 90 之间");
        return ApiResponse.success(metrics.overview(scope(request, tenant).tenantId(), days));
    }

    // ── 审计反查 ──

    @GetMapping("/hub/audit/reverse")
    @Operation(summary = "审计反查：某个业务对象被谁通过哪个 Agent 改过")
    public ApiResponse<List<HubService.Finding>> reverse(
            HttpServletRequest request, @RequestParam String object, @RequestParam(required = false) String tenant) {
        if (object == null || object.isBlank() || object.length() > 64) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：object 不能为空，最长 64 字");
        return ApiResponse.success(hub.reverseLookup(scope(request, tenant).tenantId(), object.strip()));
    }

    @GetMapping("/hub/demo/integration-compare")
    @Operation(summary = "演示：同一问题经 BO API（用户身份）与 Open API（集成账号）的数据范围对照")
    public ApiResponse<IntegrationCompare.Result> integrationCompare(HttpServletRequest request, @RequestParam(defaultValue = "zhangmin") String user) {
        requireDemo();
        Scope s = scope(request, null);
        var target = dev.eab.directory.DemoDirectory.findUser(user)
                .filter(u -> u.tenantId().equals(s.tenantId()))
                .orElseThrow(() -> new ApiException(400, "BAD_REQUEST", "用户 " + user + " 不在本租户"));
        try {
            return ApiResponse.success(integration.compare(target));
        } catch (IllegalStateException e) {
            throw new ApiException(502, "ERP_UNREACHABLE", e.getMessage());
        }
    }

    // ── 升级回归 ──

    @GetMapping("/hub/upgrades")
    @Operation(summary = "标品升级记录与各租户扩展的回归结果")
    public ApiResponse<Map<String, Object>> upgradeList(HttpServletRequest request, @RequestParam(required = false) String tenant) {
        Scope s = scope(request, tenant);
        return ApiResponse.success(Map.of("presets", UpgradeService.PRESETS, "regressions", upgrades.list(s.tenantId())));
    }

    @PostMapping("/hub/demo/upgrade")
    @Operation(summary = "演示控制台：发布标品新版本，自动对依赖它的租户扩展运行回归评测")
    public ApiResponse<List<UpgradeService.Regression>> upgrade(HttpServletRequest request) {
        requireDemo();
        Scope s = admin(scope(request, null));
        return ApiResponse.success(call(() -> upgrades.simulate(UpgradeService.PRESETS.getFirst(), s.user().id())));
    }

    @PostMapping("/hub/demo/history")
    @Operation(summary = "演示控制台：生成过去 30 天的模拟运行数据")
    public ApiResponse<HistorySimulator.Report> history(HttpServletRequest request) {
        requireDemo();
        Scope s = admin(scope(request, null));
        HistorySimulator.Report[] report = new HistorySimulator.Report[1];
        services.db().inTransaction(() -> report[0] = HistorySimulator.generate(
                services.db().jdbc(), s.tenantId(), LocalDate.now(services.clock().withZone(ZoneOffset.UTC))));
        return ApiResponse.success(report[0]);
    }

    // ── 经验回流 ──

    @GetMapping("/hub/feedback")
    @Operation(summary = "经验回流的审核队列")
    public ApiResponse<List<Feedback>> feedbackList(
            HttpServletRequest request, @RequestParam(required = false) String status, @RequestParam(required = false) String tenant) {
        if (status != null && !List.of("pending", "approved", "rejected").contains(status)) {
            throw new ApiException(400, "BAD_REQUEST", "status 只能是 pending、approved、rejected");
        }
        return ApiResponse.success(feedback.store().list(scope(request, tenant).tenantId(), status, 100));
    }

    public record ReviewBody(Map<String, Object> candidate, String note) {}

    @PostMapping("/hub/feedback/{id}/approve")
    @Operation(summary = "审核通过：写入租户语义包扩展，所有相关 Agent 下次运行即生效")
    public ApiResponse<Feedback> approveFeedback(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) ReviewBody body) {
        Scope s = admin(scope(request, null));
        return ApiResponse.success(call(() -> feedback.approve(s.user(), id, body == null ? null : body.candidate(), body == null ? null : body.note())));
    }

    @PostMapping("/hub/feedback/{id}/reject")
    @Operation(summary = "驳回候选规则")
    public ApiResponse<Feedback> rejectFeedback(HttpServletRequest request, @PathVariable String id, @RequestBody(required = false) ReviewBody body) {
        Scope s = admin(scope(request, null));
        return ApiResponse.success(call(() -> feedback.reject(s.user(), id, body == null ? null : body.note())));
    }

    // ── 方案包 ──

    @GetMapping("/hub/solutions")
    @Operation(summary = "方案包及其在各环境的部署")
    public ApiResponse<List<Map<String, Object>>> solutionList(HttpServletRequest request, @RequestParam(required = false) String tenant) {
        Scope s = scope(request, tenant);
        return ApiResponse.success(solutions.list(s.tenantId()).stream()
                .map(p -> {
                    Map<String, Object> envs = new java.util.LinkedHashMap<>();
                    for (String env : List.of("dev", "test", "prod")) {
                        solutions.activePackage(s.tenantId(), env, p.solution())
                                .filter(d -> d.packageId().equals(p.id()))
                                .ifPresent(d -> envs.put(env, d.deployedAt()));
                    }
                    return Map.<String, Object>of("package", p, "verified", solutions.verify(p), "environments", envs);
                })
                .toList());
    }

    public record CompileBody(String name, String root) {}

    @PostMapping("/hub/solutions/compile")
    @Operation(summary = "从一个租户资产（通常是扩展）生成方案并编译成方案包")
    public ApiResponse<SolutionService.Package> compile(HttpServletRequest request, @RequestBody CompileBody body) {
        Scope s = admin(scope(request, null));
        if (body == null || body.name() == null || !body.name().matches(NAME)) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：name 用小写字母、数字和连字符");
        parse(body.root());
        return ApiResponse.success(call(() -> solutions.compileFrom(s.tenantId(), s.user().id(), body.name(), body.root())));
    }

    public record DeployBody(String environment) {}

    @PostMapping("/hub/solutions/packages/{id}/deploy")
    @Operation(summary = "部署方案包：校验签名后部署包里的全部资产")
    public ApiResponse<SolutionService.PackageDeployment> deploy(HttpServletRequest request, @PathVariable String id, @RequestBody DeployBody body) {
        Scope s = admin(scope(request, null));
        if (body == null || body.environment() == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 environment");
        return ApiResponse.success(call(() -> solutions.deploy(s.tenantId(), s.user().id(), id, body.environment())));
    }

    public record SolutionRollbackBody(String solution, String environment) {}

    @PostMapping("/hub/solutions/rollback")
    @Operation(summary = "方案回滚到上一个方案包")
    public ApiResponse<SolutionService.PackageDeployment> rollback(HttpServletRequest request, @RequestBody SolutionRollbackBody body) {
        Scope s = admin(scope(request, null));
        if (body == null || body.solution() == null || body.environment() == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：需要 solution 和 environment");
        return ApiResponse.success(call(() -> solutions.rollback(s.tenantId(), s.user().id(), body.solution(), body.environment())));
    }

    // ── 评测 ──

    @GetMapping("/hub/evals/suites")
    @Operation(summary = "本租户可见的已发布评测集，可按冒烟集、完整集过滤")
    public ApiResponse<List<Map<String, Object>>> suites(
            HttpServletRequest request, @RequestParam(required = false) String tier, @RequestParam(required = false) String tenant) {
        Scope s = scope(request, tenant);
        return ApiResponse.success(evals.suites(s.tenantId(), tier).stream()
                .map(e -> Map.<String, Object>of(
                        "ref", e.metadata().namespace() + "/" + e.metadata().name(),
                        "version", e.metadata().version(),
                        "name", e.metadata().displayName() == null ? e.metadata().name() : e.metadata().displayName(),
                        "target", e.spec().target(),
                        "tier", e.spec().tier(),
                        "minPassRate", e.spec().minPassRate(),
                        "cases", dev.eab.eval.SuiteRunner.expand(e.spec()).size()))
                .toList());
    }

    public record RunSuiteBody(String suite) {}

    @PostMapping("/hub/evals/run")
    @Operation(summary = "运行一个已发布的评测集")
    public ApiResponse<EvalService.SuiteRun> runSuite(HttpServletRequest request, @RequestBody RunSuiteBody body) {
        Scope s = scope(request, null);
        AssetRef ref = parse(body == null ? null : body.suite());
        EvalSuiteAsset suite = evals.suites(s.tenantId(), null).stream()
                .filter(e -> (e.metadata().namespace() + "/" + e.metadata().name()).equals(ref.key()))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "评测集 " + ref.key() + " 不存在"));
        try {
            return ApiResponse.success(evals.run(s.tenantId(), suite, s.user().id()));
        } catch (IllegalStateException e) {
            throw new ApiException(409, "EVAL_FAILED", e.getMessage());
        }
    }
}

package dev.eab.platform.lifecycle;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.eval.Assertions;
import dev.eab.eval.SuiteRunner;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioStores.EvalRunStore;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行注册中心里已发布的评测集（ADR 0013）：冒烟集与完整集、升级回归、晋级门禁都用它。每个用例以用例指定的用户身份运行
 * 被测 Agent（按租户组装，叠加该租户已发布的扩展）；运行遇到确认点时按“用户批准”继续，评测关心的是批准后的行为。
 * 结果按被测资产的内容哈希记入 eval_runs。
 */
public final class EvalService {

    /** 一次运行里最多自动确认的次数，防止确认点循环 */
    static final int MAX_CONFIRMATIONS = 5;

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final EvalRunStore evalRuns;
    private final Assertions.Judge judge;

    public EvalService(
            PlatformServices services, RuntimeService runtime, EvalRunStore evalRuns, Assertions.Judge judge) {
        this.services = services;
        this.runtime = runtime;
        this.evalRuns = evalRuns;
        this.judge = judge;
    }

    /**
     * 一个评测集的运行结果。
     *
     * @param target 被测 Agent，namespace/name@版本
     */
    public record SuiteRun(
            String evalRunId, String suite, String tier, String target, SuiteRunner.SuiteResult result) {}

    /** 本租户可见的已发布评测集，可按层级（smoke、full）过滤 */
    public List<EvalSuiteAsset> suites(String tenantId, String tier) {
        return services
                .registry()
                .latestPublished(new AssetFilter("EvalSuite", null, null, null, null), Viewer.tenant(tenantId))
                .assets()
                .stream()
                .filter(EvalSuiteAsset.class::isInstance)
                .map(EvalSuiteAsset.class::cast)
                .filter(s -> tier == null || tier.equals(s.spec().tier()))
                .toList();
    }

    public SuiteRun run(String tenantId, EvalSuiteAsset suite, String startedBy) {
        String target = suite.spec().target();
        var resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        Asset asset = resolver.resolve(AssetRef.parse(target), AssetResolver.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException("评测对象 " + target + " 不存在或没有发布"));
        // 评测对象是扩展时，运行它扩展的标品 Agent：按租户组装时会叠加这个扩展
        Asset agent = asset instanceof AgentExtensionAsset ext
                ? resolver.resolve(AssetRef.parse(ext.spec().target()), AssetResolver.PUBLISHED)
                        .orElseThrow(() -> new IllegalStateException(
                                "扩展 " + target + " 的目标 " + ext.spec().target() + " 不存在"))
                : asset;
        String pinned = agent.metadata().namespace() + "/" + agent.metadata().name() + "@"
                + agent.metadata().version();
        String suiteRef = suite.metadata().namespace() + "/" + suite.metadata().name();
        var record = evalRuns.start(
                tenantId,
                suiteRef,
                asset.metadata().namespace() + "/" + asset.metadata().name(),
                StudioService.contentHash(asset),
                startedBy,
                suite.spec().minPassRate());
        SuiteRunner.SuiteResult result = SuiteRunner.run(suite.spec(), c -> execute(tenantId, pinned, c), judge);
        evalRuns.finish(record, result.total(), result.passed(), result.status(), Assertions.toJson(result.cases()));
        return new SuiteRun(record.id(), suiteRef, suite.spec().tier(), pinned, result);
    }

    /**
     * 钩子、工作流、技能内部的工具调用不推送事件，但都在追踪里（eab.tool.name、eab.skill.ref）。从追踪补上这些调用，
     * “调用了某工具”的断言对它们同样有效。
     */
    List<RunEvent> toolsFromTrace(String traceId, List<RunEvent> events) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RunEvent e : events)
            if (e.type().equals("tool_call")) seen.add(String.valueOf(e.data().get("tool")));
        List<RunEvent> extra = new ArrayList<>();
        for (var span : runtime.traces().list(traceId)) {
            if (span.attributes().get("eab.tool.name") instanceof String name && seen.add(name)) {
                extra.add(RunEvent.of("tool_call", null, "tool", name, "source", "trace"));
            }
            // 工作流里的技能步骤记的是技能引用，技能作为工具时的名字是它的资产名
            if (span.attributes().get("eab.skill.ref") instanceof String ref) {
                String name = ref.substring(ref.lastIndexOf('/') + 1).replaceAll("@.*$", "");
                if (seen.add(name)) extra.add(RunEvent.of("tool_call", null, "tool", name, "source", "trace"));
            }
        }
        return extra;
    }

    /** 运行一个用例：签发用例用户的令牌，确认点按批准继续 */
    SuiteRunner.Execution execute(String tenantId, String agent, EvalCase evalCase) {
        DemoUser user = DemoDirectory.findUser(evalCase.user())
                .filter(u -> u.tenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalStateException("用例用户 " + evalCase.user() + " 不在租户 " + tenantId));
        String token =
                services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles()));
        List<RunEvent> events = new CopyOnWriteArrayList<>();
        var outcome = runtime.start(
                new RuntimeService.StartRequest(user, token, agent, evalCase.question(), null, null), events::add);
        for (int i = 0; i < MAX_CONFIRMATIONS && outcome.result().status().equals("suspended"); i++) {
            outcome = runtime.confirm(outcome.runId(), user, token, true, events::add);
        }
        var r = outcome.result();
        List<RunEvent> all = new ArrayList<>(events);
        all.addAll(toolsFromTrace(outcome.traceId(), events));
        return new SuiteRunner.Execution(
                new Assertions.CaseRun(r.text(), r.output(), all, r.status(), r.error()),
                outcome.runId(),
                outcome.traceId());
    }
}

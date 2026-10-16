package dev.eab.platform.extend;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.platform.registry.OverlayResolver;
import dev.eab.platform.studio.EvalRunner;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioService.DraftView;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.platform.studio.StudioStores.EvalRun;
import dev.eab.platform.tools.McpGate;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Lint;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.node.ObjectNode;

/**
 * 扩展的评测、静态检查与发布（主提示词 10.4 节，ADR 0011）。发布前强制运行两类评测集：目标标品 Agent 自带的公开评测集，
 * 和扩展自己的评测集；两类都达到最低通过率才能发布。评测在私有环境里叠加扩展草稿运行，遇到确认点一律拒绝，不写入数据。
 * 扩展发布后立即对本租户生效（运行时组装叠加已发布的扩展）。
 */
public final class ExtensionReleases {

    /** 一个评测用例里最多依次拒绝的确认点 */
    static final int MAX_CONFIRMATIONS = 5;

    private final ExtendService extend;
    private final EvalRunner.Judge judge;
    private final McpGate mcpGate;

    /** @param mcpGate 为 null 时不检查 MCP 审批（单元测试） */
    public ExtensionReleases(ExtendService extend, EvalRunner.Judge judge, McpGate mcpGate) {
        this.extend = extend;
        this.judge = judge;
        this.mcpGate = mcpGate;
    }

    private StudioService studio() {
        return extend.studio();
    }

    // ── 评测集 ──

    /** 扩展评测集初稿：目标 Agent 的第一条示例话术，断言调用了扩展新增的工具，再按追加指令做裁判 */
    public DraftView generateEvals(DemoUser user, String id, String testUser) {
        Draft draft = extend.extensionDraft(user, id);
        AgentExtensionAsset ext = ExtendService.parseExtension(draft);
        extend.testUser(user, testUser);
        AgentAsset target = target(user.tenantId(), ext);
        String question = target.spec().routing() != null
                        && !target.spec().routing().examples().isEmpty()
                ? target.spec().routing().examples().getFirst()
                : target.metadata().displayName();
        List<Map<String, Object>> assertions = new ArrayList<>();
        for (String tool : addedToolNames(user.tenantId(), ext)) {
            assertions.add(Map.of("type", "calledTool", "tool", tool, "description", "调用了扩展新增的工具 " + tool));
        }
        String append = ext.spec().instructions() == null
                ? null
                : ext.spec().instructions().append();
        assertions.add(Map.of(
                "type",
                "judge",
                "rubric",
                append == null ? "回答针对问题给出结论，并说明依据" : "回答遵守租户追加的要求：" + append.strip(),
                "description",
                "扩展的要求生效"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "extension-1");
        c.put("user", testUser);
        c.put("question", question);
        c.put("assertions", assertions);
        ObjectNode tree = ExtensionComposer.read(draft.yaml());
        String suiteRef = ExtensionComposer.evalNamespaceFor(user.tenantId()) + "/"
                + tree.path("metadata").path("name").asString() + "-smoke";
        ((ObjectNode) tree.path("spec")).putObject("evals").putArray("suites").add(suiteRef);
        String evalYaml = ExtensionComposer.evalSuite(user.tenantId(), user.id(), tree, List.of(c));
        return studio().view(studio().drafts()
                .update(draft, draft.namespace(), draft.name(), draft.step(), ExtensionComposer.write(tree), evalYaml));
    }

    public DraftView putEvals(DemoUser user, String id, String yaml) {
        Draft draft = extend.extensionDraft(user, id);
        if (!(ExtendService.parse(yaml, "评测集") instanceof EvalSuiteAsset)) {
            throw new StudioException(422, "DRAFT_INVALID", "评测集草稿的 kind 应为 EvalSuite");
        }
        return studio().view(studio().drafts()
                .update(draft, draft.namespace(), draft.name(), draft.step(), draft.yaml(), yaml));
    }

    AgentAsset target(String tenantId, AgentExtensionAsset ext) {
        return extend.services()
                .registry()
                .resolverFor(Viewer.tenant(tenantId))
                .resolveOfKind(ext.spec().target(), AgentAsset.class, AssetResolver.PUBLISHED)
                .orElseThrow(() -> new StudioException(
                        422, "TARGET_NOT_FOUND", "扩展的目标 " + ext.spec().target() + " 不存在"));
    }

    /** 扩展新增的工具名：tools.add 里引用的工具来源的全部工具，以及技能 */
    List<String> addedToolNames(String tenantId, AgentExtensionAsset ext) {
        List<String> names = new ArrayList<>();
        if (ext.spec().tools() == null) return names;
        var resolver = extend.services().registry().resolverFor(Viewer.tenant(tenantId));
        for (var entry : ext.spec().tools().add()) {
            Object value = Json.MAPPER.convertValue(entry, Object.class);
            String ref = value instanceof String s ? s : null;
            if (ref == null) continue;
            var asset = resolver.resolve(ref, AssetResolver.PUBLISHED).orElse(null);
            if (asset instanceof ToolSourceAsset source && source.spec().tools() != null) {
                source.spec().tools().forEach(t -> names.add(t.name()));
            } else if (asset != null) {
                names.add(asset.metadata().name());
            }
        }
        return names;
    }

    // ── 评测运行 ──

    public record SuiteResult(
            String suite,
            String visibility,
            double minPassRate,
            int total,
            int passed,
            boolean ok,
            List<EvalRunner.CaseResult> cases) {}

    public record ExtensionEvalView(String id, String status, List<SuiteResult> suites) {}

    /** 运行标品公开评测集和扩展评测集，记录在 eval_runs（被测对象是扩展，按扩展草稿的内容哈希） */
    public ExtensionEvalView runEvals(DemoUser user, String id) {
        Draft draft = extend.extensionDraft(user, id);
        AgentExtensionAsset ext = ExtendService.parseExtension(draft);
        if (draft.evalYaml() == null) throw new StudioException(409, "NO_EVALS", "还没有扩展评测集，请先生成评测集初稿");
        EvalSuiteAsset own = (EvalSuiteAsset) ExtendService.parse(draft.evalYaml(), "评测集");
        List<EvalSuiteAsset> suites = new ArrayList<>(publicSuites(user.tenantId(), ext));
        suites.add(own);
        String extRef = ext.metadata().namespace() + "/" + ext.metadata().name();
        EvalRun run = studio().evalRuns()
                .start(user.tenantId(), "public+extension", extRef, StudioService.contentHash(ext), user.id(), 1.0);
        List<SuiteResult> results = new ArrayList<>();
        for (EvalSuiteAsset suite : suites) results.add(runSuite(user, draft, suite));
        boolean ok = results.stream().allMatch(SuiteResult::ok);
        int total = results.stream().mapToInt(SuiteResult::total).sum();
        int passed = results.stream().mapToInt(SuiteResult::passed).sum();
        studio().evalRuns()
                .finish(run, total, passed, ok ? "passed" : "failed", Json.MAPPER.writeValueAsString(results));
        return new ExtensionEvalView(run.id(), ok ? "passed" : "failed", results);
    }

    /** 目标标品 Agent 声明的、对租户可见的公开评测集（内部评测集租户读不到） */
    List<EvalSuiteAsset> publicSuites(String tenantId, AgentExtensionAsset ext) {
        AgentAsset target = target(tenantId, ext);
        if (target.spec().evals() == null) return List.of();
        var resolver = extend.services().registry().resolverFor(Viewer.tenant(tenantId));
        List<EvalSuiteAsset> suites = new ArrayList<>();
        for (String ref : target.spec().evals().suites()) {
            resolver.resolveOfKind(ref, EvalSuiteAsset.class, AssetResolver.PUBLISHED)
                    .filter(s -> "public".equals(s.spec().visibility()))
                    .ifPresent(suites::add);
        }
        return suites;
    }

    private SuiteResult runSuite(DemoUser user, Draft draft, EvalSuiteAsset suite) {
        List<EvalRunner.CaseResult> cases = new ArrayList<>();
        for (EvalCase c : suite.spec().cases()) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = extend.test(user, draft.id(), c.question(), c.user(), null, events::add);
            var result = outcome.result();
            // 评测不写入数据：遇到确认点一律拒绝，直到运行结束（扩展的工具和钩子工作流可能各有一个确认点）
            for (int i = 0; i < MAX_CONFIRMATIONS && "suspended".equals(result.status()); i++) {
                var testUser = extend.testUser(user, c.user());
                outcome = extend.runtime()
                        .confirm(outcome.runId(), testUser, extend.userToken(testUser), false, events::add);
                result = outcome.result();
            }
            var caseRun =
                    new EvalRunner.CaseRun(result.text(), result.output(), events, result.status(), result.error());
            cases.add(EvalRunner.check(c, caseRun, outcome.runId(), outcome.traceId(), judge));
        }
        int passed = (int) cases.stream().filter(EvalRunner.CaseResult::passed).count();
        double rate = cases.isEmpty() ? 0 : (double) passed / cases.size();
        return new SuiteResult(
                suite.metadata().namespace() + "/" + suite.metadata().name(),
                suite.spec().visibility(),
                suite.spec().minPassRate(),
                cases.size(),
                passed,
                rate >= suite.spec().minPassRate(),
                cases);
    }

    // ── 静态检查与发布 ──

    public record LintReport(boolean ok, List<Map<String, Object>> issues) {}

    public LintReport lint(DemoUser user, String id) {
        Draft draft = extend.extensionDraft(user, id);
        AgentExtensionAsset ext = ExtendService.parseExtension(draft);
        List<Asset> overlay = new ArrayList<>(List.of(ext));
        if (draft.evalYaml() != null) overlay.add(ExtendService.parse(draft.evalYaml(), "评测集"));
        return lint(user.tenantId(), ext, overlay);
    }

    LintReport lint(String tenantId, AgentExtensionAsset ext, List<Asset> overlay) {
        var registry = extend.services().registry();
        var resolver = new OverlayResolver(registry.resolverFor(Viewer.tenant(tenantId)), overlay);
        List<Map<String, Object>> issues = new ArrayList<>();
        for (var i : Lint.lint(ext, resolver, AssetResolver.LIVE)) {
            issues.add(issue(i.rule(), i.severity(), i.message(), i.path()));
        }
        var assembled = EffectiveAssembly.assemble(
                registry,
                Viewer.tenant(tenantId),
                EffectiveAssembly.Request.withOverlay(ext.spec().target(), tenantId, overlay));
        for (var i : assembled.issues()) {
            issues.add(issue("assembly", i.isError() ? "error" : "warning", i.code() + " " + i.message(), i.path()));
        }
        boolean ok = issues.stream().noneMatch(i -> "error".equals(i.get("severity")));
        return new LintReport(ok, issues);
    }

    private static Map<String, Object> issue(String rule, String severity, String message, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("severity", severity);
        m.put("message", message);
        if (path != null) m.put("path", path);
        return m;
    }

    public record PublishResult(String ref, String version, boolean unchanged, String evalRun) {}

    /** 发布：静态检查无错误，两类评测都通过（同一内容已有通过记录时直接用，否则当场运行），再写入注册中心 */
    public PublishResult publish(DemoUser user, String id, String changeNote) {
        Draft draft = extend.extensionDraft(user, id);
        AgentExtensionAsset ext = ExtendService.parseExtension(draft);
        if (draft.evalYaml() == null) throw new StudioException(409, "NO_EVALS", "发布前需要扩展评测集，请先生成评测集初稿");
        EvalSuiteAsset suite = (EvalSuiteAsset) ExtendService.parse(draft.evalYaml(), "评测集");
        LintReport report = lint(user.tenantId(), ext, List.of(ext, suite));
        if (!report.ok()) throw new StudioException(409, "LINT_FAILED", "静态检查有错误，不能发布", report.issues());
        // 扩展发布即对本租户生效，相当于上了生产，用到的 MCP Server 必须已审批
        List<String> unapproved = mcpGate == null ? List.of() : mcpGate.unapproved(user.tenantId(), ext);
        if (!unapproved.isEmpty()) {
            throw new StudioException(
                    409, "MCP_NOT_APPROVED", "扩展用到的 MCP Server 还没有审批通过：" + String.join("、", unapproved), unapproved);
        }

        String extRef = ext.metadata().namespace() + "/" + ext.metadata().name();
        String hash = StudioService.contentHash(ext);
        Optional<EvalRun> passed = studio().evalRuns().latestPassed(user.tenantId(), extRef, hash);
        String evalRun;
        if (passed.isPresent()) {
            evalRun = passed.get().id();
        } else {
            ExtensionEvalView view = runEvals(user, id);
            if (!view.status().equals("passed")) {
                throw new StudioException(409, "EVALS_FAILED", "标品公开评测集或扩展评测集没有通过，不能发布", view.suites());
            }
            evalRun = view.id();
        }

        var registry = extend.services().registry();
        Viewer viewer = Viewer.tenant(user.tenantId());
        var resolver = registry.resolverFor(viewer);
        var versions =
                resolver.listVersions(ext.metadata().namespace(), ext.metadata().name());
        if (!versions.isEmpty()) {
            var latest = versions.getFirst();
            var existing =
                    resolver.get(ext.metadata().namespace(), ext.metadata().name(), latest.version());
            if (existing.isPresent()
                    && latest.status() == AssetResolver.Status.published
                    && StudioService.contentHash(existing.get()).equals(hash)) {
                return new PublishResult(extRef, latest.version(), true, evalRun);
            }
        }
        String version = nextVersion(
                ext.metadata().version(),
                versions.stream().map(AssetResolver.VersionInfo::version).toList());
        ObjectNode extTree = ExtensionComposer.read(draft.yaml());
        ((ObjectNode) extTree.path("metadata")).put("version", version);
        ObjectNode suiteTree = ExtensionComposer.read(draft.evalYaml());
        ((ObjectNode) suiteTree.path("metadata")).put("version", version);
        String note = changeNote == null || changeNote.isBlank() ? "Studio 发布扩展" : changeNote;
        String suiteNs = suite.metadata().namespace();
        String suiteName = suite.metadata().name();
        put(viewer, ExtensionComposer.write(extTree), note);
        boolean suiteExists =
                registry.resolverFor(viewer).get(suiteNs, suiteName, version).isPresent();
        if (!suiteExists) put(viewer, ExtensionComposer.write(suiteTree), note);
        publishStatus(viewer, ext.metadata().namespace(), ext.metadata().name(), version);
        if (!suiteExists) publishStatus(viewer, suiteNs, suiteName, version);
        studio().drafts()
                .update(
                        draft,
                        draft.namespace(),
                        draft.name(),
                        draft.step(),
                        ExtensionComposer.write(extTree),
                        ExtensionComposer.write(suiteTree));
        studio().drafts().markPublished(draft, version);
        extend.services()
                .audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                "studio.extension.publish",
                                extRef + "@" + version,
                                "allow",
                                "发布扩展，评测 " + evalRun + " 通过")
                        .tenantId(user.tenantId()));
        return new PublishResult(extRef, version, false, evalRun);
    }

    static String nextVersion(String draftVersion, List<String> existing) {
        if (existing.isEmpty()) return draftVersion;
        String max = existing.stream().max(Versions::compare).orElseThrow();
        if (Versions.compare(draftVersion, max) > 0) return draftVersion;
        String[] parts = max.split("[.-]");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private void put(Viewer viewer, String yaml, String note) {
        var result = extend.services().registry().put(viewer, yaml, note);
        if (!result.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "写入注册中心失败：" + result.error().message(),
                    result.error().issues());
        }
    }

    private void publishStatus(Viewer viewer, String namespace, String name, String version) {
        var result = extend.services()
                .registry()
                .setStatus(viewer, namespace, name, version, AssetResolver.Status.published);
        if (!result.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "发布失败：" + result.error().message(),
                    result.error().issues());
        }
    }

    static String targetKey(AgentExtensionAsset ext) {
        return AssetRef.parse(ext.spec().target()).key();
    }
}

package dev.eab.platform.studio;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.CanonicalJson;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.platform.studio.StudioStores.DraftStore;
import dev.eab.platform.studio.StudioStores.EvalRun;
import dev.eab.platform.studio.StudioStores.EvalRunStore;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.AssetParser;
import dev.eab.spec.Json;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Studio 的工作区（ADR 0010）：从意图新建草稿、逐步推荐与采纳、YAML 编辑、私有环境测试、评测集生成与运行。
 * 发布与晋级在 StudioReleases。所有操作都限定在开发者自己的租户里。
 */
public final class StudioService {

    public static final class StudioException extends RuntimeException {
        private final int status;
        private final String code;
        private final Object details;

        public StudioException(int status, String code, String message, Object details) {
            super(message);
            this.status = status;
            this.code = code;
            this.details = details;
        }

        public StudioException(int status, String code, String message) {
            this(status, code, message, null);
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public Object details() {
            return details;
        }
    }

    public record Issue(String code, String message, String path, Integer line) {}

    public record DraftView(
            Map<String, Object> draft, String yaml, Map<String, Object> asset, List<Issue> issues, String evalYaml) {}

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final DraftStore drafts;
    private final EvalRunStore evalRuns;
    private final StudioRecommender recommender;
    private final EvalRunner.Judge judge;

    public StudioService(
            PlatformServices services,
            RuntimeService runtime,
            DraftStore drafts,
            EvalRunStore evalRuns,
            StudioRecommender recommender,
            EvalRunner.Judge judge) {
        this.services = services;
        this.runtime = runtime;
        this.drafts = drafts;
        this.evalRuns = evalRuns;
        this.recommender = recommender;
        this.judge = judge;
    }

    public DraftStore drafts() {
        return drafts;
    }

    public EvalRunStore evalRuns() {
        return evalRuns;
    }

    public StudioCatalog catalog(String tenantId) {
        return StudioCatalog.build(services.registry(), tenantId);
    }

    // ── 草稿 ──

    public Draft draft(DemoUser user, String id) {
        return drafts.get(user.tenantId(), id)
                .orElseThrow(() -> new StudioException(404, "NOT_FOUND", "草稿 " + id + " 不存在"));
    }

    public DraftView create(DemoUser user, String intent) {
        String name = String.valueOf(StudioDefaults.naming(intent).get("name"));
        ObjectNode tree = StudioDraftComposer.skeleton(user.tenantId(), user.id(), name, intent);
        Draft draft = drafts.create(
                user.tenantId(),
                user.id(),
                "Agent",
                StudioDraftComposer.namespaceFor(user.tenantId()),
                name,
                intent.strip(),
                StudioDraftComposer.write(tree));
        return view(draft);
    }

    public DraftView view(Draft draft) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", draft.id());
        summary.put("intent", draft.intent());
        summary.put("step", draft.step());
        summary.put("kind", draft.kind());
        summary.put("namespace", draft.namespace());
        summary.put("name", draft.name());
        summary.put("publishedVersion", draft.publishedVersion());
        summary.put("owner", draft.owner());
        summary.put("updatedAt", draft.updatedAt());
        Map<String, Object> asset = null;
        try {
            asset = Json.MAPPER.convertValue(StudioDraftComposer.read(draft.yaml()), Map.class);
        } catch (RuntimeException e) {
            // YAML 语法错误时只返回问题列表
        }
        return new DraftView(summary, draft.yaml(), asset, issues(draft.yaml()), draft.evalYaml());
    }

    static List<Issue> issues(String yaml) {
        AssetParser.ParseResult parsed = AssetParser.parseYaml(yaml);
        if (parsed.ok()) return List.of();
        return parsed.issues().stream()
                .map(i -> new Issue(i.code(), i.message(), i.path(), i.line()))
                .toList();
    }

    /** 草稿解析成资产；有 schema 问题时拒绝（测试、评测、发布都要求草稿合法） */
    static Asset parse(String yaml, String what) {
        AssetParser.ParseResult parsed = AssetParser.parseYaml(yaml);
        if (!parsed.ok()) {
            throw new StudioException(422, "DRAFT_INVALID", what + "不合法，请先修正 YAML 里的问题", issues(yaml));
        }
        return parsed.asset();
    }

    /** 推荐时交给模型的“已选”：从草稿里取出各步已经确定的内容 */
    static Map<String, Object> selections(ObjectNode tree) {
        JsonNode metadata = tree.path("metadata");
        JsonNode spec = tree.path("spec");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("displayName", metadata.path("displayName").asString(""));
        s.put("description", metadata.path("description").asString(""));
        s.put("businessProcess", Json.MAPPER.convertValue(metadata.path("businessProcess"), Object.class));
        s.put("semanticPacks", Json.MAPPER.convertValue(spec.path("semanticPacks"), Object.class));
        List<String> agents = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        for (JsonNode entry : spec.path("tools")) {
            if (entry.has("agent")) agents.add(entry.path("agent").asString());
            if (entry.has("skill")) skills.add(entry.path("skill").asString());
            if (entry.has("api")) {
                for (JsonNode name : entry.path("api").path("tools")) {
                    tools.add(Map.of("source", entry.path("api").path("source").asString(), "name", name.asString()));
                }
            }
        }
        s.put("agents", agents);
        s.put("skills", skills);
        s.put("tools", tools);
        s.put("constraints", Json.MAPPER.convertValue(spec.path("constraints"), Object.class));
        s.put("profile", spec.path("model").path("profile").asString(""));
        return s;
    }

    public StudioRecommender.Recommendation recommend(DemoUser user, Draft draft, String step) {
        ObjectNode tree = StudioDraftComposer.read(draft.yaml());
        return recommender.recommend(step, draft.intent(), selections(tree), catalog(user.tenantId()), user.id());
    }

    public DraftView apply(DemoUser user, Draft draft, String step, Map<String, Object> value) {
        ObjectNode tree;
        try {
            tree = StudioDraftComposer.apply(StudioDraftComposer.read(draft.yaml()), step, value);
        } catch (IllegalArgumentException e) {
            throw new StudioException(400, "BAD_REQUEST", e.getMessage());
        } catch (RuntimeException e) {
            throw new StudioException(422, "DRAFT_INVALID", "草稿的 YAML 无法解析，请先在 YAML 编辑模式里修正");
        }
        int stepIndex = List.copyOf(StudioRecommender.STEPS.keySet()).indexOf(step) + 1;
        return save(draft, tree, Math.max(draft.step(), stepIndex), StudioDraftComposer.write(tree), draft.evalYaml());
    }

    public DraftView putYaml(DemoUser user, Draft draft, String yaml) {
        ObjectNode tree;
        try {
            tree = StudioDraftComposer.read(yaml);
        } catch (RuntimeException e) {
            // 语法错误的 YAML 也保存，方便开发者继续改
            drafts.update(draft, draft.namespace(), draft.name(), draft.step(), yaml, draft.evalYaml());
            return view(drafts.get(draft.tenantId(), draft.id()).orElseThrow());
        }
        return save(draft, tree, draft.step(), yaml, draft.evalYaml());
    }

    private DraftView save(Draft draft, ObjectNode tree, int step, String yaml, String evalYaml) {
        String namespace = tree.path("metadata").path("namespace").asString(draft.namespace());
        String name = tree.path("metadata").path("name").asString(draft.name());
        String expected = StudioDraftComposer.namespaceFor(draft.tenantId());
        if (!namespace.startsWith("tenant." + draft.tenantId() + ".")) {
            throw new StudioException(422, "NAMESPACE_FORBIDDEN", "草稿只能放在本租户的命名空间里，例如 " + expected);
        }
        return view(drafts.update(draft, namespace, name, step, yaml, evalYaml));
    }

    // ── 评测集 ──

    public DraftView generateEvals(DemoUser user, Draft draft, String testUser) {
        if (DemoDirectory.findUser(testUser)
                .filter(u -> u.tenantId().equals(user.tenantId()))
                .isEmpty()) {
            throw new StudioException(400, "BAD_REQUEST", "测试用户 " + testUser + " 不在本租户");
        }
        ObjectNode tree = StudioDraftComposer.read(draft.yaml());
        List<Map<String, Object>> cases =
                StudioDefaults.evalCases(draft.intent(), selections(tree), catalog(user.tenantId()), testUser);
        if (cases.isEmpty()) {
            // 没有可用的已验证任务时，用路由示例话术生成一个只做裁判的用例，开发者再补充
            String question =
                    tree.path("spec").path("routing").path("examples").path(0).asString(draft.intent());
            cases = List.of(Map.of(
                    "id",
                    "example-1",
                    "user",
                    testUser,
                    "question",
                    question,
                    "assertions",
                    List.of(Map.of(
                            "type", "judge",
                            "rubric", "回答针对问题给出结论，并说明依据的数据",
                            "description", "给出有依据的结论"))));
        }
        String evalYaml = StudioDraftComposer.evalSuite(user.tenantId(), user.id(), tree, cases);
        String suiteRef = StudioDraftComposer.evalNamespaceFor(user.tenantId()) + "/"
                + tree.path("metadata").path("name").asString() + "-smoke";
        ((ObjectNode) tree.path("spec")).putObject("evals").putArray("suites").add(suiteRef);
        return save(draft, tree, Math.max(draft.step(), 11), StudioDraftComposer.write(tree), evalYaml);
    }

    public DraftView putEvals(DemoUser user, Draft draft, String yaml) {
        Asset suite = parse(yaml, "评测集");
        if (!(suite instanceof EvalSuiteAsset))
            throw new StudioException(422, "DRAFT_INVALID", "评测集草稿的 kind 应为 EvalSuite");
        return view(drafts.update(draft, draft.namespace(), draft.name(), draft.step(), draft.yaml(), yaml));
    }

    // ── 私有环境测试 ──

    /** 演示模式下，以所选测试用户的身份运行草稿；平台为测试用户签发令牌，写审计 */
    public RuntimeService.RunOutcome test(
            DemoUser developer, Draft draft, String message, String asUser, String sessionId, RunEvents.Sink sink) {
        DemoUser testUser = testUser(developer, asUser);
        Asset asset = parse(draft.yaml(), "草稿");
        services.audit()
                .append(NewAuditEvent.of(
                                developer.id(),
                                "studio.test-run",
                                asset.versionedRef(),
                                "allow",
                                developer.id() + " 在私有环境以 " + testUser.id() + " 的身份测试草稿")
                        .tenantId(developer.tenantId()));
        return runtime.start(
                new RuntimeService.StartRequest(
                        testUser,
                        userToken(testUser),
                        asset.metadata().namespace() + "/" + asset.metadata().name(),
                        message,
                        sessionId,
                        null,
                        null,
                        new RuntimeService.TestRun(developer.id(), List.of(draft.yaml()))),
                sink);
    }

    public RuntimeService.RunOutcome confirmTest(
            DemoUser developer, String runId, boolean approved, RunEvents.Sink sink) {
        String startedBy = runtime.runs().startedBy(runId).orElse(null);
        var run = runtime.runs().get(runId).orElse(null);
        if (run == null || !developer.id().equals(startedBy) || !run.tenantId().equals(developer.tenantId())) {
            throw new StudioException(404, "NOT_FOUND", "测试运行 " + runId + " 不存在");
        }
        DemoUser testUser = DemoDirectory.findUser(run.userId()).orElseThrow();
        try {
            return runtime.confirm(runId, testUser, userToken(testUser), approved, sink);
        } catch (IllegalStateException e) {
            throw new StudioException(409, "NOT_SUSPENDED", e.getMessage());
        }
    }

    DemoUser testUser(DemoUser developer, String asUser) {
        String id = asUser == null || asUser.isBlank() ? developer.id() : asUser;
        return DemoDirectory.findUser(id)
                .filter(u -> u.tenantId().equals(developer.tenantId()))
                .orElseThrow(() -> new StudioException(400, "BAD_REQUEST", "测试用户 " + id + " 不在本租户"));
    }

    private String userToken(DemoUser user) {
        return services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles()));
    }

    // ── 评测运行 ──

    /** 被测内容的哈希：去掉版本号，草稿评测通过后发布出的同内容版本也算通过（晋级门禁据此判断） */
    public static String contentHash(Asset asset) {
        ObjectNode tree = (ObjectNode) Json.toTree(asset);
        ((ObjectNode) tree.path("metadata")).remove("version");
        return CanonicalJson.hashOf(CanonicalJson.of(tree));
    }

    public record EvalRunView(
            String id,
            String status,
            int total,
            int passed,
            double minPassRate,
            double passRate,
            List<EvalRunner.CaseResult> cases) {}

    public EvalRunView runEvals(DemoUser developer, Draft draft) {
        if (draft.evalYaml() == null) throw new StudioException(409, "NO_EVALS", "还没有评测集，请先生成评测集初稿");
        Asset agent = parse(draft.yaml(), "草稿");
        EvalSuiteAsset suite = (EvalSuiteAsset) parse(draft.evalYaml(), "评测集");
        String target = agent.metadata().namespace() + "/" + agent.metadata().name();
        EvalRun run = evalRuns.start(
                developer.tenantId(),
                suite.metadata().namespace() + "/" + suite.metadata().name(),
                target,
                contentHash(agent),
                developer.id(),
                suite.spec().minPassRate());
        List<EvalRunner.CaseResult> results = new ArrayList<>();
        for (EvalCase c : suite.spec().cases()) {
            List<RunEvent> events = new CopyOnWriteArrayList<>();
            var outcome = test(developer, draft, c.question(), c.user(), null, events::add);
            var r = outcome.result();
            var caseRun = new EvalRunner.CaseRun(r.text(), r.output(), events, r.status(), r.error());
            results.add(EvalRunner.check(c, caseRun, outcome.runId(), outcome.traceId(), judge));
        }
        int passed =
                (int) results.stream().filter(EvalRunner.CaseResult::passed).count();
        double rate = results.isEmpty() ? 0 : (double) passed / results.size();
        String status = rate >= suite.spec().minPassRate() ? "passed" : "failed";
        evalRuns.finish(run, results.size(), passed, status, EvalRunner.toJson(results));
        return new EvalRunView(
                run.id(),
                status,
                results.size(),
                passed,
                suite.spec().minPassRate(),
                Math.round(rate * 1000) / 1000.0,
                results);
    }
}

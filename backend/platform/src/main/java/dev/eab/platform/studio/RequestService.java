package dev.eab.platform.studio;

import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.assist.NotificationStore;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.RegistryService.AssetSummary;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.platform.studio.StudioStores.DraftStore;
import dev.eab.platform.studio.StudioStores.RequestPlanRow;
import dev.eab.platform.studio.StudioStores.RequestPlanStore;
import dev.eab.runtime.Bm25;
import dev.eab.spec.Json;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.EvalSuiteAsset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 扩展需求（DevRequest）的处理闭环（主提示词 10.3 节）：开发者读取需求与自动附带的业务上下文，
 * 生成六步方案卡（模型生成，模型不可用或输出不合格时退回检索兜底），方案可编辑、可重新生成；采纳方案把资产
 * 草稿落成工作区草稿；发布完成后标记交付并通知提出人。需求本体是注册中心里的 DevRequest 资产，方案卡是
 * 工作区里的工作材料，不进注册中心。
 */
public final class RequestService {

    private static final Logger LOG = LoggerFactory.getLogger(RequestService.class);

    static final int MAX_INTENT = 200;
    static final String NAMESPACE_SUFFIX = ".requests";

    private final PlatformServices services;
    private final DraftStore drafts;
    private final RequestPlanStore plans;
    private final NotificationStore notifications;
    private final ChatModel model;
    private final String modelName;

    public RequestService(
            PlatformServices services,
            DraftStore drafts,
            RequestPlanStore plans,
            NotificationStore notifications,
            ChatModel model,
            String modelName) {
        this.services = services;
        this.drafts = drafts;
        this.plans = plans;
        this.notifications = notifications;
        this.model = model;
        this.modelName = modelName;
    }

    /** 需求详情：详情页与方案生成共用的完整读取 */
    public record RequestView(
            String ref,
            String displayName,
            String originalText,
            String requester,
            String requesterName,
            String status,
            String createdAt,
            String updatedAt,
            List<String> supplements,
            Map<String, Object> context,
            Map<String, Object> plan) {}

    /** 从注册中心读到的一份需求；createdAt 取最早版本的入库时间 */
    private record Loaded(String tenantId, AssetSummary latest, String createdAt, ObjectNode asset) {

        String ref() {
            return latest.namespace() + "/" + latest.name();
        }

        String spec(String field) {
            return asset.path("spec").path(field).asString("");
        }

        String status() {
            return spec("status");
        }

        String displayName() {
            return asset.path("metadata").path("displayName").asString(latest.name());
        }

        List<String> supplements() {
            List<String> result = new ArrayList<>();
            for (JsonNode node : asset.path("spec").path("supplements")) {
                if (!node.asString("").isBlank()) result.add(node.asString(""));
            }
            return result;
        }
    }

    private Loaded load(String tenantId, String namespace, String name) {
        if (!namespace.equals("tenant." + tenantId + NAMESPACE_SUFFIX)) {
            throw new StudioException(403, "FORBIDDEN", "只能处理本租户的扩展需求");
        }
        var view = services.registry().view(Viewer.tenant(tenantId), namespace, name, null);
        if (!view.ok()) throw new StudioException(404, "NOT_FOUND", "扩展需求 " + namespace + "/" + name + " 不存在");
        // 提交时间没有单独的字段，取同名资产最早版本的入库时间
        String createdAt = services
                .registry()
                .list(Viewer.tenant(tenantId), new AssetFilter("DevRequest", namespace, null, tenantId, null))
                .stream()
                .filter(s -> s.name().equals(name))
                .min(Comparator.comparing(AssetSummary::version, Versions::compare))
                .map(AssetSummary::updatedAt)
                .orElse(view.value().summary().updatedAt());
        return new Loaded(
                tenantId, view.value().summary(), createdAt, view.value().asset());
    }

    public RequestView view(String tenantId, String namespace, String name) {
        return toView(load(tenantId, namespace, name));
    }

    private RequestView toView(Loaded request) {
        String requester = request.spec("requester");
        String requesterName =
                DemoDirectory.findUser(requester).map(DemoUser::name).orElse(requester);
        JsonNode context = request.asset().path("spec").path("context");
        Map<String, Object> contextView = new LinkedHashMap<>();
        contextView.put("assistant", context.path("assistant").asString(null));
        contextView.put("semanticObjects", Json.MAPPER.convertValue(context.path("semanticObjects"), List.class));
        contextView.put("rules", Json.MAPPER.convertValue(context.path("rules"), List.class));
        return new RequestView(
                request.ref(),
                request.displayName(),
                request.spec("originalText"),
                requester,
                requesterName,
                request.status(),
                request.createdAt(),
                request.latest().updatedAt(),
                request.supplements(),
                contextView,
                storedPlan(request.tenantId(), request.ref()));
    }

    /** 工作区里的方案卡；没有生成过时为 null */
    private Map<String, Object> storedPlan(String tenantId, String requestRef) {
        return plans.get(tenantId, requestRef)
                .map(row -> (Map<String, Object>) Json.MAPPER.readValue(row.plan(), Map.class))
                .orElse(null);
    }

    // ── 方案生成 ──

    /** 生成六步方案卡；首次生成时需求状态从 submitted 变为 in-progress */
    public RequestView generate(DemoUser developer, String namespace, String name) {
        Loaded request = load(developer.tenantId(), namespace, name);
        requireOpen(request);
        RequestPlan plan = generatePlan(developer, request);
        persist(developer.tenantId(), request.ref(), plan);
        if (request.status().equals("submitted")) {
            updateStatus(developer, request, "in-progress", "开始处理：生成方案");
        }
        return view(developer.tenantId(), namespace, name);
    }

    private static void requireOpen(Loaded request) {
        if (List.of("delivered", "rejected").contains(request.status())) {
            throw new StudioException(409, "REQUEST_CLOSED", "需求已" + statusLabel(request.status()) + "，不能再处理");
        }
    }

    private RequestPlan generatePlan(DemoUser developer, Loaded request) {
        Map<String, Object> answer = StudioRecommender.callModel(
                model, modelName, developer.id(), systemPrompt(), userPrompt(request), "生成方案调用模型失败，退回检索兜底");
        if (answer != null) {
            RequestPlan parsed = RequestPlan.parse(answer);
            if (parsed != null) {
                // 结论来源与时间由服务端确定，不信任模型输出
                return new RequestPlan(
                        "model",
                        IsoTime.now(services.clock()),
                        parsed.clarifications(),
                        parsed.fitGap(),
                        parsed.summary(),
                        parsed.assets(),
                        parsed.drafts(),
                        parsed.evalSuite());
            }
            LOG.warn("方案卡输出不合格，退回检索兜底：{}", request.ref());
        }
        return fallback(request);
    }

    private String systemPrompt() {
        return "你是 ERP Agent Builder Studio 的方案助手。业务用户向平台提交了一个扩展需求，开发者点“生成方案”，"
                + "你产出六步方案卡。只输出一个 JSON 对象，不要输出别的文字，字段与含义：\n"
                + "{\n"
                + "  \"clarifications\": [{\"question\": \"需要向提出人澄清的问题\", \"options\": [\"选项\"]}],\n"
                + "  \"fitGap\": {\"verdict\": \"satisfied|extend|new\", \"score\": 0.0~1.0, "
                + "\"matched\": [{\"ref\": \"匹配到的能力 ref\", \"reason\": \"覆盖了什么、缺什么\"}], "
                + "\"reason\": \"三档结论的依据\"},\n"
                + "  \"summary\": {\"user\": \"目标用户\", \"scenario\": \"业务场景\", \"metrics\": [\"衡量指标\"], "
                + "\"boundaries\": \"边界与硬约束\"},\n"
                + "  \"assets\": [{\"action\": \"reuse|create\", \"kind\": \"资产 kind\", \"ref\": \"资产 ref\", \"note\": \"为什么\"}],\n"
                + "  \"drafts\": [{\"kind\": \"Skill|AgentExtension\", \"yaml\": \"完整资产 YAML 草稿\"}],\n"
                + "  \"evalSuite\": {\"name\": \"评测集名\", \"yaml\": \"EvalSuite 资产 YAML\"}\n"
                + "}\n"
                + "要求：先判断标品是否已满足需求；扩展标品可行时优先扩展而不是新建 Agent；资产草稿的 namespace "
                + "放在本租户命名空间（tenant.<租户>.skills、tenant.<租户>.<域>）；硬约束写成规则编号或确认点，"
                + "不写进口径之外的地方；没有需要澄清的问题时 clarifications 给空数组；评分与结论要有依据。";
    }

    private String userPrompt(Loaded request) {
        StringBuilder user = new StringBuilder("任务：生成扩展方案\n");
        user.append("需求：").append(request.spec("originalText")).append('\n');
        for (String supplement : request.supplements()) {
            user.append("补充说明：").append(supplement).append('\n');
        }
        JsonNode context = request.asset().path("spec").path("context");
        if (!context.path("semanticObjects").isEmpty()) {
            user.append("已附带的语义对象：")
                    .append(String.join("、", toStrings(context.path("semanticObjects"))))
                    .append('\n');
        }
        if (!context.path("rules").isEmpty()) {
            user.append("已附带的规则：")
                    .append(String.join("、", toStrings(context.path("rules"))))
                    .append('\n');
        }
        user.append("候选能力：").append(Json.MAPPER.writeValueAsString(candidates(request)));
        return user.toString();
    }

    private static List<String> toStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(v -> values.add(v.asString("")));
        return values;
    }

    /** 给模型的能力候选：Agent（含路由示例）、语义包的对象与规则、BO 工具 */
    private Map<String, Object> candidates(Loaded request) {
        StudioCatalog catalog = StudioCatalog.build(services.registry(), request.tenantId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agents", catalog.agents());
        result.put(
                "semanticPacks",
                catalog.semanticPacks().stream()
                        .map(p -> Map.of(
                                "ref",
                                p.ref(),
                                "objects",
                                p.objects(),
                                "rules",
                                p.rules().stream()
                                        .map(r -> Map.of("id", r.id(), "description", String.valueOf(r.description())))
                                        .toList()))
                        .toList());
        result.put("tools", catalog.tools());
        return result;
    }

    /** 模型不可用或输出不合格时的检索兜底：只有能力匹配，不含草稿，明确提示重新生成 */
    private RequestPlan fallback(Loaded request) {
        String intent = request.spec("originalText");
        List<Bm25.Passage> passages = new ArrayList<>();
        for (StudioCatalog.Item agent :
                StudioCatalog.build(services.registry(), request.tenantId()).agents()) {
            passages.add(new Bm25.Passage(agent.ref(), agent.name(), agent.text()));
        }
        List<RequestPlan.FitGap.Matched> matched = Bm25.search(passages, intent, 3).stream()
                .map(h -> new RequestPlan.FitGap.Matched(h.passage().source(), "按需求文本检索到的相近能力，匹配度 " + h.score()))
                .toList();
        return new RequestPlan(
                "retrieval",
                IsoTime.now(services.clock()),
                List.of(),
                new RequestPlan.FitGap("new", 0, matched, "模型不可用或输出不合格，这里只按需求文本检索相近能力；接入模型后重新生成可得到完整方案"),
                new RequestPlan.Summary("", intent, List.of(), String.join("；", request.supplements())),
                List.of(),
                List.of(),
                null);
    }

    /** 保存开发者修改后的方案卡 */
    public RequestView save(DemoUser developer, String namespace, String name, Map<String, Object> body) {
        Loaded request = load(developer.tenantId(), namespace, name);
        requireOpen(request);
        RequestPlan plan = body == null ? null : RequestPlan.parse(body);
        if (plan == null) {
            throw new StudioException(422, "PLAN_INVALID", "方案卡不完整：请检查 fitGap、summary、drafts 等字段");
        }
        persist(developer.tenantId(), request.ref(), plan);
        return view(developer.tenantId(), namespace, name);
    }

    private void persist(String tenantId, String requestRef, RequestPlan plan) {
        plans.save(tenantId, requestRef, Json.MAPPER.writeValueAsString(plan.toMap()));
    }

    // ── 采纳：把方案里的资产草稿落成工作区草稿 ──

    public record AdoptedDraft(String id, String kind, String ref, String name) {}

    /**
     * 采纳是幂等的：上次采纳创建的草稿都还在时直接返回；有草稿被删掉时整批重建（工作区草稿允许重名，
     * 陈旧的草稿由开发者自己清理）。
     */
    public List<AdoptedDraft> adopt(DemoUser developer, String namespace, String name) {
        String tenantId = developer.tenantId();
        Loaded request = load(tenantId, namespace, name);
        requireOpen(request);
        RequestPlanRow row = plans.get(tenantId, request.ref()).orElse(null);
        RequestPlan plan = row == null ? null : RequestPlan.parse(storedPlan(tenantId, request.ref()));
        if (plan == null) throw new StudioException(409, "NO_PLAN", "还没有生成方案，先点“生成方案”");
        if (plan.drafts().isEmpty()) {
            throw new StudioException(422, "NO_DRAFTS", "方案里没有资产草稿（检索兜底的方案不含草稿），请重新生成");
        }
        List<Draft> adopted = row.adopted().stream()
                .map(id -> drafts.get(tenantId, id))
                .flatMap(java.util.Optional::stream)
                .toList();
        if (adopted.size() == plan.drafts().size()) {
            return summaries(adopted);
        }
        List<Draft> created = new ArrayList<>();
        String intent = request.spec("originalText");
        String shortIntent = intent.length() > MAX_INTENT ? intent.substring(0, MAX_INTENT) + "…" : intent;
        for (RequestPlan.DraftCard card : plan.drafts()) {
            Asset asset = StudioService.parse(card.yaml(), "资产草稿");
            if (!asset.kind().equals(card.kind())) {
                throw new StudioException(
                        422, "DRAFT_INVALID", "草稿的 kind 是 " + asset.kind() + "，方案里标的是 " + card.kind());
            }
            if (!asset.metadata().namespace().startsWith("tenant." + tenantId + ".")) {
                throw new StudioException(
                        422,
                        "NAMESPACE_FORBIDDEN",
                        "草稿 " + asset.versionedRef() + " 不在本租户命名空间里，请检查 metadata.namespace");
            }
            created.add(drafts.create(
                    tenantId,
                    developer.id(),
                    asset.kind(),
                    asset.metadata().namespace(),
                    asset.metadata().name(),
                    shortIntent,
                    card.yaml()));
        }
        RequestPlan.EvalSuiteCard evalSuite = plan.evalSuite();
        if (evalSuite != null) {
            Draft extension = created.stream()
                    .filter(d -> d.kind().equals("AgentExtension"))
                    .findFirst()
                    .orElseThrow(
                            () -> new StudioException(422, "NO_EXTENSION", "评测集草稿要挂到 AgentExtension 草稿上，方案里没有扩展草稿"));
            Asset suite = StudioService.parse(evalSuite.yaml(), "评测集");
            if (!(suite instanceof EvalSuiteAsset)) {
                throw new StudioException(422, "DRAFT_INVALID", "评测集草稿的 kind 应为 EvalSuite");
            }
            drafts.update(
                    extension,
                    extension.namespace(),
                    extension.name(),
                    extension.step(),
                    extension.yaml(),
                    evalSuite.yaml());
        }
        plans.markAdopted(
                tenantId, request.ref(), created.stream().map(Draft::id).toList());
        return summaries(created);
    }

    private static List<AdoptedDraft> summaries(List<Draft> drafts) {
        return drafts.stream()
                .map(d -> new AdoptedDraft(d.id(), d.kind(), d.ref(), d.name()))
                .toList();
    }

    // ── 交付与拒绝 ──

    /** 发布完成后标记交付，并通知提出人重新提问 */
    public RequestView deliver(DemoUser developer, String namespace, String name, String summary) {
        Loaded request = load(developer.tenantId(), namespace, name);
        requireOpen(request);
        updateStatus(developer, request, "delivered", "交付" + noteOf(summary));
        notifyRequester(
                request,
                "你提交的扩展需求已交付",
                "「"
                        + request.displayName()
                        + "」已经交付"
                        + (summary == null || summary.isBlank() ? "" : "：" + summary)
                        + "。回到统一入口重新提问即可使用新能力。",
                "delivered");
        return view(developer.tenantId(), namespace, name);
    }

    public RequestView reject(DemoUser developer, String namespace, String name, String reason) {
        Loaded request = load(developer.tenantId(), namespace, name);
        requireOpen(request);
        updateStatus(developer, request, "rejected", "拒绝" + noteOf(reason));
        notifyRequester(
                request,
                "你提交的扩展需求没有通过评估",
                "「"
                        + request.displayName()
                        + "」"
                        + (reason == null || reason.isBlank() ? "没有通过评估" : "没有通过评估：" + reason),
                "rejected");
        return view(developer.tenantId(), namespace, name);
    }

    private static String noteOf(String note) {
        return note == null || note.isBlank() ? "" : "：" + note;
    }

    private void notifyRequester(Loaded request, String title, String body, String decision) {
        String requester = request.spec("requester");
        if (requester.isBlank()) return;
        notifications.add(request.tenantId(), requester, title, body, request.ref() + ":" + decision, null, null);
    }

    /** 需求状态变更写成新版本：已有版本不可改，升补丁版本号 */
    private void updateStatus(DemoUser developer, Loaded request, String status, String note) {
        ObjectNode updated = request.asset().deepCopy();
        ((ObjectNode) updated.path("metadata"))
                .put("version", bump(request.latest().version()));
        ((ObjectNode) updated.path("spec")).put("status", status);
        var result =
                services.registry().put(Viewer.tenant(developer.tenantId()), StudioDraftComposer.write(updated), note);
        if (!result.ok()) {
            String message = result instanceof dev.eab.platform.registry.RegistryResult.Err<?> err
                    ? err.error().message()
                    : "未知原因";
            throw new StudioException(422, "REQUEST_INVALID", "扩展需求没有保存：" + message);
        }
    }

    static String bump(String version) {
        String[] parts = version.split("\\.");
        int patch = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        return parts[0] + "." + (parts.length >= 2 ? parts[1] : "0") + "." + (patch + 1);
    }

    static String statusLabel(String status) {
        return switch (status) {
            case "submitted" -> "提交";
            case "in-progress" -> "处理中";
            case "delivered" -> "交付";
            case "rejected" -> "拒绝";
            default -> status;
        };
    }
}

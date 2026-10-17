package dev.eab.platform.assist;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.a2a.RemoteRuns;
import dev.eab.platform.identity.AgentScope;
import dev.eab.platform.policy.PolicyController;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioStores;
import dev.eab.policy.PolicyEngine;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.policy.PolicyInputs;
import dev.eab.runtime.AgentRuntime;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.runtime.routing.Catalog;
import dev.eab.runtime.routing.Catalog.Entry;
import dev.eab.runtime.routing.Router;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一入口（主提示词第 9 节）：两级编排，入口 → 岗位助手 → Agent（或导航场景）。每一级先召回候选，再按权限过滤
 * （岗位助手过鉴权点 1；Agent 要求用户在 ERP 里至少有它所需的一项业务权限），最后决策；置信度低时请用户在两个选项里选，
 * 找不到合适的能力时给出“提交扩展需求”。路由的候选、分数、过滤原因和理由写进本次运行的追踪。
 */
public final class AssistService {

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final Router router;

    private final StudioStores.DeploymentStore deployments;

    public AssistService(PlatformServices services, RuntimeService runtime, Router router) {
        this(services, runtime, router, null);
    }

    /**
     * @param deployments 环境部署；给出时，租户层的岗位助手和 Agent 只有部署到 prod 才进入能力目录，并按 prod 的版本运行（ADR 0010）
     */
    public AssistService(
            PlatformServices services,
            RuntimeService runtime,
            Router router,
            StudioStores.DeploymentStore deployments) {
        this.services = services;
        this.runtime = runtime;
        this.router = router;
        this.deployments = deployments;
    }

    /**
     * 一次提问。
     *
     * @param target 用户在澄清选项里点选的候选（namespace/name），没有时为 null
     */
    public record AssistRequest(DemoUser user, String token, String message, String sessionId, String target) {}

    /**
     * 结果。
     *
     * @param kind run（交给 Agent）、navigation、clarify、gap
     */
    public record AssistOutcome(
            String kind, String runId, String traceId, String sessionId, Map<String, Object> payload) {}

    /** 本租户可见的能力目录：已发布的岗位助手、Agent，以及它们挂载的语义包；租户层的只收录部署在 prod 的版本 */
    public List<Entry> catalog(String tenantId) {
        var published = services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        if (deployments == null) return Catalog.build(published);
        var resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        List<Asset> visible = new ArrayList<>();
        for (Asset asset : published) {
            boolean routable = asset instanceof Asset.AgentAsset || asset instanceof Asset.AssistantAsset;
            if (!routable || asset.layer() != Layer.tenant) {
                visible.add(asset);
                continue;
            }
            var m = asset.metadata();
            deployments
                    .active(tenantId, "prod", m.namespace(), m.name())
                    .flatMap(d -> d.version().equals(m.version())
                            ? java.util.Optional.of(asset)
                            : resolver.get(m.namespace(), m.name(), d.version()))
                    .ifPresent(visible::add);
        }
        return Catalog.build(visible);
    }

    /** 租户层 Agent 按能力目录里的版本运行（prod 部署的版本），标品 Agent 取最新发布版 */
    static String runRef(Entry entry) {
        return entry.ref().startsWith("tenant.") && entry.version() != null
                ? entry.ref() + "@" + entry.version()
                : entry.ref();
    }

    public AssistOutcome assist(AssistRequest request, RunEvents.Sink sink) {
        DemoUser user = request.user();
        List<Entry> catalog = catalog(user.tenantId());
        Map<String, String> denials = new HashMap<>();
        Router.Filter filter = entry -> denials.computeIfAbsent(entry.ref(), ref -> check(user, entry));
        List<Map<String, Object>> levels = new ArrayList<>();

        Entry pending = request.target() == null
                ? RemoteRuns.pendingRemote(runtime, user, request.sessionId(), catalog)
                        .orElse(null)
                : null;
        if (pending != null) {
            Router.Level resumed = new Router.Level(
                    "route",
                    List.of(new Router.Candidate(pending, 1.0, "会话里有等待补充信息的外部 Agent 任务", null)),
                    new Router.Decision(pending, 1.0, "继续上一轮：" + pending.displayName() + " 在等待补充信息", "session"),
                    List.of());
            levels.add(describe(1, resumed));
            return finish(request, sink, levels, resumed, null);
        }

        List<Entry> first = catalog.stream()
                .filter(e -> e.kind().equals("assistant") || e.direct())
                .toList();
        Router.Level level1 = routeLevel(request.message(), levelOneTarget(request.target(), first), first, filter);
        levels.add(describe(1, level1));
        if (!level1.outcome().equals("route")) return finish(request, sink, levels, level1, null);

        Entry chosen = level1.decision().chosen();
        Entry assistant = null;
        Router.Level last = level1;
        if (chosen.kind().equals("assistant")) {
            assistant = chosen;
            List<Entry> members = new ArrayList<>(catalog.stream()
                    .filter(e -> (e.kind().equals("agent") || e.kind().equals("remote"))
                            && chosen.members().contains(e.ref()))
                    .toList());
            members.addAll(Catalog.NAVIGATION);
            last = routeLevel(request.message(), request.target(), members, filter);
            levels.add(describe(2, last));
            if (!last.outcome().equals("route")) return finish(request, sink, levels, last, assistant);
        }
        return finish(request, sink, levels, last, assistant);
    }

    /**
     * 第一级的点选目标：点选的是岗位助手或可直达的 Agent 时就是它；点选的是第二级的成员 Agent 时，
     * 是第一个列出它的岗位助手，这样不会因为重新路由第一级而丢掉用户的选择。
     */
    static String levelOneTarget(String target, List<Entry> first) {
        if (target == null) return null;
        if (first.stream().anyMatch(e -> e.ref().equals(target))) return target;
        return first.stream()
                .filter(e -> e.kind().equals("assistant") && e.members().contains(target))
                .map(Entry::ref)
                .findFirst()
                .orElse(null);
    }

    /** 用户点了澄清选项时，这一级直接选中它（仍然要过权限过滤） */
    private Router.Level routeLevel(String message, String targetRef, List<Entry> entries, Router.Filter filter) {
        if (targetRef != null) {
            Entry target = entries.stream()
                    .filter(e -> e.ref().equals(targetRef))
                    .findFirst()
                    .orElse(null);
            if (target != null) {
                String denied = filter.apply(target);
                var candidate = new Router.Candidate(target, 0, "用户在澄清选项里选择", denied);
                if (denied != null) return new Router.Level("gap", List.of(candidate), null, List.of());
                return new Router.Level(
                        "route",
                        List.of(candidate),
                        new Router.Decision(target, 1.0, "用户在澄清选项里选择了 " + target.displayName(), "user"),
                        List.of());
            }
        }
        return router.route(message, entries, filter);
    }

    /** 权限过滤：岗位助手过鉴权点 1；Agent 要求用户至少有它所需的一项业务权限；导航场景不限制（页面本身按数据权限展示） */
    String check(DemoUser user, Entry entry) {
        return switch (entry.kind()) {
            case "assistant" -> {
                List<String> roles = services.tenantSettings()
                        .get(user.tenantId())
                        .assistantRoles()
                        .getOrDefault(entry.ref(), List.of());
                Map<String, Object> facts = PolicyInputs.entry(
                        new PolicyInputs.SubjectFacts(
                                user.id(), user.tenantId(), user.roles(), DemoDirectory.permittedOperations(user)),
                        user.tenantId(),
                        new PolicyInputs.Assistant(entry.ref(), roles));
                Decision decision = PolicyEngine.evaluate(
                        services.policyRules(), "entry", facts, services.clock().instant());
                PolicyController.recordDecision(
                        services.audit(),
                        decision,
                        false,
                        user.id(),
                        user.tenantId(),
                        user.id(),
                        entry.ref(),
                        null,
                        null,
                        "on-behalf");
                yield decision.outcome().equals("deny") ? decision.reason() + "（规则 " + decision.ruleId() + "）" : null;
            }
            case "agent" -> agentAccess(user, entry);
            default -> null;
        };
    }

    private String agentAccess(DemoUser user, Entry entry) {
        EffectiveDefinition definition;
        try {
            definition = runtime.assemble(entry.ref(), user.tenantId());
        } catch (RuntimeException e) {
            return "无法组装：" + e.getMessage();
        }
        List<String> declared = AgentScope.declaredBoOperations(
                definition, services.registry().resolverFor(Viewer.tenant(user.tenantId())));
        if (declared.isEmpty()) return null;
        List<String> permitted = DemoDirectory.permittedOperations(user);
        return declared.stream().anyMatch(permitted::contains)
                ? null
                : "你在 ERP 里没有 " + entry.displayName() + " 需要的业务权限";
    }

    static Map<String, Object> describe(int index, Router.Level level) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Router.Candidate c : level.candidates()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ref", c.entry().ref());
            item.put("name", c.entry().displayName());
            item.put("kind", c.entry().kind());
            if (c.entry().kind().equals("remote")) item.put("action", RemoteRuns.ACTION);
            item.put("score", c.score());
            item.put("matched", c.matched());
            if (c.filtered() != null) item.put("filtered", c.filtered());
            candidates.add(item);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", index);
        map.put("outcome", level.outcome());
        map.put("candidates", candidates);
        if (level.decision() != null && level.decision().chosen() != null) {
            map.put(
                    "decision",
                    Map.of(
                            "ref", level.decision().chosen().ref(),
                            "name", level.decision().chosen().displayName(),
                            "confidence", level.decision().confidence(),
                            "reason", level.decision().reason(),
                            "decidedBy", level.decision().decidedBy()));
        }
        return map;
    }

    private AssistOutcome finish(
            AssistRequest request,
            RunEvents.Sink sink,
            List<Map<String, Object>> levels,
            Router.Level last,
            Entry assistant) {
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("query", request.message());
        routing.put("levels", levels);
        if (assistant != null)
            routing.put("assistant", Map.of("ref", assistant.ref(), "name", assistant.displayName()));
        Entry target = last.outcome().equals("route") ? last.decision().chosen() : null;
        if (target != null) {
            routing.put("target", target.ref());
            routing.put("targetName", target.displayName());
            routing.put("confidence", last.decision().confidence());
            routing.put("reason", last.decision().reason());
            routing.put("decidedBy", last.decision().decidedBy());
        }
        routing.put("outcome", last.outcome());

        if (target != null && target.kind().equals("remote")) {
            routing.put("action", RemoteRuns.ACTION);
            var outcome = new RemoteRuns(services, runtime)
                    .start(request.user(), request.sessionId(), request.message(), target, assistant, routing, sink);
            return new AssistOutcome(
                    "run",
                    outcome.runId(),
                    outcome.traceId(),
                    outcome.sessionId(),
                    Map.of("status", outcome.result().status()));
        }
        if (target != null && target.kind().equals("agent")) {
            var outcome = runtime.start(
                    new RuntimeService.StartRequest(
                            request.user(),
                            request.token(),
                            runRef(target),
                            request.message(),
                            request.sessionId(),
                            null,
                            routing),
                    sink);
            emitCapabilityGap(sink, request, assistant, target, outcome.result());
            return new AssistOutcome(
                    "run",
                    outcome.runId(),
                    outcome.traceId(),
                    outcome.sessionId(),
                    Map.of("status", outcome.result().status()));
        }

        Map<String, Object> payload;
        String kind;
        if (target != null) {
            kind = "navigation";
            payload = navigate(request, target);
        } else if (last.outcome().equals("clarify")) {
            kind = "clarify";
            List<Map<String, Object>> options = new ArrayList<>();
            for (Entry option : last.options()) {
                options.add(Map.of(
                        "ref",
                        option.ref(),
                        "name",
                        option.displayName(),
                        "description",
                        option.description() == null ? "" : option.description()));
            }
            payload = Map.of("message", "这个问题可以由两个能力处理，请选择一个：", "options", options);
        } else {
            kind = "gap";
            boolean allFiltered = levels.stream()
                    .flatMap(l -> ((List<?>) l.get("candidates")).stream())
                    .map(c -> (Map<?, ?>) c)
                    .anyMatch(c -> c.containsKey("filtered"));
            payload = Map.of(
                    "message",
                    allFiltered ? "能处理这个问题的助手没有对你开放，或你在 ERP 里没有相应的业务权限。" : "现有的助手和 Agent 都不能处理这个问题。",
                    "suggestion",
                    "可以提交扩展需求，由开发者评估后扩展能力。",
                    "originalText",
                    request.message(),
                    "assistant",
                    assistant == null ? "" : assistant.ref());
        }
        var recorded = runtime.recordRouterRun(
                request.user(), request.sessionId(), request.message(), routing, kind, payload, sink);
        return new AssistOutcome(kind, recorded.runId(), recorded.traceId(), recorded.sessionId(), payload);
    }

    private static final Pattern PO = Pattern.compile("PO-\\d{4}-\\d{4}");

    /**
     * Agent 的结构化输出标记了能力缺口时，在运行事件里补一张“提交扩展需求”卡片：Agent 自己的回复已经说明了缺什么，
     * 卡片把原话和岗位助手带给扩展需求的提交入口（主提示词第 15 节故事 E）。
     */
    private static void emitCapabilityGap(
            RunEvents.Sink sink, AssistRequest request, Entry assistant, Entry target, AgentRuntime.RunResult result) {
        if (result == null || result.output() == null) return;
        if (!Boolean.TRUE.equals(result.output().get("capabilityGap"))) return;
        String missing = String.valueOf(result.output().getOrDefault("missingCapability", ""));
        sink.accept(event(
                "gap",
                "message",
                target.displayName() + " 还没有完成这件事的能力" + (missing.isBlank() ? "" : "：" + missing),
                "suggestion",
                "可以提交扩展需求，由开发者评估后扩展能力。",
                "originalText",
                request.message(),
                "assistant",
                assistant == null ? "" : assistant.ref()));
    }

    /** 导航型交互：找到用户问的单据或供应商，返回 DemoERP 只读页面的深链接卡片（带 10 分钟有效的页面令牌） */
    @SuppressWarnings("unchecked")
    Map<String, Object> navigate(AssistRequest request, Entry target) {
        DemoUser user = request.user();
        String page = target.ref().substring(target.ref().indexOf(':') + 1);
        String id = null;
        String title = null;
        if (page.equals("purchase-order")) {
            Matcher m = PO.matcher(request.message());
            if (m.find()) {
                id = m.group();
                title = "采购订单 · " + id;
            }
        } else {
            // 只在用户有权查看的供应商里找：名称出现在问题里的，取最长的那个
            ToolOutcome found = runtime.callPlatformTool(
                    RuntimeService.NAVIGATOR_AGENT, user, request.token(), "supplier_list", Map.of("limit", 200));
            if (found.ok() && found.data() instanceof Map<?, ?> data && data.get("items") instanceof List<?> items) {
                Map<String, Object> best = ((List<Map<String, Object>>) items)
                        .stream()
                                .filter(s -> request.message().contains(String.valueOf(s.get("name"))))
                                .max(Comparator.comparingInt(
                                        s -> String.valueOf(s.get("name")).length()))
                                .orElse(null);
                if (best != null) {
                    id = String.valueOf(best.get("id"));
                    title = "供应商 · " + best.get("name");
                }
            }
        }
        if (id == null) {
            return Map.of(
                    "message",
                    page.equals("purchase-order")
                            ? "没有在问题里找到采购订单号，请写明订单号，例如 PO-2026-0117。"
                            : "没有找到你有权查看的、名称出现在问题里的供应商。");
        }
        String token = services.tokens().issuePage(user.id(), user.tenantId());
        String segment = page.equals("supplier") ? "suppliers" : "purchase-orders";
        String url = "/erp/pages/" + segment + "/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?t=" + token;
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("title", title);
        card.put("url", url);
        card.put("id", id);
        card.put("description", "DemoERP 只读页面，按你的数据权限展示，链接 10 分钟内有效");
        return Map.of("message", "已为你找到 " + title + "，点击打开：", "card", card);
    }

    /** 统一入口推送事件用 */
    static RunEvent event(String type, Object... keyValues) {
        return RunEvent.of(type, RuntimeService.ROUTER_AGENT, keyValues);
    }
}

package dev.eab.runtime;

import dev.eab.assembler.Assembler;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.policy.PolicyEngine;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.model.GatewayConfig;
import dev.eab.runtime.model.ModelGateway;
import dev.eab.runtime.model.ScriptedChatModel;
import dev.eab.runtime.model.Scripts;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.InMemoryAssetResolver;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.TraceStore;
import dev.eab.tracing.Tracer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 运行时测试共用：内存实现的端口、测试用的标品资产、脚本化模型 */
final class TestRuntime {

    static final String ANALYST = "vendor.pur/analyst";

    final List<Ports.BoCall> boCalls = new CopyOnWriteArrayList<>();
    final List<Decision> decisions = new CopyOnWriteArrayList<>();
    final List<String> alerts = new CopyOnWriteArrayList<>();
    final List<String> notifications = new CopyOnWriteArrayList<>();
    final List<String> writes = new CopyOnWriteArrayList<>();
    final List<List<String>> session = new CopyOnWriteArrayList<>();
    final List<String> longTerm = new ArrayList<>();
    final TraceStore.InMemory traces = new TraceStore.InMemory();
    List<String> delegationScope = List.of();

    /** 按 BO 操作返回的响应，默认返回空列表 */
    Function<Ports.BoCall, Ports.BoResponse> boResponder = call -> new Ports.BoResponse(
            200, Map.of("items", List.of(), "total", 0, "filtered", true, "visibleScope", List.of("华东")));

    final InMemoryAssetResolver resolver;
    final List<Asset.AgentExtensionAsset> extensions = new ArrayList<>();
    final AgentRuntime runtime;

    TestRuntime(String scriptYaml, Asset... extra) {
        List<Asset> assets = new ArrayList<>(List.of(
                boToolSource(), supplierLookup(), createFollowupTask(), analyst(), helper(), boss(), recursive()));
        assets.addAll(List.of(extra));
        resolver = InMemoryAssetResolver.published(assets.toArray(Asset[]::new));
        List<Scripts.Script> scripts = Scripts.parse(scriptYaml, "test.yaml");
        ModelGateway gateway =
                new ModelGateway(new ScriptedChatModel(scripts, () -> "2026-10-15"), GatewayConfig.scripted());
        RuntimeDeps deps = new RuntimeDeps(
                gateway,
                resolver,
                AssetResolver.PUBLISHED,
                this::assemble,
                (definition, tenant) -> "agent-token:" + definition.provenance().agent(),
                call -> {
                    boCalls.add(call);
                    return boResponder.apply(call);
                },
                new Ports.Policy() {
                    @Override
                    public Decision evaluate(
                            String checkpoint, Map<String, Object> facts, RunContext context, String target) {
                        Decision decision = PolicyEngine.evaluate(PolicyEngine.DEFAULT_RULES, checkpoint, facts);
                        decisions.add(decision);
                        return decision;
                    }

                    @Override
                    public boolean l3Enabled(String tenantId) {
                        return false;
                    }

                    @Override
                    public List<String> delegationScope(String delegationId) {
                        return delegationScope;
                    }
                },
                (connection, tool, args) -> Map.of("mcp", tool),
                (context, connection, method, path, body) -> Map.of("sent", true),
                (context, channel, message) -> notifications.add(channel + ":" + message),
                (source, query, limit) -> List.of(),
                new Ports.Memory() {
                    @Override
                    public List<String> longTerm(String tenantId, String userId, String agent) {
                        return longTerm;
                    }

                    @Override
                    public List<List<String>> session(String sessionId, String tenantId, String userId) {
                        return session;
                    }

                    @Override
                    public void appendSession(
                            String sessionId,
                            String tenantId,
                            String userId,
                            String agent,
                            String utterance,
                            String reply) {
                        session.add(List.of(utterance, reply));
                    }
                },
                new Ports.Alerts() {
                    @Override
                    public void raise(
                            RunContext context,
                            String type,
                            String severity,
                            String message,
                            Map<String, Object> detail) {
                        alerts.add(type);
                    }

                    @Override
                    public int recentWrites(String sessionId, long windowSeconds) {
                        return writes.size();
                    }

                    @Override
                    public void recordWrite(String sessionId, String tool) {
                        writes.add(tool);
                    }
                },
                ref -> Optional.empty(),
                Clock.systemUTC(),
                4);
        runtime = new AgentRuntime(deps);
    }

    EffectiveDefinition assemble(String agent, String tenantId) {
        return Assembler.assemble(
                        new Assembler.Input(agent, tenantId, extensions, List.of(), resolver, AssetResolver.PUBLISHED))
                .definition();
    }

    static final RunContext.User ZHANGMIN = new RunContext.User(
            "zhangmin",
            "张敏",
            "t001",
            "星海制造",
            List.of("purchasing-manager"),
            List.of("supplier_list", "purchase_order_list", "followup_task_create"),
            "采购组织：华东");

    /** 一次运行：事件、结果和追踪 */
    record Run(AgentRuntime.RunResult result, List<RunEvent> events, Journal journal, String traceId) {
        List<String> types() {
            return events.stream().map(RunEvent::type).toList();
        }
    }

    Run run(String agent, String utterance) {
        return run(agent, utterance, "on-behalf", null, new Journal(), "run-1", Tracer.newTraceId());
    }

    Run run(
            String agent,
            String utterance,
            String mode,
            String delegationId,
            Journal journal,
            String runId,
            String traceId) {
        List<RunEvent> events = new CopyOnWriteArrayList<>();
        Tracer tracer = new Tracer(traceId, traces, "t001", Clock.systemUTC());
        var request = new AgentRuntime.RunRequest(
                runId,
                "session-1",
                ZHANGMIN,
                mode,
                delegationId,
                new RunContext.Subject(mode.equals("delegated") ? null : "user-token", delegationId),
                agent,
                utterance);
        var result = runtime.run(request, journal, tracer, events::add);
        return new Run(result, events, journal, traceId);
    }

    /** 用户决定后恢复：日志序列化再反序列化，模拟从数据库读出 */
    Run resume(Run suspended, String utterance, boolean approved) {
        Journal restored =
                new Journal(Journal.fromJson(Journal.toJson(suspended.journal().state())));
        restored.decide(approved);
        return run(ANALYST, utterance, "on-behalf", null, restored, "run-1", suspended.traceId());
    }

    List<SpanRecord> spans(String traceId) {
        return traces.list(traceId);
    }

    // ── 测试资产 ─────────────────────────────────────────

    static Asset boToolSource() {
        return Fixtures.build(
                "ToolSource",
                "demo-erp-bo",
                "vendor.erp.tools",
                Map.of(
                        "type", "bo-api",
                        "connection", "DEMO_ERP",
                        "tools",
                                List.of(
                                        tool("supplier_list", "L0", "query", "GET", "/bo/v1/suppliers"),
                                        tool("supplier_get", "L0", "query", "GET", "/bo/v1/suppliers/{supplierId}"),
                                        tool("purchase_order_list", "L0", "query", "GET", "/bo/v1/purchase-orders"),
                                        tool("followup_task_create", "L2", "write", "POST", "/bo/v1/followup-tasks"))));
    }

    static Map<String, Object> tool(String name, String level, String category, String method, String path) {
        return Map.of(
                "name", name,
                "description", name,
                "writeLevel", level,
                "category", category,
                "operation", Map.of("method", method, "path", path),
                "inputSchema", Map.of("type", "object"));
    }

    static Asset supplierLookup() {
        return Fixtures.build(
                "Skill",
                "supplier-lookup",
                "vendor.pur.skills",
                Map.of(
                        "writeLevel", "L0",
                        "inputSchema", Map.of("type", "object", "required", List.of("supplierName")),
                        "outputSchema", Map.of("type", "object"),
                        "steps",
                                List.of(
                                        Map.of(
                                                "id",
                                                "search",
                                                "type",
                                                "tool",
                                                "tool",
                                                "supplier_list",
                                                "source",
                                                "vendor.erp.tools/demo-erp-bo",
                                                "with",
                                                Map.of("name", "input.supplierName"),
                                                "output",
                                                "found"),
                                        Map.of(
                                                "id",
                                                "pick",
                                                "type",
                                                "transform",
                                                "expression",
                                                "{'suppliers': found.items, 'filtered': found.filtered}"))));
    }

    static Asset createFollowupTask() {
        return Fixtures.build(
                "Skill",
                "create-followup-task",
                "vendor.common.skills",
                Map.of(
                        "writeLevel",
                        "L2",
                        "agentOnly",
                        true,
                        "outputSchema",
                        Map.of("type", "object"),
                        "inputSchema",
                        Map.of("type", "object", "required", List.of("title", "assignee")),
                        "steps",
                        List.of(
                                Map.of(
                                        "id",
                                        "confirm",
                                        "type",
                                        "confirm",
                                        "message",
                                        "为 {{input.relatedObject}} 创建跟进任务“{{input.title}}”"),
                                Map.of(
                                        "id",
                                        "create",
                                        "type",
                                        "tool",
                                        "tool",
                                        "followup_task_create",
                                        "source",
                                        "vendor.erp.tools/demo-erp-bo",
                                        "with",
                                        Map.of(
                                                "title",
                                                "input.title",
                                                "assignee",
                                                "input.assignee",
                                                "relatedObject",
                                                "input.relatedObject",
                                                "dueDate",
                                                "input.dueDate"),
                                        "output",
                                        "task"))));
    }

    static Asset analyst() {
        return Fixtures.build(
                "Agent",
                "analyst",
                "vendor.pur",
                Map.of(
                        "expertise", "分析供应商",
                        "instructions", "【标品机密】先确认供应商，再查订单。",
                        "publicInstructions", "先确认供应商，再查订单。",
                        "model", Map.of("profile", "reasoning"),
                        "tools",
                                List.of(
                                        Map.of("skill", "vendor.pur.skills/supplier-lookup"),
                                        Map.of("skill", "vendor.common.skills/create-followup-task"),
                                        Map.of(
                                                "api",
                                                Map.of(
                                                        "source",
                                                        "vendor.erp.tools/demo-erp-bo",
                                                        "tools",
                                                        List.of("purchase_order_list", "supplier_get"))),
                                        Map.of("builtin", "calculator")),
                        "writePolicy", Map.of("maxLevel", "L2"),
                        "output", Map.of("schema", Map.of("supplier", Map.of("type", "string"))),
                        "routing", Map.of("description", "分析供应商", "examples", List.of("百润精密最近表现怎么样"))));
    }

    static Asset helper() {
        return Fixtures.build(
                "Agent",
                "helper",
                "vendor.pur",
                Map.of(
                        "expertise", "查询订单",
                        "instructions", "查询订单。",
                        "model", Map.of("profile", "fast"),
                        "tools",
                                List.of(Map.of(
                                        "api",
                                        Map.of(
                                                "source",
                                                "vendor.erp.tools/demo-erp-bo",
                                                "tools",
                                                List.of("purchase_order_list")))),
                        "triggers", List.of(Map.of("type", "agent")),
                        "writePolicy", Map.of("maxLevel", "L0"),
                        "routing", Map.of("description", "查询订单")));
    }

    static Asset boss() {
        return Fixtures.build(
                "Agent",
                "boss",
                "vendor.pur",
                Map.of(
                        "expertise", "汇总",
                        "instructions", "把订单查询交给 helper。",
                        "model", Map.of("profile", "reasoning"),
                        "tools", List.of(Map.of("agent", "vendor.pur/helper")),
                        "writePolicy", Map.of("maxLevel", "L0"),
                        "routing", Map.of("description", "汇总")));
    }

    /** 调用自己的 Agent，用于测试调用深度上限 */
    static Asset recursive() {
        return Fixtures.build(
                "Agent",
                "loop",
                "vendor.pur",
                Map.of(
                        "expertise", "递归",
                        "instructions", "继续交给自己。",
                        "model", Map.of("profile", "fast"),
                        "tools", List.of(Map.of("agent", "vendor.pur/loop")),
                        "triggers", List.of(Map.of("type", "agent")),
                        "writePolicy", Map.of("maxLevel", "L0"),
                        "routing", Map.of("description", "递归")));
    }
}

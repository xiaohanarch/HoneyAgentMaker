package dev.eab.platform.a2a;

import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.runtime.RuntimeStores.RunRecord;
import dev.eab.runtime.AgentRuntime;
import dev.eab.runtime.Journal;
import dev.eab.runtime.Ports;
import dev.eab.runtime.RunContext;
import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.routing.Catalog.Entry;
import dev.eab.spec.Json;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 统一入口把问题路由到远程 Agent 时的一次运行（主提示词 8.4 节，执行动作类型 agent-request）：
 * 记一次运行和一条追踪，A2A 请求与响应原文写进 span；对方返回 input-required 时把追问展示给用户，下一句话按 contextId、taskId 续接。
 */
public final class RemoteRuns {

    /** 远程 Agent 场景的执行动作类型，命名对齐 dialog function 的动作 */
    public static final String ACTION = "agent-request";

    /** 没有经过岗位助手时，调用方记为统一入口 */
    static final String ENTRY_CALLER = RuntimeService.ROUTER_AGENT;

    private final PlatformServices services;
    private final RuntimeService runtime;

    public RemoteRuns(PlatformServices services, RuntimeService runtime) {
        this.services = services;
        this.runtime = runtime;
    }

    /**
     * @param assistant 经过的岗位助手，作为 A2A 调用方（对方看到的 act）；直达时为 null
     */
    public RuntimeService.RunOutcome start(
            DemoUser user,
            String sessionId,
            String message,
            Entry target,
            Entry assistant,
            Map<String, Object> routing,
            RunEvents.Sink sink) {
        String runId = "run-" + UUID.randomUUID();
        String traceId = Tracer.newTraceId();
        String session = sessionId == null || sessionId.isBlank() ? "ses-" + UUID.randomUUID() : sessionId;
        String caller = assistant == null ? ENTRY_CALLER : assistant.ref();
        String now = IsoTime.now(services.clock());
        Tracer tracer = new Tracer(traceId, runtime.traces(), user.tenantId(), services.clock());
        var root = tracer.start("外部 Agent · " + target.displayName(), SpanKind.run, null, "run", Map.of());
        root.set("eab.run.id", runId)
                .set("eab.agent", target.ref())
                .set("eab.user.id", user.id())
                .set("eab.tenant.id", user.tenantId())
                .set("eab.action", ACTION);
        sink.accept(RunEvent.of(
                "run_started", target.ref(), "runId", runId, "traceId", traceId, "agent", target.displayName()));
        AgentRuntime.recordRouting(root, routing, sink, target.ref());

        var span = tracer.start("A2A · " + target.displayName(), SpanKind.agent, root, "run/a2a", Map.of());
        sink.accept(RunEvent.of(
                "tool_call", target.ref(), "callId", "a2a", "tool", ACTION, "arguments", Map.of("message", message)));
        RunContext context = new RunContext(
                runId,
                session,
                RuntimeService.runUser(user),
                "on-behalf",
                null,
                new RunContext.Subject(null, null, null),
                caller,
                null,
                null,
                0);
        Ports.RemoteResult result = runtime.remoteAgents().call(context, target.ref(), message);
        span.set("eab.a2a.remote", target.ref())
                .set("eab.a2a.caller", caller)
                .set("eab.a2a.state", result.state())
                .set("eab.a2a.task_id", result.taskId())
                .set("eab.a2a.context_id", result.contextId())
                .set("eab.a2a.exchanges", Json.MAPPER.writeValueAsString(result.exchanges()));
        String text = result.ok() ? result.text() : result.error();
        if (result.ok()) span.end();
        else span.fail(result.error());
        sink.accept(RunEvent.of(
                "tool_result",
                target.ref(),
                "callId",
                "a2a",
                "tool",
                ACTION,
                "ok",
                result.ok(),
                "summary",
                summary(result)));
        String status = result.ok() ? "completed" : "failed";
        root.set("eab.a2a.state", result.state());
        if (result.ok()) root.end();
        else root.fail(result.error());

        Map<String, Object> output = output(result);
        AgentRuntime.streamText(sink, target.ref(), text);
        sink.accept(RunEvent.of("final", target.ref(), "text", text, "output", output, "traceId", traceId));
        var runResult = new AgentRuntime.RunResult(
                status, text, output, null, result.ok() ? null : result.error(), List.of(), 0);
        runtime.runs()
                .insert(new RunRecord(
                        runId,
                        traceId,
                        session,
                        user.tenantId(),
                        user.id(),
                        target.ref(),
                        "on-behalf",
                        null,
                        message,
                        status,
                        Journal.toJson(new Journal().state()),
                        Json.MAPPER.writeValueAsString(runResult),
                        now,
                        IsoTime.now(services.clock())));
        runtime.memory().appendSession(session, user.tenantId(), user.id(), target.ref(), message, text);
        return new RuntimeService.RunOutcome(runId, traceId, session, runResult);
    }

    /**
     * 会话里最近一次外部 Agent 调用停在 input-required 时，返回它在能力目录里的条目；用户的下一句话直接交回给它，
     * 由网关带上对方的 contextId、taskId 续接。
     */
    public static Optional<Entry> pendingRemote(
            RuntimeService runtime, DemoUser user, String sessionId, List<Entry> catalog) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        if (!(runtime.remoteAgents() instanceof RemoteAgentGateway gateway)) return Optional.empty();
        return gateway.tasks()
                .pendingInSession(user.tenantId(), user.id(), sessionId)
                .flatMap(task -> catalog.stream()
                        .filter(e -> e.kind().equals("remote") && e.ref().equals(task.remote()))
                        .findFirst());
    }

    static String summary(Ports.RemoteResult result) {
        if (!result.ok()) return result.errorCode();
        return switch (result.state()) {
            case "input-required" -> "外部 Agent 需要补充信息";
            case "working", "submitted" -> "已转为异步任务，结果到达后推送到通知区";
            default -> "外部 Agent 已返回结果";
        };
    }

    static Map<String, Object> output(Ports.RemoteResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", ACTION);
        output.put("state", result.state());
        if (result.agentName() != null) output.put("agent", result.agentName());
        if (result.taskId() != null) output.put("taskId", result.taskId());
        if (result.contextId() != null) output.put("contextId", result.contextId());
        if (!result.ok()) output.put("error", Map.of("code", result.errorCode(), "message", result.error()));
        return output;
    }
}

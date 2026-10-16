package dev.eab.a2a;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 入站 A2A 请求的执行器：把一条消息交给平台运行（{@link Runner}），按运行结果更新 A2A 任务。
 * 平台的运行等待人工确认时，任务停在 input-required，调用方带着同一个 taskId 回复“确认”或“取消”后继续。
 */
public final class InboundExecutor implements AgentExecutor {

    /**
     * 平台一侧的运行。
     *
     * @param principal 调用上下文里的调用方身份（{@link A2aServer#PRINCIPAL_KEY}）
     * @param sessionId 由 A2A 的 contextId 得到，同一个 contextId 的多轮共享会话记忆
     * @param pendingRunId 上一轮等待确认的运行，没有时为 null
     * @param progress 运行中的进度说明，转成 working 状态的消息
     */
    public interface Runner {
        Reply run(Object principal, String text, String sessionId, String pendingRunId, Consumer<String> progress);
    }

    /**
     * 运行结果。
     *
     * @param state completed、input-required（等待确认或追问）、failed
     * @param runId 平台的运行编号；input-required 时用于续接
     */
    public record Reply(String state, String text, String runId, String traceId) {}

    private final Runner runner;
    private final Map<String, String> pendingRuns = new ConcurrentHashMap<>();

    public InboundExecutor(Runner runner) {
        this.runner = runner;
    }

    /** A2A 的 contextId 对应平台的会话编号 */
    public static String sessionOf(String contextId) {
        return "a2a-" + contextId;
    }

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) {
        if (context.getTask() == null) emitter.submit();
        emitter.startWork();
        Object principal = context.getCallContext().getState().get(A2aServer.PRINCIPAL_KEY);
        String pending = pendingRuns.remove(context.getTaskId());
        Reply reply;
        try {
            reply = runner.run(
                    principal,
                    context.getUserInput(),
                    sessionOf(context.getContextId()),
                    pending,
                    note -> emitter.updateStatus(TaskState.TASK_STATE_WORKING, message(emitter, note)));
        } catch (RuntimeException e) {
            emitter.fail(message(emitter, "平台运行失败：" + e.getMessage()));
            return;
        }
        Map<String, Object> metadata = reply.traceId() == null ? Map.of() : Map.of("eab.traceId", reply.traceId());
        switch (reply.state()) {
            case "input-required" -> {
                if (reply.runId() != null) pendingRuns.put(context.getTaskId(), reply.runId());
                emitter.requiresInput(emitter.newAgentMessage(List.of(new TextPart(reply.text())), metadata));
            }
            case "failed" -> emitter.fail(emitter.newAgentMessage(List.of(new TextPart(reply.text())), metadata));
            default -> {
                emitter.addArtifact(List.of(new TextPart(reply.text())), null, "回复", metadata);
                emitter.complete();
            }
        }
    }

    private static org.a2aproject.sdk.spec.Message message(AgentEmitter emitter, String text) {
        return emitter.newAgentMessage(List.of(new TextPart(text)), Map.of());
    }
}

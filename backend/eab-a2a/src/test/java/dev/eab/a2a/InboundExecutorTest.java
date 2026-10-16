package dev.eab.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 入站执行器：平台运行的结果映射到 A2A 任务状态，等待确认的运行停在 input-required，续接时带上运行编号 */
class InboundExecutorTest {

    static AgentCard card() {
        return AgentCard.builder()
                .name("入站测试")
                .description("入站测试")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder().streaming(true).build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder()
                        .id("s")
                        .name("s")
                        .description("s")
                        .tags(List.of("s"))
                        .build()))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://127.0.0.1/a2a")))
                .build();
    }

    static String send(String text, String taskId, String contextId) {
        String ids = taskId == null ? "" : ",\"taskId\":\"" + taskId + "\",\"contextId\":\"" + contextId + "\"";
        return "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\",\"params\":{\"message\":{\"messageId\":\""
                + java.util.UUID.randomUUID() + "\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"" + text + "\"}]" + ids
                + "}}}";
    }

    @Test
    @DisplayName("阻塞式调用：运行完成时返回完成的任务，回复写在产出物里，运行中的进度不影响结果")
    void completes() {
        var server = new A2aServer(
                card(),
                new InboundExecutor((principal, text, session, pending, progress) -> {
                    progress.accept("正在调用工具");
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    progress.accept("正在调用另一个工具");
                    return new InboundExecutor.Reply("completed", "回复：" + text + "，会话 " + session, "run-1", "t-1");
                }),
                Executors.newVirtualThreadPerTaskExecutor(),
                url -> {});
        var response = server.handle(send("你好", null, null), A2aServer.context("zhangmin", Map.of(), "1.0"));
        String body = ((A2aServer.Response.Json) response).body();
        assertThat(body).contains("TASK_STATE_COMPLETED", "回复：你好", "会话 a2a-");
    }

    @Test
    @DisplayName("等待确认：第一轮停在 input-required，第二轮带着同一个 taskId 回复时，执行器把上一轮的运行编号交给平台")
    void confirmation() throws Exception {
        List<String> pendings = new java.util.concurrent.CopyOnWriteArrayList<>();
        var server = new A2aServer(
                card(),
                new InboundExecutor((principal, text, session, pending, progress) -> {
                    pendings.add(String.valueOf(pending));
                    return pending == null
                            ? new InboundExecutor.Reply("input-required", "需要确认", "run-9", null)
                            : new InboundExecutor.Reply("completed", "已执行", "run-9", null);
                }),
                Executors.newVirtualThreadPerTaskExecutor(),
                url -> {});
        var context = A2aServer.context("zhangmin", Map.of(), "1.0");
        String first = ((A2aServer.Response.Json) server.handle(send("改交期", null, null), context)).body();
        assertThat(first).contains("TASK_STATE_INPUT_REQUIRED", "需要确认");
        var task = new com.google.gson.Gson().fromJson(first, com.google.gson.JsonObject.class)
                .getAsJsonObject("result")
                .getAsJsonObject("task");
        String second = ((A2aServer.Response.Json) server.handle(
                                send("确认", task.get("id").getAsString(), task.get("contextId").getAsString()), context))
                .body();
        assertThat(second).contains("TASK_STATE_COMPLETED", "已执行");
        assertThat(pendings).containsExactly("null", "run-9");
    }

    @Test
    @DisplayName("平台运行抛出异常时任务失败，不会挂起")
    void failure() {
        var server = new A2aServer(
                card(),
                new InboundExecutor((principal, text, session, pending, progress) -> {
                    throw new IllegalStateException("坏了");
                }),
                Executors.newVirtualThreadPerTaskExecutor(),
                url -> {});
        String body = ((A2aServer.Response.Json)
                        server.handle(send("你好", null, null), A2aServer.context("zhangmin", Map.of(), "1.0")))
                .body();
        assertThat(body).contains("TASK_STATE_FAILED", "坏了");
    }
}

package dev.eab.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 直接调用 {@link A2aServer#handle}：错误处理、各个 JSON-RPC 方法、0.3 的流式、只回复消息的 Agent、立即返回的请求 */
class A2aServerProtocolTest {

    final A2aServer tasks = new A2aServer(
            InboundExecutorTest.card(),
            A2aRoundTripTest.executor(),
            Executors.newVirtualThreadPerTaskExecutor(),
            url -> {});

    /** 不建任务、直接回复一条消息的 Agent */
    final A2aServer messages = new A2aServer(
            InboundExecutorTest.card(),
            (context, emitter) -> emitter.sendMessage(List.of(new TextPart("直接回复"))),
            Executors.newVirtualThreadPerTaskExecutor(),
            url -> {});

    final ServerCallContext context = A2aServer.context("zhangmin", Map.of(), "1.0");

    static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"r\",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    static String message(String text) {
        return "{\"message\":{\"messageId\":\"" + java.util.UUID.randomUUID()
                + "\",\"role\":\"ROLE_USER\",\"parts\":[{\"text\":\"" + text + "\"}]}";
    }

    String json(A2aServer server, String body) {
        return ((A2aServer.Response.Json) server.handle(body, context)).body();
    }

    static List<String> drain(A2aServer.Response response) throws InterruptedException {
        List<String> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        ((A2aServer.Response.Stream) response).events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                events.add(item);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        done.await(10, TimeUnit.SECONDS);
        return events;
    }

    @Test
    @DisplayName("请求体不是 JSON、方法不存在、参数不对时返回 JSON-RPC 错误")
    void errors() {
        assertThat(json(tasks, "not json")).contains("\"error\"");
        assertThat(json(tasks, rpc("NoSuchMethod", "{}"))).contains("\"error\"");
        assertThat(json(tasks, rpc("GetTask", "{\"id\":\"missing\"}"))).contains("\"error\"");
        assertThat(json(tasks, rpc("SendMessage", "{\"message\":42}"))).contains("\"error\"");
    }

    @Test
    @DisplayName("完成的任务可以用 GetTask 查到，用 ListTasks 列出；已完成的任务不能取消")
    void taskMethods() {
        String sent = json(tasks, rpc("SendMessage", message("百润精密") + "}"));
        assertThat(sent).contains("TASK_STATE_COMPLETED");
        String id = com.google.gson.JsonParser.parseString(sent)
                .getAsJsonObject()
                .getAsJsonObject("result")
                .getAsJsonObject("task")
                .get("id")
                .getAsString();
        assertThat(json(tasks, rpc("GetTask", "{\"id\":\"" + id + "\"}"))).contains(id, "风险等级 中");
        assertThat(json(tasks, rpc("ListTasks", "{}"))).contains(id);
        assertThat(json(tasks, rpc("CancelTask", "{\"id\":\"" + id + "\"}"))).contains("\"error\"");
    }

    @Test
    @DisplayName("Agent 直接回复消息时，阻塞式 SendMessage 返回这条消息")
    void messageReply() {
        assertThat(json(messages, rpc("SendMessage", message("你好") + "}"))).contains("直接回复");
    }

    @Test
    @DisplayName("请求要求立即返回时不等待任务完成")
    void returnImmediately() {
        String body = rpc("SendMessage", message("慢一点的百润") + ",\"configuration\":{\"returnImmediately\":true}}");
        assertThat(json(tasks, body)).contains("\"result\"");
    }

    @Test
    @DisplayName("1.0 的流式方法和 0.3 的 message/stream 都以 SSE 返回事件")
    void streams() throws Exception {
        var v1 = tasks.handle(rpc("SendStreamingMessage", message("百润精密") + "}"), context);
        assertThat(String.join("", drain(v1))).contains("TASK_STATE_COMPLETED");
        String legacy = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"message/stream\",\"params\":{\"message\":"
                + "{\"kind\":\"message\",\"messageId\":\"m-s\",\"role\":\"user\","
                + "\"parts\":[{\"kind\":\"text\",\"text\":\"百润精密\"}]}}}";
        var events = drain(tasks.handle(legacy, context));
        assertThat(String.join("", events)).startsWith("id: 0").contains("completed");
    }

    @Test
    @DisplayName("0.3 请求的参数不合法、tasks/cancel 找不到任务时返回错误")
    void legacyErrors() {
        assertThat(json(tasks, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tasks/get\",\"params\":\"x\"}"))
                .contains("\"error\"");
        assertThat(json(
                        tasks,
                        "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tasks/cancel\",\"params\":{\"id\":\"none\"}}"))
                .contains("\"error\"");
    }
}

package dev.eab.a2a;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import org.a2aproject.sdk.compat03.conversion.Convert_v0_3_To10RequestHandler;
import org.a2aproject.sdk.compat03.json.JsonProcessingException_v0_3;
import org.a2aproject.sdk.compat03.json.JsonUtil_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentCapabilities_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentCard_v0_3;
import org.a2aproject.sdk.compat03.spec.AgentSkill_v0_3;
import org.a2aproject.sdk.compat03.spec.CancelTaskRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.GetTaskRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.HTTPAuthSecurityScheme_v0_3;
import org.a2aproject.sdk.compat03.spec.SecurityScheme_v0_3;
import org.a2aproject.sdk.compat03.spec.SendMessageRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageRequest_v0_3;
import org.a2aproject.sdk.compat03.spec.SendStreamingMessageResponse_v0_3;
import org.a2aproject.sdk.compat03.transport.jsonrpc.handler.JSONRPCHandler_v0_3;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * 0.3 版本客户端的兼容入口：旧路径 /.well-known/agent.json 上的 0.3 格式 Agent Card，以及 message/send、message/stream、
 * tasks/get、tasks/cancel 四个方法。请求经 SDK 的兼容层转成 1.0 交给同一个请求处理器，任务与 1.0 客户端共享。
 */
final class LegacyJsonRpc {

    static final String PROTOCOL_VERSION = "0.3.0";

    private final AgentCard_v0_3 card;
    private final JSONRPCHandler_v0_3 handler;

    LegacyJsonRpc(AgentCard card, RequestHandler requestHandler, Executor workers) {
        this.card = convert(card);
        this.handler = new JSONRPCHandler_v0_3(this.card, workers, new Convert_v0_3_To10RequestHandler(requestHandler));
    }

    /** 由 1.0 的 Agent Card 生成 0.3 格式：url 取第一个 JSON-RPC 接口，安全方案只保留 Bearer */
    static AgentCard_v0_3 convert(AgentCard card) {
        String url = card.supportedInterfaces().isEmpty()
                ? null
                : card.supportedInterfaces().getFirst().url();
        List<AgentSkill_v0_3> skills = card.skills().stream()
                .map(s -> new AgentSkill_v0_3.Builder()
                        .id(s.id())
                        .name(s.name())
                        .description(s.description())
                        .tags(s.tags())
                        .examples(s.examples())
                        .build())
                .toList();
        boolean bearer =
                card.securitySchemes() != null && !card.securitySchemes().isEmpty();
        var builder = new AgentCard_v0_3.Builder()
                .name(card.name())
                .description(card.description())
                .url(url)
                .version(card.version())
                .protocolVersion(PROTOCOL_VERSION)
                .preferredTransport("JSONRPC")
                .capabilities(new AgentCapabilities_v0_3.Builder()
                        .streaming(Boolean.TRUE.equals(card.capabilities().streaming()))
                        .pushNotifications(
                                Boolean.TRUE.equals(card.capabilities().pushNotifications()))
                        .build())
                .defaultInputModes(card.defaultInputModes())
                .defaultOutputModes(card.defaultOutputModes())
                .skills(skills);
        if (bearer) {
            Map<String, SecurityScheme_v0_3> schemes =
                    Map.of("bearer", new HTTPAuthSecurityScheme_v0_3(null, "bearer", "平台签发的用户令牌"));
            builder.securitySchemes(schemes).security(List.of(Map.of("bearer", List.of())));
        }
        return builder.build();
    }

    String cardJson() {
        try {
            return JsonUtil_v0_3.toJson(card);
        } catch (JsonProcessingException_v0_3 e) {
            throw new IllegalStateException("0.3 格式的 Agent Card 无法序列化", e);
        }
    }

    /** 0.3 的方法名带斜杠，1.0 是驼峰，按方法名判断请求属于哪个版本 */
    static boolean isLegacy(String body) {
        String method = field(body, "method");
        return method != null && method.contains("/");
    }

    A2aServer.Response handle(String body, ServerCallContext context) {
        String method = field(body, "method");
        if (method == null) return error(null, -32700, "请求体不是合法的 JSON-RPC 请求");
        try {
            return switch (method) {
                case "message/send" ->
                    json(handler.onMessageSend(JsonUtil_v0_3.fromJson(body, SendMessageRequest_v0_3.class), context));
                case "message/stream" ->
                    stream(handler.onMessageSendStream(
                            JsonUtil_v0_3.fromJson(body, SendStreamingMessageRequest_v0_3.class), context));
                case "tasks/get" ->
                    json(handler.onGetTask(JsonUtil_v0_3.fromJson(body, GetTaskRequest_v0_3.class), context));
                case "tasks/cancel" ->
                    json(handler.onCancelTask(JsonUtil_v0_3.fromJson(body, CancelTaskRequest_v0_3.class), context));
                default -> error(idOf(body), -32601, "不支持的方法：" + method);
            };
        } catch (JsonProcessingException_v0_3 | RuntimeException e) {
            return error(idOf(body), -32602, "请求参数不合法：" + e.getMessage());
        }
    }

    private static JsonElement element(String body, String name) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return null;
            JsonElement value = root.getAsJsonObject().get(name);
            return value == null || !value.isJsonPrimitive() ? null : value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String field(String body, String name) {
        JsonElement value = element(body, name);
        return value == null ? null : value.getAsString();
    }

    private static Object idOf(String body) {
        JsonElement id = element(body, "id");
        if (id == null) return null;
        return id.getAsJsonPrimitive().isNumber() ? (Object) id.getAsLong() : id.getAsString();
    }

    private static A2aServer.Response json(Object response) throws JsonProcessingException_v0_3 {
        return new A2aServer.Response.Json(JsonUtil_v0_3.toJson(response));
    }

    private static A2aServer.Response error(Object id, int code, String message) {
        JsonObject node = new JsonObject();
        node.addProperty("jsonrpc", "2.0");
        if (id instanceof Long l) node.addProperty("id", l);
        else if (id != null) node.addProperty("id", id.toString());
        else node.add("id", com.google.gson.JsonNull.INSTANCE);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        node.add("error", err);
        return new A2aServer.Response.Json(node.toString());
    }

    private static A2aServer.Response stream(Flow.Publisher<SendStreamingMessageResponse_v0_3> publisher) {
        AtomicLong ids = new AtomicLong();
        Flow.Publisher<String> events = subscriber -> publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(SendStreamingMessageResponse_v0_3 item) {
                try {
                    subscriber.onNext(
                            "id: " + ids.getAndIncrement() + "\ndata: " + JsonUtil_v0_3.toJson(item) + "\n\n");
                } catch (JsonProcessingException_v0_3 e) {
                    subscriber.onError(e);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                subscriber.onError(throwable);
            }

            @Override
            public void onComplete() {
                subscriber.onComplete();
            }
        });
        return new A2aServer.Response.Stream(events);
    }
}

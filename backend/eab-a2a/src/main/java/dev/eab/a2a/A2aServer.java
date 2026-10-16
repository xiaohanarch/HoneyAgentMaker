package dev.eab.a2a;

import com.google.gson.JsonSyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.grpc.utils.ProtoUtils;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CreateTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskPushNotificationConfigResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SubscribeToTaskRequest;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.auth.AuthenticatedUser;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.BasePushNotificationSender;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.server.util.sse.SseFormatter;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.UnsupportedOperationError;

/**
 * 一个对外暴露的 A2A Server（JSON-RPC 传输），用 A2A 官方 Java SDK 的请求处理器、任务存储、事件总线和推送通知组装，
 * 不依赖 Quarkus 或其他 Web 框架：调用方把请求体和调用上下文交给 {@link #handle}，按返回值写 JSON 或 SSE。
 * 请求分发和响应序列化参照 SDK 的参考实现（a2a-java-sdk-reference-jsonrpc 的 A2AServerRoutes，Apache-2.0）。
 */
public final class A2aServer implements AutoCloseable {

    /** 调用上下文里保存调用方身份等平台信息的键 */
    public static final String PRINCIPAL_KEY = "eab.principal";

    /** 处理结果：普通 JSON，或者一串已格式化的 SSE 事件 */
    public sealed interface Response {
        record Json(String body) implements Response {}

        record Stream(Flow.Publisher<String> events) implements Response {}
    }

    private final AgentCard card;
    private final MainEventBusProcessor processor;
    private final org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler handler;

    /**
     * @param workers 执行 Agent 和消费事件的线程
     * @param pushUrls 推送通知地址的校验：只允许登记过的回调地址，防止被当作请求转发器
     */
    public A2aServer(AgentCard card, AgentExecutor executor, Executor workers, PushNotificationUrlValidator pushUrls) {
        this.card = card;
        OwnerTaskAuthorization ownership = new OwnerTaskAuthorization();
        InMemoryTaskStore tasks = new InMemoryTaskStore(ownership);
        MainEventBus bus = new MainEventBus();
        InMemoryQueueManager queues = new InMemoryQueueManager(tasks, bus);
        InMemoryPushNotificationConfigStore pushStore = new InMemoryPushNotificationConfigStore();
        A2AHttpClient http = new JdkA2AHttpClient();
        var sender = new BasePushNotificationSender(pushStore, http, pushUrls);
        this.processor = new MainEventBusProcessor(bus, tasks, sender, queues);
        processor.setPushNotificationExecutor(workers);
        processor.start();
        var requestHandler = DefaultRequestHandler.builder()
                .agentExecutor(executor)
                .taskStore(tasks)
                .queueManager(queues)
                .pushConfigStore(pushStore)
                .mainEventBusProcessor(processor)
                .executor(workers)
                .eventConsumerExecutor(workers)
                .pushNotificationsEnabled(true)
                .authorizationProvider(ownership)
                .build();
        this.handler = new org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler(card, requestHandler, workers);
    }

    public AgentCard card() {
        return card;
    }

    public String cardJson() {
        try {
            return JsonUtil.toJson(card);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent Card 无法序列化", e);
        }
    }

    /** 请求头里的协议版本 */
    public static final String VERSION_HEADER = "A2A-Version";

    /** 已认证调用方的调用上下文；attributes 里放平台自己的身份信息，protocolVersion 取自请求头 A2A-Version */
    public static ServerCallContext context(String subject, Map<String, Object> attributes, String protocolVersion) {
        Map<String, Object> state = new java.util.HashMap<>(attributes);
        // 请求没有带 A2A-Version 头时按当前协议版本处理（SDK 默认会当作 0.3）
        String version = protocolVersion == null || protocolVersion.isBlank()
                ? org.a2aproject.sdk.spec.AgentInterface.CURRENT_PROTOCOL_VERSION
                : protocolVersion;
        return new ServerCallContext(new AuthenticatedUser(subject, attributes), state, Set.of(), version);
    }

    public Response handle(String body, ServerCallContext context) {
        try {
            A2ARequest<?> request = JSONRPCUtils.parseRequestBody(body, null);
            if (request instanceof SendStreamingMessageRequest req) {
                return stream(handler.onMessageSendStream(req, context));
            }
            if (request instanceof SubscribeToTaskRequest req) {
                return stream(handler.onSubscribeToTask(req, context));
            }
            return new Response.Json(serialize(dispatch(request, context)));
        } catch (InvalidParamsJsonMappingException e) {
            return error(e.getId(), new org.a2aproject.sdk.spec.InvalidParamsError(null, e.getMessage(), null));
        } catch (MethodNotFoundJsonMappingException e) {
            return error(e.getId(), new MethodNotFoundError(null, e.getMessage(), null));
        } catch (JsonMappingException e) {
            return error(null, new InvalidRequestError(null, e.getMessage(), null));
        } catch (JsonSyntaxException | JsonProcessingException e) {
            return error(null, new JSONParseError(e.getMessage()));
        } catch (org.a2aproject.sdk.spec.A2AError e) {
            return new Response.Json(serialize(new A2AErrorResponse(e)));
        } catch (RuntimeException e) {
            return error(null, new InternalError("Internal error"));
        }
    }

    private static Response error(Object id, org.a2aproject.sdk.spec.A2AError error) {
        return new Response.Json(JSONRPCUtils.toJsonRPCErrorResponse(id, error));
    }

    private A2AResponse<?> dispatch(A2ARequest<?> request, ServerCallContext context) {
        if (request instanceof SendMessageRequest req) return handler.onMessageSend(req, context);
        if (request instanceof GetTaskRequest req) return handler.onGetTask(req, context);
        if (request instanceof CancelTaskRequest req) return handler.onCancelTask(req, context);
        if (request instanceof ListTasksRequest req) return handler.onListTasks(req, context);
        if (request instanceof CreateTaskPushNotificationConfigRequest req) {
            return handler.setPushNotificationConfig(req, context);
        }
        if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return handler.getPushNotificationConfig(req, context);
        }
        return new A2AErrorResponse(request.getId(), new UnsupportedOperationError());
    }

    private static Response stream(Flow.Publisher<SendStreamingMessageResponse> publisher) {
        AtomicLong ids = new AtomicLong();
        Flow.Publisher<String> events = subscriber -> publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(subscription);
            }

            @Override
            public void onNext(SendStreamingMessageResponse item) {
                subscriber.onNext(SseFormatter.formatResponseAsSSE(item, ids.getAndIncrement()));
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
        return new Response.Stream(events);
    }

    static String serialize(A2AResponse<?> response) {
        if (response.getError() != null)
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), response.getError());
        com.google.protobuf.MessageOrBuilder proto;
        if (response instanceof SendMessageResponse r) {
            proto = ProtoUtils.ToProto.taskOrMessage(r.getResult());
        } else if (response instanceof GetTaskResponse r) {
            proto = ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof CancelTaskResponse r) {
            proto = ProtoUtils.ToProto.task(r.getResult());
        } else if (response instanceof ListTasksResponse r) {
            proto = ProtoUtils.ToProto.listTasksResult(r.getResult());
        } else if (response instanceof CreateTaskPushNotificationConfigResponse r) {
            proto = ProtoUtils.ToProto.createTaskPushNotificationConfigResponse(r.getResult());
        } else if (response instanceof GetTaskPushNotificationConfigResponse r) {
            proto = ProtoUtils.ToProto.getTaskPushNotificationConfigResponse(r.getResult());
        } else {
            return JSONRPCUtils.toJsonRPCErrorResponse(response.getId(), new UnsupportedOperationError());
        }
        return JSONRPCUtils.toJsonRPCResultResponse(response.getId(), proto);
    }

    @Override
    public void close() {
        // 事件总线处理线程是守护线程，随进程退出；这里不需要额外清理
    }
}

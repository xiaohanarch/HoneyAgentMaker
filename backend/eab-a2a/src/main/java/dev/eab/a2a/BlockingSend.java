package dev.eab.a2a;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageRequest;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;

/**
 * 阻塞式 SendMessage：内部按流式执行，流结束后取任务的最终状态返回。
 *
 * <p>SDK 1.4.0 的 DefaultRequestHandler 在阻塞模式下只等 Agent 5 秒、等事件消费 2 秒（Builder 里写死），
 * 平台的运行经常超过这个时间，直接用会返回“Timeout waiting for task consumption”。流式路径没有这个限制。
 */
final class BlockingSend {

    /** 一次阻塞调用最长等待的时间 */
    static final Duration MAX_WAIT = Duration.ofMinutes(10);

    private BlockingSend() {}

    static SendMessageResponse send(JSONRPCHandler handler, SendMessageRequest request, ServerCallContext context) {
        var config = request.getParams().configuration();
        if (config != null && Boolean.TRUE.equals(config.returnImmediately())) {
            return handler.onMessageSend(request, context);
        }
        var publisher =
                handler.onMessageSendStream(new SendStreamingMessageRequest(request.getId(), request.getParams()), context);
        AtomicReference<String> taskId = new AtomicReference<>();
        AtomicReference<Message> message = new AtomicReference<>();
        AtomicReference<SendStreamingMessageResponse> failure = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SendStreamingMessageResponse item) {
                if (item.getError() != null) {
                    failure.compareAndSet(null, item);
                    return;
                }
                switch (item.getResult()) {
                    case Task task -> taskId.compareAndSet(null, task.id());
                    case TaskStatusUpdateEvent update -> taskId.compareAndSet(null, update.taskId());
                    case TaskArtifactUpdateEvent update -> taskId.compareAndSet(null, update.taskId());
                    case Message m -> message.compareAndSet(null, m);
                    default -> {}
                }
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        });
        try {
            done.get(MAX_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendMessageResponse(request.getId(), new InternalError("等待任务时被中断"));
        } catch (ExecutionException | TimeoutException e) {
            return new SendMessageResponse(request.getId(), new InternalError("等待任务完成失败：" + e.getMessage()));
        }
        if (failure.get() != null) return new SendMessageResponse(request.getId(), failure.get().getError());
        if (taskId.get() == null) {
            return message.get() == null
                    ? new SendMessageResponse(request.getId(), new InternalError("Agent 没有返回任务或消息"))
                    : new SendMessageResponse(request.getId(), message.get());
        }
        var task = handler.onGetTask(new GetTaskRequest(request.getId(), new TaskQueryParams(taskId.get())), context);
        return task.getError() != null
                ? new SendMessageResponse(request.getId(), task.getError())
                : new SendMessageResponse(request.getId(), task.getResult());
    }
}

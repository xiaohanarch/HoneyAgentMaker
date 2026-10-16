package dev.eab.platform.http;

import dev.eab.runtime.RunEvents;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Json;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 以 SSE 推送运行事件：运行在虚拟线程上执行，浏览器断开后停止推送，运行照常完成，结果和追踪仍可以通过
 * GET /runs/{id}、GET /traces/{traceId} 查看。运行抛出的异常只把面向用户的信息推给前端，细节写日志。
 */
public final class SseRuns {

    private static final Logger LOG = LoggerFactory.getLogger(SseRuns.class);

    static final String GENERIC_FAILURE = "运行失败，请稍后重试；详情见平台日志";

    private SseRuns() {}

    public static SseEmitter start(long timeoutMillis, String errorCode, Consumer<RunEvents.Sink> work) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        RunEvents.Sink sink = event -> send(emitter, open, event);
        Thread.ofVirtual().start(() -> {
            try {
                work.accept(sink);
            } catch (ApiException e) {
                sink.accept(RunEvent.of(
                        "error",
                        null,
                        "code",
                        e.error().code(),
                        "message",
                        e.error().message()));
            } catch (RuntimeException e) {
                LOG.error("运行失败", e);
                sink.accept(RunEvent.of("error", null, "code", errorCode, "message", GENERIC_FAILURE));
            } finally {
                complete(emitter, open);
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, AtomicBoolean open, RunEvent event) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(Json.MAPPER.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 浏览器已断开：之后的事件不再推送，运行照常完成
            open.set(false);
        }
    }

    private static void complete(SseEmitter emitter, AtomicBoolean open) {
        if (!open.getAndSet(false)) return;
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            // 已经由超时或断开结束
        }
    }
}

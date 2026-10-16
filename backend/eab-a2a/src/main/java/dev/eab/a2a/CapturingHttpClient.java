package dev.eab.a2a;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpResponse;
import org.a2aproject.sdk.client.http.ServerSentEvent;

/**
 * 记录 A2A 请求与响应原文的 HTTP 客户端，包在 SDK 自带的 JDK 客户端外面。Studio 的连通性测试和追踪里展示这些原文；
 * Authorization 头只记录类型，不记录令牌。
 */
public final class CapturingHttpClient implements A2AHttpClient {

    /** 一次 HTTP 往来；流式响应的每个 SSE 事件记一条 */
    public record Exchange(
            String at,
            String kind,
            String method,
            String url,
            Map<String, String> headers,
            String body,
            Integer status) {}

    private final A2AHttpClient delegate;
    private final List<Exchange> exchanges = Collections.synchronizedList(new ArrayList<>());

    public CapturingHttpClient(A2AHttpClient delegate) {
        this.delegate = delegate;
    }

    public List<Exchange> exchanges() {
        synchronized (exchanges) {
            return List.copyOf(exchanges);
        }
    }

    void record(String kind, String method, String url, Map<String, String> headers, String body, Integer status) {
        exchanges.add(new Exchange(Instant.now().toString(), kind, method, url, redact(headers), body, status));
    }

    static Map<String, String> redact(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((k, v) ->
                result.put(k, k.equalsIgnoreCase("authorization") && v != null ? v.split(" ")[0] + " ***" : v));
        return result;
    }

    @Override
    public GetBuilder createGet() {
        GetBuilder inner = delegate.createGet();
        Map<String, String> headers = new LinkedHashMap<>();
        String[] url = new String[1];
        return new GetBuilder() {
            @Override
            public GetBuilder url(String value) {
                url[0] = value;
                inner.url(value);
                return this;
            }

            @Override
            public GetBuilder addHeaders(Map<String, String> values) {
                headers.putAll(values);
                inner.addHeaders(values);
                return this;
            }

            @Override
            public GetBuilder addHeader(String name, String value) {
                headers.put(name, value);
                inner.addHeader(name, value);
                return this;
            }

            @Override
            public A2AHttpResponse get() throws IOException, InterruptedException {
                record("request", "GET", url[0], headers, null, null);
                A2AHttpResponse response = inner.get();
                record("response", "GET", url[0], Map.of(), response.body(), response.status());
                return response;
            }

            @Override
            public CompletableFuture<Void> getAsyncSSE(
                    Consumer<ServerSentEvent> events, Consumer<Throwable> errors, Runnable done)
                    throws IOException, InterruptedException {
                record("request", "GET", url[0], headers, null, null);
                return inner.getAsyncSSE(
                        e -> {
                            record("event", "GET", url[0], Map.of(), e.data(), null);
                            events.accept(e);
                        },
                        errors,
                        done);
            }
        };
    }

    @Override
    public PostBuilder createPost() {
        PostBuilder inner = delegate.createPost();
        Map<String, String> headers = new LinkedHashMap<>();
        String[] url = new String[1];
        String[] body = new String[1];
        return new PostBuilder() {
            @Override
            public PostBuilder url(String value) {
                url[0] = value;
                inner.url(value);
                return this;
            }

            @Override
            public PostBuilder addHeaders(Map<String, String> values) {
                headers.putAll(values);
                inner.addHeaders(values);
                return this;
            }

            @Override
            public PostBuilder addHeader(String name, String value) {
                headers.put(name, value);
                inner.addHeader(name, value);
                return this;
            }

            @Override
            public PostBuilder body(String value) {
                body[0] = value;
                inner.body(value);
                return this;
            }

            @Override
            public A2AHttpResponse post() throws IOException, InterruptedException {
                record("request", "POST", url[0], headers, body[0], null);
                A2AHttpResponse response = inner.post();
                record("response", "POST", url[0], Map.of(), response.body(), response.status());
                return response;
            }

            @Override
            public CompletableFuture<Void> postAsyncSSE(
                    Consumer<ServerSentEvent> events, Consumer<Throwable> errors, Runnable done)
                    throws IOException, InterruptedException {
                record("request", "POST", url[0], headers, body[0], null);
                return inner.postAsyncSSE(
                        e -> {
                            record("event", "POST", url[0], Map.of(), e.data(), null);
                            events.accept(e);
                        },
                        errors,
                        done);
            }
        };
    }

    @Override
    public DeleteBuilder createDelete() {
        return delegate.createDelete();
    }
}

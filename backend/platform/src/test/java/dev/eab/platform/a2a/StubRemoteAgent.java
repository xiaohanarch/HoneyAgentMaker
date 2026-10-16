package dev.eab.platform.a2a;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.eab.a2a.A2aServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 测试用的外部供应商风险 Agent：用官方 Java SDK 装配的 A2A Server，行为与 external-agents/supplier-risk-py 一致：
 * 话术里没有供应商时追问，有“深度”时慢速返回（超过平台的同步超时），否则直接给出风险等级。记录收到的 Authorization 头。
 */
final class StubRemoteAgent implements AutoCloseable {

    static final long SLOW_MILLIS = 3_000;

    final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    final String url;

    StubRemoteAgent() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        url = "http://127.0.0.1:" + server.getAddress().getPort();
        AgentCard card = AgentCard.builder()
                .name("供应商风险舆情 Agent")
                .description("测试替身")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(true)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder()
                        .id("supplier-risk")
                        .name("供应商风险评估")
                        .description("按舆情给出风险等级")
                        .tags(List.of("risk"))
                        .build()))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url + "/")))
                .build();
        var a2a = new A2aServer(
                card, executor(), Executors.newVirtualThreadPerTaskExecutor(), PushNotificationUrlValidator.ALLOW_ALL);
        server.createContext("/.well-known/agent-card.json", ex -> write(ex, 200, a2a.cardJson()));
        server.createContext("/", ex -> serve(ex, a2a));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    static AgentExecutor executor() {
        return (context, emitter) -> {
            String text = context.getUserInput();
            if (context.getTask() == null) emitter.submit();
            emitter.startWork();
            if (text.contains("深度")) {
                try {
                    Thread.sleep(SLOW_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String supplier = List.of("百润精密", "华远电子").stream()
                    .filter(text::contains)
                    .findFirst()
                    .orElse(null);
            if (supplier == null) {
                emitter.requiresInput(emitter.newAgentMessage(List.of(new TextPart("请问要评估哪家供应商？")), Map.of()));
                return;
            }
            emitter.addArtifact(List.of(new TextPart(supplier + "：风险等级 高，依据：近期有停产传闻")));
            emitter.complete();
        };
    }

    private void serve(HttpExchange ex, A2aServer a2a) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            write(ex, 404, "{}");
            return;
        }
        String authorization = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.add(authorization == null ? "" : authorization);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var response = a2a.handle(
                body, A2aServer.context("platform", Map.of(), ex.getRequestHeaders().getFirst(A2aServer.VERSION_HEADER)));
        if (response instanceof A2aServer.Response.Json json) {
            write(ex, 200, json.body());
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        CountDownLatch done = new CountDownLatch(1);
        ((A2aServer.Response.Stream) response).events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                try {
                    out.write(item.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    done.countDown();
                }
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
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.close();
    }

    static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

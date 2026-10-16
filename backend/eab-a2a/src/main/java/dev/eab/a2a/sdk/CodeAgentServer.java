package dev.eab.a2a.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.eab.a2a.A2aServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 把一个 {@link CodeAgent} 以 A2A 协议对外提供：Agent Card 在标准路径和旧路径，JSON-RPC 端点在根路径，
 * 只接受平台签发的服务令牌。用 JDK 自带的 HTTP 服务，不依赖 Web 框架，适合独立进程部署。
 */
public final class CodeAgentServer implements AutoCloseable {

    /**
     * Agent 的描述，写进 Agent Card。
     *
     * @param skillId 技能编号，同时作为默认的资产名
     */
    public record Description(String name, String description, String skillId, List<String> examples) {}

    private static final String SCHEME = "platformToken";

    private final HttpServer http;
    private final String url;

    private CodeAgentServer(HttpServer http, String url) {
        this.http = http;
        this.url = url;
    }

    /**
     * 启动服务。
     *
     * @param port 0 表示随机端口
     */
    public static CodeAgentServer start(Description description, CodeAgent agent, PlatformTokens tokens, int port)
            throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        String url = "http://127.0.0.1:" + http.getAddress().getPort();
        var a2a = new A2aServer(
                card(description, url), executor(agent), Executors.newVirtualThreadPerTaskExecutor(), target -> {
                    throw new IllegalArgumentException("代码 Agent 不提供推送通知");
                });
        http.createContext("/.well-known/agent-card.json", ex -> write(ex, 200, a2a.cardJson()));
        http.createContext("/.well-known/agent.json", ex -> write(ex, 200, a2a.legacyCardJson()));
        http.createContext("/", ex -> serve(ex, a2a, tokens));
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return new CodeAgentServer(http, url);
    }

    public String url() {
        return url;
    }

    static AgentCard card(Description d, String url) {
        return AgentCard.builder()
                .name(d.name())
                .description(d.description())
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder()
                        .id(d.skillId())
                        .name(d.name())
                        .description(d.description())
                        .tags(List.of("code-agent"))
                        .examples(d.examples())
                        .build()))
                .securitySchemes(Map.of(
                        SCHEME,
                        HTTPAuthSecurityScheme.builder()
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("平台签发的服务令牌")
                                .build()))
                .securityRequirements(List.of(new SecurityRequirement(Map.of(SCHEME, List.of()))))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url + "/")))
                .build();
    }

    static AgentExecutor executor(CodeAgent agent) {
        return (context, emitter) -> {
            if (context.getTask() == null) emitter.submit();
            emitter.startWork();
            var caller = (CodeAgent.Caller) context.getCallContext().getState().get(A2aServer.PRINCIPAL_KEY);
            CodeAgent.Reply reply;
            try {
                reply = agent.reply(new CodeAgent.Request(caller, context.getUserInput(), context.getContextId()));
            } catch (RuntimeException e) {
                emitter.fail(
                        emitter.newAgentMessage(List.of(new TextPart("代码 Agent 执行失败：" + e.getMessage())), Map.of()));
                return;
            }
            if (reply.needsInput()) {
                emitter.requiresInput(emitter.newAgentMessage(List.of(new TextPart(reply.text())), Map.of()));
                return;
            }
            emitter.addArtifact(List.of(new TextPart(reply.text())));
            emitter.complete();
        };
    }

    private static void serve(HttpExchange ex, A2aServer a2a, PlatformTokens tokens) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            write(ex, 405, "{\"error\":\"只接受 POST\"}");
            return;
        }
        CodeAgent.Caller caller;
        try {
            caller = tokens.verify(ex.getRequestHeaders().getFirst("Authorization"));
        } catch (SecurityException e) {
            write(ex, 401, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var response = a2a.handle(
                body,
                A2aServer.context(
                        caller.user(),
                        Map.of(A2aServer.PRINCIPAL_KEY, caller),
                        ex.getRequestHeaders().getFirst(A2aServer.VERSION_HEADER)));
        if (response instanceof A2aServer.Response.Json json) {
            write(ex, 200, json.body());
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            pipe(((A2aServer.Response.Stream) response).events(), out);
        }
    }

    static void pipe(Flow.Publisher<String> events, OutputStream out) {
        CountDownLatch done = new CountDownLatch(1);
        events.subscribe(new Flow.Subscriber<>() {
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
    }

    static void write(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        http.stop(0);
    }
}

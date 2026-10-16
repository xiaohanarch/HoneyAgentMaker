package dev.eab.a2a;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.a2aproject.sdk.A2A;
import org.a2aproject.sdk.client.Client;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.MessageEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.http.JdkA2AHttpClient;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransportConfigBuilder;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;

/**
 * 出站 A2A 调用（主提示词 8.4 节），用官方 Java SDK 的客户端和 JSON-RPC 传输：拉取 Agent Card；发送消息时带上多轮的
 * contextId、taskId；对方支持流式时走流式；同步等待超过超时时间后转为异步任务，向对方登记推送通知回调。
 * 每次调用都记录请求与响应原文（令牌只记类型）。
 */
public final class A2aOutbound {

    /** 同步调用的默认超时，超过后转异步（主提示词 8.4 节） */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * 调用结果。
     *
     * @param state completed、input-required、working（已转异步）、failed、rejected、canceled、auth-required
     * @param text 对方的回复文本：完成时是结果，input-required 时是追问
     */
    public record Outcome(
            String state,
            String text,
            String contextId,
            String taskId,
            boolean async,
            List<CapturingHttpClient.Exchange> exchanges) {}

    public record CardResult(AgentCard card, String json, List<CapturingHttpClient.Exchange> exchanges) {}

    /** 推送通知回调：异步任务完成时对方把任务状态推到这个地址，带上 token 供平台核对 */
    public record PushTarget(String url, String token) {}

    private A2aOutbound() {}

    /** 拉取 Agent Card；baseUrl 是连接别名绑定的地址，cardPath 默认 /.well-known/agent-card.json */
    public static CardResult fetchCard(String baseUrl, String cardPath, Map<String, String> headers) {
        CapturingHttpClient http = new CapturingHttpClient(new JdkA2AHttpClient());
        String path = cardPath == null || cardPath.isBlank() ? "/.well-known/agent-card.json" : cardPath;
        try {
            String url = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/")
                    .resolve(path.substring(1))
                    .toString();
            var response = http.createGet().url(url).addHeaders(headers).get();
            if (!response.success()) {
                throw new A2aException("CARD_UNAVAILABLE", "拉取 Agent Card 失败：HTTP " + response.status());
            }
            AgentCard card = A2A.getAgentCard(new JdkA2AHttpClient(), baseUrl, path, headers);
            return new CardResult(card, response.body(), http.exchanges());
        } catch (A2aException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new A2aException("AGENT_UNREACHABLE", "无法连接外部 Agent（" + baseUrl + "）：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException("INTERRUPTED", "拉取 Agent Card 被中断");
        } catch (RuntimeException e) {
            throw new A2aException("CARD_INVALID", "Agent Card 无法解析：" + e.getMessage());
        }
    }

    public static final class A2aException extends RuntimeException {
        private final String code;

        public A2aException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * 发送一条消息并等待结果。
     *
     * @param bearer 调用对方时携带的令牌（平台签发的服务令牌），为 null 时不带
     * @param push 对方支持推送时用于异步回调；为 null 时超时后只返回任务编号
     */
    public static Outcome send(
            AgentCard card,
            String bearer,
            String text,
            String contextId,
            String taskId,
            Duration timeout,
            PushTarget push) {
        CapturingHttpClient http = new CapturingHttpClient(new JdkA2AHttpClient());
        boolean streaming = card.capabilities() != null && card.capabilities().streaming();
        Client client;
        try {
            client = Client.builder(card)
                    .clientConfig(
                            new ClientConfig.Builder().setStreaming(streaming).build())
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder().httpClient(http))
                    .build();
        } catch (org.a2aproject.sdk.spec.A2AClientException e) {
            throw new A2aException("CLIENT_INVALID", "无法按 Agent Card 建立 A2A 客户端：" + e.getMessage());
        }
        Map<String, String> headers = bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer);
        ClientCallContext context = new ClientCallContext(Map.of(), headers);
        Message message = userMessage(text, contextId, taskId);

        CompletableFuture<Task> finished = new CompletableFuture<>();
        AtomicReference<Task> latest = new AtomicReference<>();
        AtomicReference<String> replyText = new AtomicReference<>();
        BiConsumer<ClientEvent, AgentCard> consumer = (event, ignored) -> {
            switch (event) {
                case MessageEvent m -> {
                    replyText.set(textOf(m.getMessage()));
                    finished.complete(null);
                }
                case TaskEvent t -> observe(t.getTask(), latest, finished);
                case TaskUpdateEvent u -> observe(u.getTask(), latest, finished);
                default -> {}
            }
        };
        try {
            Thread.ofVirtual().start(() -> {
                try {
                    client.sendMessage(message, List.of(consumer), finished::completeExceptionally, context);
                } catch (Exception e) {
                    finished.completeExceptionally(e);
                }
            });
            Task task = finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (task == null && replyText.get() != null) {
                return new Outcome("completed", replyText.get(), contextId, null, false, http.exchanges());
            }
            return outcomeOf(task == null ? latest.get() : task, false, http);
        } catch (TimeoutException e) {
            Task current = latest.get();
            if (current != null
                    && push != null
                    && card.capabilities() != null
                    && card.capabilities().pushNotifications()) {
                registerPush(client, current.id(), push, context);
            }
            String note = current == null
                    ? "外部 Agent 超过 " + timeout.toSeconds() + " 秒没有返回，已转为异步"
                    : "外部 Agent 超过 " + timeout.toSeconds() + " 秒没有完成，已转为异步任务 " + current.id() + "，完成后通过推送回调送达";
            return new Outcome(
                    "working",
                    note,
                    current == null ? contextId : current.contextId(),
                    current == null ? taskId : current.id(),
                    true,
                    http.exchanges());
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new A2aException("AGENT_UNREACHABLE", "调用外部 Agent 失败：" + rootMessage(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aException("INTERRUPTED", "调用外部 Agent 被中断");
        } finally {
            client.close();
        }
    }

    /** 查询任务的最新状态（异步任务轮询、恢复时使用） */
    public static Outcome getTask(AgentCard card, String bearer, String taskId) {
        CapturingHttpClient http = new CapturingHttpClient(new JdkA2AHttpClient());
        Client client = null;
        try {
            client = Client.builder(card)
                    .clientConfig(new ClientConfig.Builder().setStreaming(false).build())
                    .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfigBuilder().httpClient(http))
                    .build();
            Map<String, String> headers = bearer == null ? Map.of() : Map.of("Authorization", "Bearer " + bearer);
            Task task = client.getTask(new TaskQueryParams(taskId), new ClientCallContext(Map.of(), headers));
            return outcomeOf(task, false, http);
        } catch (Exception e) {
            throw new A2aException("AGENT_UNREACHABLE", "查询外部 Agent 的任务失败：" + rootMessage(e));
        } finally {
            if (client != null) client.close();
        }
    }

    /** 用户消息：多轮时带上对方返回的 contextId、taskId，第一轮两者都不带 */
    static Message userMessage(String text, String contextId, String taskId) {
        Message.Builder builder = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(List.of(new TextPart(text)))
                .messageId(java.util.UUID.randomUUID().toString());
        if (contextId != null && !contextId.isBlank()) builder.contextId(contextId);
        if (taskId != null && !taskId.isBlank()) builder.taskId(taskId);
        return builder.build();
    }

    private static void registerPush(Client client, String taskId, PushTarget push, ClientCallContext context) {
        try {
            client.createTaskPushNotificationConfiguration(
                    TaskPushNotificationConfig.builder()
                            .taskId(taskId)
                            .url(push.url())
                            .token(push.token())
                            .build(),
                    context);
        } catch (Exception e) {
            // 登记失败不影响已转异步的结果：平台仍可以按任务编号查询
        }
    }

    private static void observe(Task task, AtomicReference<Task> latest, CompletableFuture<Task> finished) {
        if (task == null) return;
        latest.set(task);
        TaskState state = task.status() == null ? null : task.status().state();
        if (state != null && (state.isFinal() || state.isInterrupted())) finished.complete(task);
    }

    static Outcome outcomeOf(Task task, boolean async, CapturingHttpClient http) {
        if (task == null) return new Outcome("failed", "外部 Agent 没有返回结果", null, null, async, http.exchanges());
        return new Outcome(
                stateName(task.status().state()), textOf(task), task.contextId(), task.id(), async, http.exchanges());
    }

    public static String stateName(TaskState state) {
        if (state == null) return "unknown";
        return switch (state) {
            case TASK_STATE_COMPLETED -> "completed";
            case TASK_STATE_INPUT_REQUIRED -> "input-required";
            case TASK_STATE_AUTH_REQUIRED -> "auth-required";
            case TASK_STATE_FAILED -> "failed";
            case TASK_STATE_REJECTED -> "rejected";
            case TASK_STATE_CANCELED -> "canceled";
            case TASK_STATE_SUBMITTED -> "submitted";
            case TASK_STATE_WORKING -> "working";
            default -> "unknown";
        };
    }

    /** 任务的文本：状态消息里的文本加上产物里的文本 */
    public static String textOf(Task task) {
        List<String> parts = new ArrayList<>();
        if (task.status() != null && task.status().message() != null)
            parts.add(textOf(task.status().message()));
        if (task.artifacts() != null) {
            for (Artifact artifact : task.artifacts()) parts.add(textOfParts(artifact.parts()));
        }
        return String.join("\n", parts.stream().filter(s -> !s.isBlank()).toList());
    }

    public static String textOf(Message message) {
        return message == null ? "" : textOfParts(message.parts());
    }

    static String textOfParts(List<Part<?>> parts) {
        if (parts == null) return "";
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart t) text.append(t.text());
        }
        return text.toString();
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}

package dev.eab.platform.a2a;

import dev.eab.a2a.A2aOutbound;
import dev.eab.a2a.CapturingHttpClient;
import dev.eab.common.IsoTime;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.runtime.Ports;
import dev.eab.runtime.RunContext;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.ConnectionAsset;
import dev.eab.spec.model.Asset.RemoteAgentAsset;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * A2A 出站网关（主提示词 8.4 节，ADR 0012）：平台里的 Agent 调用远程 Agent 时经过这里。
 *
 * <ul>
 *   <li>远程 Agent 资产只引用连接别名，地址先取部署配置，再取本租户 Connection 资产的 prod 绑定；
 *   <li>身份透传：以最终用户为 sub、发起调用的平台 Agent 为 act 签发服务令牌；没有用户身份、连接未绑定、配置不全时一律拒绝，
 *       不退回到任何技术账号；
 *   <li>多轮：同一会话里对方上一轮停在 input-required 时，带着对方的 contextId、taskId 续接；
 *   <li>同步等待超过超时时间转为异步任务，向对方登记推送回调，结果到达后推送到用户的通知区；
 *   <li>对方停机或报错时给出明确的降级说明。
 * </ul>
 */
public final class RemoteAgentGateway implements Ports.RemoteAgents {

    /** 记录原文时每个请求体或响应体保留的最大长度 */
    static final int MAX_BODY = 4000;

    static final Duration CARD_TTL = Duration.ofSeconds(60);

    /**
     * @param publicUrl 平台对外的地址（推送回调用），随机端口的测试里在启动后才知道，所以按需取
     */
    public record Settings(
            Map<String, String> connections, java.util.function.Supplier<String> publicUrl, Duration timeout) {}

    private record CachedCard(AgentCard card, Instant at) {}

    private final PlatformServices services;
    private final A2aTaskStore tasks;
    private final Settings settings;
    private final Map<String, CachedCard> cards = new ConcurrentHashMap<>();

    public RemoteAgentGateway(PlatformServices services, A2aTaskStore tasks, Settings settings) {
        this.services = services;
        this.tasks = tasks;
        this.settings = settings;
    }

    public A2aTaskStore tasks() {
        return tasks;
    }

    public Settings settings() {
        return settings;
    }

    @Override
    public Ports.RemoteResult call(RunContext context, String remoteRef, String message) {
        if (context.user() == null || context.user().id() == null) {
            return deny(context, remoteRef, "IDENTITY_MISSING", "身份透传失败：本次运行没有最终用户身份，拒绝调用外部 Agent，不会退回到技术账号");
        }
        String tenantId = context.user().tenantId();
        Optional<RemoteAgentAsset> found = services.registry()
                .resolverFor(Viewer.tenant(tenantId))
                .resolveOfKind(remoteRef, RemoteAgentAsset.class, dev.eab.spec.AssetResolver.PUBLISHED);
        if (found.isEmpty()) {
            return Ports.RemoteResult.failure("REMOTE_AGENT_NOT_FOUND", "远程 Agent " + remoteRef + " 不存在或本租户不可用");
        }
        RemoteAgentAsset remote = found.get();
        String name = displayName(remote);
        if (!callerAllowed(remote, context.agent())) {
            return deny(
                    context,
                    remoteRef,
                    "CALLER_NOT_ALLOWED",
                    "外部 Agent「" + name + "」的允许调用方里没有 " + context.agent() + "，拒绝调用");
        }
        String baseUrl = connectionUrl(tenantId, remote.spec().connection()).orElse(null);
        if (baseUrl == null) {
            return deny(
                    context,
                    remoteRef,
                    "CONNECTION_UNBOUND",
                    "连接 " + remote.spec().connection() + " 在本环境没有绑定地址，拒绝调用外部 Agent「" + name + "」");
        }
        String bearer = "platform-service-token".equals(remote.spec().auth().type())
                ? services.tokens()
                        .issueService(context.user().id(), tenantId, "agent:" + context.agent(), refOf(remote))
                : null;
        AgentCard card;
        try {
            card = card(baseUrl, remote.spec().agentCardPath());
        } catch (A2aOutbound.A2aException e) {
            return unavailable(name, e);
        }
        String ref = refOf(remote);
        var pending = tasks.pendingInput(tenantId, context.user().id(), context.sessionId(), ref);
        String callbackId = "a2a-" + UUID.randomUUID();
        String callbackToken = UUID.randomUUID().toString();
        A2aOutbound.Outcome outcome;
        try {
            outcome = A2aOutbound.send(
                    card,
                    bearer,
                    message,
                    pending.map(A2aTaskStore.A2aTask::contextId).orElse(null),
                    pending.map(A2aTaskStore.A2aTask::taskId).orElse(null),
                    settings.timeout(),
                    new A2aOutbound.PushTarget(
                            settings.publicUrl().get() + "/a2a/callbacks/" + callbackId, callbackToken));
        } catch (A2aOutbound.A2aException e) {
            cards.remove(baseUrl);
            return unavailable(name, e);
        }
        String now = IsoTime.now(services.clock());
        tasks.insert(new A2aTaskStore.A2aTask(
                callbackId,
                tenantId,
                context.user().id(),
                context.sessionId(),
                context.runId(),
                ref,
                outcome.taskId(),
                outcome.contextId(),
                outcome.state(),
                outcome.text(),
                A2aTaskStore.hash(callbackToken),
                now,
                now));
        String text = switch (outcome.state()) {
            case "input-required" -> "外部 Agent「" + name + "」需要补充信息：" + outcome.text();
            case "working", "submitted" ->
                "外部 Agent「" + name + "」超过 " + settings.timeout().toSeconds() + " 秒仍在处理，已转为异步任务，结果到达后会推送到通知区";
            default -> outcome.text();
        };
        return new Ports.RemoteResult(
                true,
                outcome.state(),
                text,
                outcome.taskId(),
                outcome.contextId(),
                name,
                exchanges(outcome.exchanges()),
                null,
                null);
    }

    /**
     * 推送回调到达但没带结果正文时，用发起调用的用户身份向对方查询任务的最新状态。
     *
     * @return 查不到（远程 Agent 已下线、连接未绑定）时为空
     */
    public Optional<A2aOutbound.Outcome> refresh(A2aTaskStore.A2aTask task) {
        var resolver = services.registry().resolverFor(Viewer.tenant(task.tenantId()));
        Optional<RemoteAgentAsset> found =
                resolver.resolveOfKind(task.remote(), RemoteAgentAsset.class, dev.eab.spec.AssetResolver.PUBLISHED);
        if (found.isEmpty() || task.taskId() == null) return Optional.empty();
        RemoteAgentAsset remote = found.get();
        Optional<String> baseUrl = connectionUrl(task.tenantId(), remote.spec().connection());
        if (baseUrl.isEmpty()) return Optional.empty();
        String bearer = "platform-service-token".equals(remote.spec().auth().type())
                ? services.tokens().issueService(task.userId(), task.tenantId(), "platform:a2a-callback", task.remote())
                : null;
        try {
            return Optional.of(
                    A2aOutbound.getTask(card(baseUrl.get(), remote.spec().agentCardPath()), bearer, task.taskId()));
        } catch (A2aOutbound.A2aException e) {
            return Optional.empty();
        }
    }

    private Ports.RemoteResult deny(RunContext context, String remoteRef, String code, String message) {
        services.audit()
                .append(NewAuditEvent.of(
                                context.user() == null
                                        ? "unknown"
                                        : context.user().id(),
                                "a2a.outbound",
                                remoteRef,
                                "deny",
                                message)
                        .tenantId(context.user() == null ? null : context.user().tenantId()));
        return Ports.RemoteResult.failure(code, message);
    }

    static Ports.RemoteResult unavailable(String name, A2aOutbound.A2aException e) {
        return Ports.RemoteResult.failure(
                "REMOTE_AGENT_UNAVAILABLE", "外部 Agent「" + name + "」暂时不可用（" + e.getMessage() + "），本次回答不包含它提供的信息，可以稍后重试");
    }

    AgentCard card(String baseUrl, String cardPath) {
        CachedCard cached = cards.get(baseUrl);
        Instant now = services.clock().instant();
        if (cached != null && cached.at().plus(CARD_TTL).isAfter(now)) return cached.card();
        AgentCard card = A2aOutbound.fetchCard(baseUrl, cardPath, Map.of()).card();
        cards.put(baseUrl, new CachedCard(card, now));
        return card;
    }

    /** 连接别名的地址：部署配置优先，其次本租户 Connection 资产的 prod 绑定 */
    public Optional<String> connectionUrl(String tenantId, String alias) {
        String configured = settings.connections().get(alias);
        if (configured != null && !configured.isBlank()) return Optional.of(configured);
        var published = services.registry()
                .latestPublished(new AssetFilter("Connection", null, "tenant", tenantId, null), Viewer.tenant(tenantId))
                .assets();
        for (Asset asset : published) {
            if (asset instanceof ConnectionAsset c
                    && alias.equals(c.spec().alias())
                    && c.spec().bindings() != null) {
                var binding = c.spec().bindings().get("prod");
                if (binding != null && binding.url() != null) return Optional.of(binding.url());
            }
        }
        return Optional.empty();
    }

    /** 允许调用方为空或包含 * 时不限制；否则调用方（平台 Agent 或岗位助手，不带版本）必须在列表里 */
    static boolean callerAllowed(RemoteAgentAsset remote, String caller) {
        List<String> allowed = remote.spec().allowedCallers();
        if (allowed == null || allowed.isEmpty() || allowed.contains("*")) return true;
        if (caller == null) return false;
        String bare = caller.contains("@") ? caller.substring(0, caller.indexOf('@')) : caller;
        return allowed.contains(bare);
    }

    static String refOf(Asset asset) {
        return asset.metadata().namespace() + "/" + asset.metadata().name();
    }

    static String displayName(Asset asset) {
        return asset.metadata().displayName() == null
                ? asset.metadata().name()
                : asset.metadata().displayName();
    }

    static List<Map<String, Object>> exchanges(List<CapturingHttpClient.Exchange> exchanges) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : exchanges) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("at", e.at());
            item.put("kind", e.kind());
            item.put("method", e.method());
            item.put("url", e.url());
            if (!e.headers().isEmpty()) item.put("headers", e.headers());
            if (e.body() != null) {
                item.put("body", e.body().length() > MAX_BODY ? e.body().substring(0, MAX_BODY) + "…" : e.body());
            }
            if (e.status() != null) item.put("status", e.status());
            result.add(item);
        }
        return result;
    }
}

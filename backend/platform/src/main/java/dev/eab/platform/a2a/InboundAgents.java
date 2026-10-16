package dev.eab.platform.a2a;

import dev.eab.a2a.A2aServer;
import dev.eab.a2a.InboundExecutor;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AssistantAsset;
import dev.eab.spec.model.Common.Routing;
import dev.eab.spec.model.Common.Trigger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.a2aproject.sdk.server.tasks.PushNotificationUrlValidator;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;

/**
 * 入站 A2A（主提示词 8.4 节）：已发布、并且触发器里声明了 {@code {type: agent}}（允许被其他 Agent 调用）的 Agent 和岗位助手，
 * 各自作为一个 A2A Server 对外暴露，地址是 /a2a/agents/&lt;namespace&gt;/&lt;name&gt;。
 * Agent Card 只用资产的显示名、路由说明和示例话术，不含指令原文。
 */
public final class InboundAgents {

    /** 安全方案的名字：平台签发的用户令牌 */
    static final String SCHEME = "platformUserToken";

    /**
     * 一个对外暴露的能力。
     *
     * @param kind agent 或 assistant
     */
    public record Exposed(
            String kind, String ref, String version, String name, String description, List<String> examples) {}

    private final PlatformServices services;
    private final Supplier<String> publicUrl;
    private final Function<Exposed, InboundExecutor.Runner> runners;
    private final Executor workers;
    private final Map<String, A2aServer> servers = new ConcurrentHashMap<>();

    public InboundAgents(
            PlatformServices services,
            Supplier<String> publicUrl,
            Function<Exposed, InboundExecutor.Runner> runners,
            Executor workers) {
        this.services = services;
        this.publicUrl = publicUrl;
        this.runners = runners;
        this.workers = workers;
    }

    /** 租户层资产按命名空间里的租户取，标品资产用标品视角取（只读显示名和路由说明） */
    static Viewer viewerOf(String namespace) {
        String[] parts = namespace.split("\\.");
        return parts.length >= 2 && parts[0].equals("tenant") ? Viewer.tenant(parts[1]) : new Viewer.Vendor();
    }

    static boolean exposed(List<Trigger> triggers) {
        return triggers != null && triggers.stream().anyMatch(t -> "agent".equals(t.type()));
    }

    public Optional<Exposed> find(String namespace, String name) {
        var published = services.registry()
                .latestPublished(new AssetFilter(null, namespace, null, null, null), viewerOf(namespace))
                .assets();
        return published.stream()
                .filter(a -> a.metadata().name().equals(name))
                .flatMap(a -> toExposed(a).stream())
                .findFirst();
    }

    static Optional<Exposed> toExposed(Asset asset) {
        if (asset instanceof AgentAsset agent && exposed(agent.spec().triggers())) {
            return Optional.of(
                    exposed("agent", asset, agent.spec().routing(), agent.spec().expertise()));
        }
        if (asset instanceof AssistantAsset assistant
                && exposed(assistant.spec().triggers())) {
            return Optional.of(exposed("assistant", asset, assistant.spec().routing(), null));
        }
        return Optional.empty();
    }

    /** 已对外暴露的全部能力，Studio 的“对外 A2A”列表用 */
    public List<Exposed> list(String tenantId) {
        return services.registry().latestPublished(AssetFilter.all(), Viewer.tenant(tenantId)).assets().stream()
                .flatMap(a -> toExposed(a).stream())
                .toList();
    }

    private static Exposed exposed(String kind, Asset asset, Routing routing, String fallback) {
        var m = asset.metadata();
        return new Exposed(
                kind,
                m.namespace() + "/" + m.name(),
                m.version(),
                m.displayName() == null ? m.name() : m.displayName(),
                routing != null && routing.description() != null
                        ? routing.description()
                        : fallback == null ? m.description() : fallback,
                routing == null || routing.examples() == null ? List.of() : routing.examples());
    }

    public String endpoint(Exposed e) {
        return publicUrl.get() + "/a2a/agents/" + e.ref();
    }

    /** 每个能力每个版本一个 A2A Server；资产升版本后按新版本重建 */
    public A2aServer server(Exposed e) {
        return servers.computeIfAbsent(
                e.ref() + "@" + e.version(),
                k -> new A2aServer(card(e), new InboundExecutor(runners.apply(e)), workers, NO_PUSH));
    }

    AgentCard card(Exposed e) {
        return AgentCard.builder()
                .name(e.name())
                .description(e.description() == null ? e.name() : e.description())
                .version(e.version())
                .provider(new AgentProvider("ERP Agent Builder", publicUrl.get()))
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder()
                        .id(e.ref().substring(e.ref().indexOf('/') + 1))
                        .name(e.name())
                        .description(e.description() == null ? e.name() : e.description())
                        .tags(List.of("erp", e.kind()))
                        .examples(e.examples())
                        .build()))
                .securitySchemes(Map.of(
                        SCHEME,
                        HTTPAuthSecurityScheme.builder()
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("平台签发的用户令牌；入站请求同样经过四处鉴权")
                                .build()))
                .securityRequirements(List.of(new SecurityRequirement(Map.of(SCHEME, List.of()))))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint(e))))
                .build();
    }

    /** 推送通知不对外开放：入站任务的结果由调用方查询或流式接收，平台不向任意地址发请求 */
    static final PushNotificationUrlValidator NO_PUSH = url -> {
        throw new IllegalArgumentException("平台的入站 A2A 不提供推送通知");
    };
}

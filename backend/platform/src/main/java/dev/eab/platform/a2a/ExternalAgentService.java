package dev.eab.platform.a2a;

import dev.eab.a2a.A2aOutbound;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.RegistryResult;
import dev.eab.spec.AssetParser;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.ConnectionAsset;
import dev.eab.spec.model.Asset.RemoteAgentAsset;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.a2aproject.sdk.spec.AgentCard;

/**
 * Studio 的“接入外部 Agent”（主提示词 10.5 节）：输入 Agent Card 地址，展示技能、交互模式和认证方式，
 * 做一次连通性测试（展示请求与响应原文），配置后发布为本租户的 RemoteAgent 资产，发布后即进入能力目录。
 *
 * <p>只允许访问本机回环地址，或本租户 Connection 资产、部署配置里已经登记过的地址，避免被当作任意地址的请求转发器。
 */
public final class ExternalAgentService {

    static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
    static final Pattern ALIAS = Pattern.compile("^[A-Z][A-Z0-9_]{1,62}$");
    static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /** Agent Card 地址拆成连接地址和卡片路径 */
    record CardLocation(String origin, String baseUrl, String cardPath) {}

    private final PlatformServices services;
    private final RemoteAgentGateway gateway;

    public ExternalAgentService(PlatformServices services, RemoteAgentGateway gateway) {
        this.services = services;
        this.gateway = gateway;
    }

    static CardLocation locate(String cardUrl) {
        URI uri;
        try {
            uri = URI.create(cardUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "BAD_REQUEST", "Agent Card 地址不是合法的 URL");
        }
        if (uri.getScheme() == null
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null) {
            throw new ApiException(400, "BAD_REQUEST", "Agent Card 地址必须是 http 或 https 的完整 URL");
        }
        String origin = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String path = uri.getPath() == null ? "" : uri.getPath();
        int wellKnown = path.indexOf("/.well-known/");
        if (wellKnown >= 0)
            return new CardLocation(origin, origin + path.substring(0, wellKnown), path.substring(wellKnown));
        String base = origin + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
        return new CardLocation(origin, base, "/.well-known/agent-card.json");
    }

    static String originOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private List<ConnectionAsset> connections(String tenantId) {
        List<ConnectionAsset> result = new ArrayList<>();
        for (Asset asset : services.registry()
                .latestPublished(new AssetFilter("Connection", null, "tenant", tenantId, null), Viewer.tenant(tenantId))
                .assets()) {
            if (asset instanceof ConnectionAsset c) result.add(c);
        }
        return result;
    }

    /** 与 Agent Card 同源的连接别名：先找租户的 Connection 资产，再找部署配置 */
    Optional<String> aliasFor(String tenantId, String origin) {
        for (ConnectionAsset c : connections(tenantId)) {
            if (c.spec().bindings() == null) continue;
            boolean same = c.spec().bindings().values().stream()
                    .anyMatch(b ->
                            b != null && b.url() != null && originOf(b.url()).equals(origin));
            if (same) return Optional.of(c.spec().alias());
        }
        return gateway.settings().connections().entrySet().stream()
                .filter(e -> originOf(e.getValue()).equals(origin))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    void checkAllowed(String tenantId, CardLocation location) {
        String host = URI.create(location.origin()).getHost();
        if (LOOPBACK.contains(host) || aliasFor(tenantId, location.origin()).isPresent()) return;
        throw new ApiException(403, "A2A_HOST_NOT_ALLOWED", "只能接入本机地址或已在连接中心登记过的地址，请先在连接中心登记 " + location.origin());
    }

    private A2aOutbound.CardResult fetch(DemoUser user, CardLocation location) {
        checkAllowed(user.tenantId(), location);
        try {
            return A2aOutbound.fetchCard(location.baseUrl(), location.cardPath(), Map.of());
        } catch (A2aOutbound.A2aException e) {
            throw new ApiException(502, e.code(), e.getMessage());
        }
    }

    /** 拉取并解读 Agent Card，给出连接别名、资产名和路由说明的建议 */
    public Map<String, Object> inspect(DemoUser user, String cardUrl) {
        CardLocation location = locate(cardUrl);
        var fetched = fetch(user, location);
        AgentCard card = fetched.card();
        String alias = aliasFor(user.tenantId(), location.origin())
                .orElse("EXTERNAL_AGENT_" + Math.abs(location.origin().hashCode() % 10000));
        Optional<RemoteAgentAsset> existing = remoteByAlias(user.tenantId(), alias);
        List<String> examples = new ArrayList<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        for (var skill : card.skills()) {
            if (skill.examples() != null) examples.addAll(skill.examples());
            skills.add(Map.of(
                    "id", skill.id(),
                    "name", skill.name(),
                    "description", skill.description() == null ? "" : skill.description(),
                    "tags", skill.tags() == null ? List.of() : skill.tags(),
                    "examples", skill.examples() == null ? List.of() : skill.examples()));
        }
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("name", existing.map(r -> r.metadata().name()).orElse(slug(card)));
        suggestion.put("displayName", card.name());
        suggestion.put("connection", alias);
        suggestion.put("agentCardPath", location.cardPath());
        suggestion.put("routing", Map.of("description", card.description(), "examples", examples, "direct", false));
        existing.ifPresent(r -> suggestion.put(
                "existing", RemoteAgentGateway.refOf(r) + "@" + r.metadata().version()));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("card", summary(card));
        view.put("skills", skills);
        view.put("raw", fetched.json());
        view.put("exchanges", RemoteAgentGateway.exchanges(fetched.exchanges()));
        view.put("suggestion", suggestion);
        return view;
    }

    static Map<String, Object> summary(AgentCard card) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", card.name());
        map.put("description", card.description());
        map.put("version", card.version());
        map.put("streaming", Boolean.TRUE.equals(card.capabilities().streaming()));
        map.put("pushNotifications", Boolean.TRUE.equals(card.capabilities().pushNotifications()));
        map.put("inputModes", card.defaultInputModes());
        map.put("outputModes", card.defaultOutputModes());
        map.put(
                "auth",
                card.securitySchemes() == null
                        ? List.of()
                        : List.copyOf(card.securitySchemes().keySet()));
        map.put(
                "interfaces",
                card.supportedInterfaces().stream()
                        .map(i -> i.protocolBinding() + " " + i.url())
                        .toList());
        return map;
    }

    static String slug(AgentCard card) {
        String id = card.skills().isEmpty() ? "" : card.skills().getFirst().id();
        String slug = id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("^-+|-+$", "");
        return NAME.matcher(slug).matches() ? slug : "external-agent";
    }

    Optional<RemoteAgentAsset> remoteByAlias(String tenantId, String alias) {
        return services
                .registry()
                .latestPublished(
                        new AssetFilter("RemoteAgent", null, "tenant", tenantId, null), Viewer.tenant(tenantId))
                .assets()
                .stream()
                .filter(a ->
                        a instanceof RemoteAgentAsset r && alias.equals(r.spec().connection()))
                .map(a -> (RemoteAgentAsset) a)
                .findFirst();
    }

    /** 本租户已登记的远程 Agent */
    public List<Map<String, Object>> remotes(String tenantId) {
        return services
                .registry()
                .latestPublished(
                        new AssetFilter("RemoteAgent", null, "tenant", tenantId, null), Viewer.tenant(tenantId))
                .assets()
                .stream()
                .filter(a -> a instanceof RemoteAgentAsset)
                .map(a -> {
                    var r = (RemoteAgentAsset) a;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ref", RemoteAgentGateway.refOf(r));
                    item.put("version", r.metadata().version());
                    item.put("name", RemoteAgentGateway.displayName(r));
                    item.put("connection", r.spec().connection());
                    item.put("assistant", r.spec().assistant());
                    return item;
                })
                .toList();
    }

    /** 连通性测试：以开发者本人为主体签发服务令牌发一条消息，返回对方的回复和请求、响应原文 */
    public Map<String, Object> test(DemoUser user, String cardUrl, String message) {
        CardLocation location = locate(cardUrl);
        var fetched = fetch(user, location);
        String bearer = services.tokens()
                .issueService(user.id(), user.tenantId(), "studio:connectivity-test", location.origin());
        Map<String, Object> view = new LinkedHashMap<>();
        List<Map<String, Object>> exchanges = new ArrayList<>(RemoteAgentGateway.exchanges(fetched.exchanges()));
        try {
            var outcome = A2aOutbound.send(fetched.card(), bearer, message, null, null, TEST_TIMEOUT, null);
            exchanges.addAll(RemoteAgentGateway.exchanges(outcome.exchanges()));
            view.put("ok", true);
            view.put("state", outcome.state());
            view.put("text", outcome.text());
            view.put("taskId", outcome.taskId());
        } catch (A2aOutbound.A2aException e) {
            view.put("ok", false);
            view.put("state", "failed");
            view.put("error", Map.of("code", e.code(), "message", e.getMessage()));
        }
        view.put("exchanges", exchanges);
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                "a2a.connectivity-test",
                                location.origin(),
                                Boolean.TRUE.equals(view.get("ok")) ? "allow" : "deny",
                                "连通性测试：" + view.get("state"))
                        .tenantId(user.tenantId()));
        return view;
    }

    /**
     * 发布外部 Agent 的配置。
     *
     * @param routing description、examples、direct
     * @param assistant 所属岗位助手，为空时由统一入口直达
     */
    public record PublishRequest(
            String cardUrl,
            String name,
            String displayName,
            String connection,
            Map<String, Object> routing,
            String assistant,
            List<String> allowedCallers) {}

    public Map<String, Object> publish(DemoUser user, PublishRequest request) {
        if (request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "名称只能用小写字母、数字和连字符，以字母开头");
        }
        if (request.connection() == null || !ALIAS.matcher(request.connection()).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "连接别名只能用大写字母、数字和下划线");
        }
        CardLocation location = locate(request.cardUrl());
        checkAllowed(user.tenantId(), location);
        String tenant = user.tenantId();
        List<String> published = new ArrayList<>();
        if (aliasFor(tenant, location.origin())
                        .filter(request.connection()::equals)
                        .isEmpty()
                && connections(tenant).stream().noneMatch(c -> c.spec().alias().equals(request.connection()))) {
            published.add(write(user, connectionAsset(user, request.connection(), location.baseUrl())));
        }
        published.add(write(user, remoteAsset(user, request, location.cardPath())));
        services.audit()
                .append(NewAuditEvent.of(user.id(), "a2a.remote.publish", published.getLast(), "allow", "接入外部 Agent")
                        .tenantId(tenant));
        return Map.of("published", published, "ref", published.getLast());
    }

    Map<String, Object> remoteAsset(DemoUser user, PublishRequest request, String cardPath) {
        Map<String, Object> routing = new LinkedHashMap<>();
        Object description =
                request.routing() == null ? null : request.routing().get("description");
        routing.put(
                "description",
                description == null || description.toString().isBlank() ? request.displayName() : description);
        routing.put(
                "examples",
                request.routing() == null ? List.of() : request.routing().getOrDefault("examples", List.of()));
        routing.put(
                "direct",
                request.routing() != null
                        && Boolean.TRUE.equals(request.routing().get("direct")));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("connection", request.connection());
        spec.put("agentCardPath", cardPath);
        spec.put("auth", Map.of("type", "platform-service-token"));
        spec.put("routing", routing);
        spec.put("allowedCallers", request.allowedCallers() == null ? List.of() : request.allowedCallers());
        if (request.assistant() != null && !request.assistant().isBlank()) spec.put("assistant", request.assistant());
        return envelope(
                "RemoteAgent",
                "tenant." + user.tenantId() + ".remote",
                request.name(),
                request.displayName(),
                user,
                spec);
    }

    static Map<String, Object> connectionAsset(DemoUser user, String alias, String url) {
        Map<String, Object> binding = Map.of("url", url, "auth", Map.of("type", "platform-service-token"));
        Map<String, Object> bindings = new LinkedHashMap<>();
        for (String env : List.of("private", "dev", "test", "prod")) bindings.put(env, binding);
        String name = alias.toLowerCase(Locale.ROOT).replace('_', '-');
        return envelope(
                "Connection",
                "tenant." + user.tenantId() + ".connections",
                name,
                null,
                user,
                Map.of("alias", alias, "type", "a2a", "bindings", bindings));
    }

    static Map<String, Object> envelope(
            String kind, String namespace, String name, String displayName, DemoUser user, Map<String, Object> spec) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespace);
        metadata.put("version", "1.0.0");
        if (displayName != null && !displayName.isBlank()) metadata.put("displayName", displayName);
        metadata.put("owner", user.id());
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", kind);
        asset.put("metadata", metadata);
        asset.put("spec", spec);
        return asset;
    }

    /** 写入并发布；同名已有不同内容的版本时升补丁版本号，内容相同时直接复用 */
    @SuppressWarnings("unchecked")
    String write(DemoUser user, Map<String, Object> asset) {
        Viewer viewer = Viewer.tenant(user.tenantId());
        Map<String, Object> metadata = (Map<String, Object>) asset.get("metadata");
        for (int patch = 0; patch < 100; patch++) {
            metadata.put("version", "1.0." + patch);
            String yaml = Json.YAML.writeValueAsString(asset);
            var parsed = AssetParser.parseYaml(yaml);
            if (!parsed.ok()) {
                throw new ApiException(400, "ASSET_INVALID", "生成的资产校验未通过：" + parsed.issues());
            }
            var result = services.registry().publishAsset(viewer, parsed.asset(), yaml);
            if (result instanceof RegistryResult.Ok<?>) return parsed.asset().versionedRef();
            var error = ((RegistryResult.Err<?>) result).error();
            if (error.code() != RegistryResult.ErrorCode.VERSION_EXISTS) {
                throw new ApiException(400, error.code().name(), error.message());
            }
        }
        throw new ApiException(409, "VERSION_EXHAUSTED", "版本号已用尽");
    }
}

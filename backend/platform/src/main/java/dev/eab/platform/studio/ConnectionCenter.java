package dev.eab.platform.studio;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.kinds.OtherSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.ConnectionAsset;
import dev.eab.spec.model.Asset.RemoteAgentAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.CatalogSpecs.Binding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;

/**
 * 连接中心（主提示词 10.8.2 节）：列出本租户的连接别名和每个环境的绑定，租户管理员可以修改绑定。
 * 资产里只引用别名，地址、认证方式和凭据引用只出现在 Connection 资产里；修改绑定会写入 Connection 的新补丁版本并发布。
 */
public final class ConnectionCenter {

    /** 绑定允许的认证方式，与 eab-spec 的 Connection schema 一致 */
    public static final List<String> AUTH_TYPES =
            List.of("none", "client-credentials", "token-exchange", "platform-service-token");

    /** 凭据只能写引用，与 schema 的格式一致 */
    static final String CREDENTIAL_REF = "^(env|vault):[A-Za-z0-9_./-]+$";

    public record BindingView(String url, Map<String, Object> auth) {}

    /**
     * @param editable 当前查看者能否修改绑定（租户管理员），前端据此显示编辑入口
     */
    public record ConnectionView(
            String alias,
            String type,
            String ref,
            String version,
            Map<String, BindingView> bindings,
            List<String> usedBy,
            boolean editable) {

        public ConnectionView withEditable(boolean value) {
            return new ConnectionView(alias, type, ref, version, bindings, usedBy, value);
        }
    }

    /** 修改被拒绝或参数不合法 */
    public static final class ConnectionException extends RuntimeException {
        private final int status;
        private final String code;

        public ConnectionException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final PlatformServices services;

    public ConnectionCenter(PlatformServices services) {
        this.services = services;
    }

    static String namespaceOf(String tenantId) {
        return "tenant." + tenantId + ".connections";
    }

    private List<Asset> published(String tenantId) {
        return services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
    }

    public List<ConnectionView> list(String tenantId) {
        List<Asset> assets = published(tenantId);
        List<ConnectionView> views = new ArrayList<>();
        for (Asset asset : assets) {
            if (asset instanceof ConnectionAsset c && c.metadata().namespace().equals(namespaceOf(tenantId))) {
                views.add(view(c, assets));
            }
        }
        views.sort(Comparator.comparing(ConnectionView::alias));
        return views;
    }

    private static String refOf(Asset asset) {
        return asset.metadata().namespace() + "/" + asset.metadata().name();
    }

    static ConnectionView view(ConnectionAsset c, List<Asset> assets) {
        Map<String, BindingView> bindings = new LinkedHashMap<>();
        for (String env : OtherSchemas.ENVIRONMENTS) {
            Binding b = c.spec().bindings() == null ? null : c.spec().bindings().get(env);
            if (b == null) continue;
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("type", b.auth().type());
            if (b.auth().credentialRef() != null)
                auth.put("credentialRef", b.auth().credentialRef());
            bindings.put(env, new BindingView(b.url(), auth));
        }
        return new ConnectionView(
                c.spec().alias(),
                c.spec().type(),
                refOf(c),
                c.metadata().version(),
                bindings,
                usedBy(c.spec().alias(), assets),
                false);
    }

    /** 直接引用这个别名的工具来源和远程 Agent，以及挂载了这些工具来源的 Agent */
    static List<String> usedBy(String alias, List<Asset> assets) {
        Set<String> sources = new LinkedHashSet<>();
        Set<String> used = new LinkedHashSet<>();
        for (Asset asset : assets) {
            if (asset instanceof ToolSourceAsset t && alias.equals(t.spec().connection())) {
                sources.add(refOf(t));
                used.add(refOf(t));
            } else if (asset instanceof RemoteAgentAsset r
                    && alias.equals(r.spec().connection())) {
                used.add(refOf(r));
            }
        }
        for (Asset asset : assets) {
            if (!(asset instanceof AgentAsset a) || a.spec().tools() == null) continue;
            for (var entry : a.spec().tools()) {
                var normalized = entry.normalize();
                if (normalized.ref() == null) continue;
                String ref = normalized.ref().contains("@")
                        ? normalized.ref().substring(0, normalized.ref().indexOf('@'))
                        : normalized.ref();
                if (sources.contains(ref)) used.add(refOf(a));
            }
        }
        return List.copyOf(used);
    }

    static String nextPatch(String version) {
        String[] parts = version.split("[.-]");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    /**
     * 修改一个环境的绑定。
     *
     * @param actor 操作人，必须是本租户的租户管理员（由调用方检查角色）
     */
    public ConnectionView updateBinding(
            String tenantId,
            String actor,
            String alias,
            String environment,
            String url,
            String authType,
            String credentialRef) {
        if (!OtherSchemas.ENVIRONMENTS.contains(environment)) {
            throw new ConnectionException(400, "BAD_REQUEST", "环境只能是 " + String.join("、", OtherSchemas.ENVIRONMENTS));
        }
        if (url == null || !url.matches("^https?://[^\\s/]+(/\\S*)?$")) {
            throw new ConnectionException(400, "BAD_REQUEST", "地址必须是完整的 http 或 https URL");
        }
        if (authType == null || !AUTH_TYPES.contains(authType)) {
            throw new ConnectionException(400, "BAD_REQUEST", "认证方式只能是 " + String.join("、", AUTH_TYPES));
        }
        String ref = credentialRef == null || credentialRef.isBlank() ? null : credentialRef.strip();
        if (ref != null && !ref.matches(CREDENTIAL_REF)) {
            throw new ConnectionException(
                    400, "BAD_REQUEST", "凭据只能写引用，例如 env:DEMO_ERP_CLIENT_SECRET 或 vault:erp/client，不能写凭据本身");
        }
        List<Asset> assets = published(tenantId);
        Optional<ConnectionAsset> found = assets.stream()
                .filter(ConnectionAsset.class::isInstance)
                .map(ConnectionAsset.class::cast)
                .filter(c -> c.metadata().namespace().equals(namespaceOf(tenantId))
                        && c.spec().alias().equals(alias))
                .findFirst();
        if (found.isEmpty()) throw new ConnectionException(404, "NOT_FOUND", "连接别名 " + alias + " 不存在");
        ConnectionAsset current = found.get();

        Viewer viewer = Viewer.tenant(tenantId);
        var m = current.metadata();
        var stored = services.registry().view(viewer, m.namespace(), m.name(), m.version());
        if (!stored.ok()) throw new ConnectionException(404, "NOT_FOUND", "连接别名 " + alias + " 不存在");
        ObjectNode tree = stored.value().asset().deepCopy();
        Set<String> existing = new LinkedHashSet<>();
        for (var summary :
                services.registry().list(viewer, new AssetFilter("Connection", m.namespace(), null, null, null))) {
            if (summary.name().equals(m.name())) existing.add(summary.version());
        }
        String version = nextPatch(m.version());
        while (existing.contains(version)) version = nextPatch(version);
        ((ObjectNode) tree.get("metadata")).put("version", version);
        ObjectNode binding =
                ((ObjectNode) tree.get("spec")).withObject("bindings").putObject(environment);
        binding.put("url", url);
        ObjectNode auth = binding.putObject("auth");
        auth.put("type", authType);
        if (ref != null) auth.put("credentialRef", ref);

        String yaml = Json.YAML.writeValueAsString(tree);
        String note = actor + " 修改了 " + alias + " 在 " + environment + " 环境的绑定";
        var put = services.registry().put(viewer, yaml, note);
        if (!put.ok()) {
            throw new ConnectionException(
                    400, "INVALID_BINDING", "绑定没有保存：" + put.error().message());
        }
        var published = services.registry().setStatus(viewer, m.namespace(), m.name(), version, Status.published);
        if (!published.ok()) {
            throw new ConnectionException(
                    409, "PUBLISH_FAILED", "绑定已保存但没有发布：" + published.error().message());
        }
        services.audit()
                .append(NewAuditEvent.of(
                                actor,
                                "connection.binding",
                                alias + "@" + environment,
                                "allow",
                                note + "，版本 " + version)
                        .tenantId(tenantId)
                        .userId(actor));
        return list(tenantId).stream()
                .filter(v -> v.alias().equals(alias))
                .findFirst()
                .orElseThrow();
    }
}

package dev.eab.platform.tools;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.studio.StudioReleases;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * “未审批的 MCP Server 不能用在生产环境”（主提示词 8.3 节）：Agent 晋级 prod、扩展发布（发布即对本租户生效）时检查它用到的
 * mcp 工具来源。标品的 mcp 工具来源由厂商审核，不在租户审批范围内。
 */
public final class McpGate implements StudioReleases.ProdGate {

    private final PlatformServices services;
    private final McpApprovalStore approvals;

    public McpGate(PlatformServices services, McpApprovalStore approvals) {
        this.services = services;
        this.approvals = approvals;
    }

    /** 资产用到的租户 mcp 工具来源 */
    public Set<String> mcpSources(String tenantId, Asset asset) {
        List<String> refs = new ArrayList<>();
        if (asset instanceof AgentAsset agent) {
            for (var entry : agent.spec().tools()) {
                var n = entry.normalize();
                if (n.ref() != null && (n.type().equals("mcp") || n.type().equals("api"))) refs.add(n.ref());
            }
        } else if (asset instanceof AgentExtensionAsset ext && ext.spec().tools() != null) {
            for (var entry : ext.spec().tools().add()) {
                Object v = Json.MAPPER.convertValue(entry, Object.class);
                if (v instanceof String ref) refs.add(ref);
                else if (v instanceof java.util.Map<?, ?> m && m.get("mcp") instanceof java.util.Map<?, ?> mcp) {
                    refs.add(String.valueOf(mcp.get("source")));
                }
            }
        }
        var resolver = services.registry().resolverFor(Viewer.tenant(tenantId));
        Set<String> sources = new LinkedHashSet<>();
        for (String ref : refs) {
            resolver.resolve(ref, AssetResolver.LIVE)
                    .filter(a -> a instanceof ToolSourceAsset s
                            && "mcp".equals(s.spec().type()))
                    .filter(a -> a.metadata().namespace().startsWith("tenant."))
                    .ifPresent(a -> sources.add(AssetRef.parse(ref).key()));
        }
        return sources;
    }

    /** 没有审批通过的 mcp 工具来源 */
    public List<String> unapproved(String tenantId, Asset asset) {
        return mcpSources(tenantId, asset).stream()
                .filter(s -> !approvals.approved(tenantId, s))
                .toList();
    }

    @Override
    public List<StudioReleases.Gate> check(String tenantId, Asset asset) {
        Set<String> sources = mcpSources(tenantId, asset);
        if (sources.isEmpty()) return List.of();
        List<String> missing = unapproved(tenantId, asset);
        return List.of(new StudioReleases.Gate(
                "MCP Server 已审批",
                missing.isEmpty(),
                missing.isEmpty() ? "用到的 MCP Server 都已审批通过" : "未审批通过：" + String.join("、", missing)));
    }
}

package dev.eab.a2a.sdk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把代码 Agent 声明为平台资产：生成 RemoteAgent 资产（只引用连接别名，地址在连接中心按环境绑定），
 * 发布后它和低代码 Agent 一样进入能力目录、可以挂到岗位助手、被其他 Agent 当作工具调用，调用时由平台签发服务令牌。
 */
public final class AgentDeclaration {

    private AgentDeclaration() {}

    /**
     * @param tenantId 资产发布到 tenant.&lt;租户&gt;.remote 命名空间
     * @param connection 连接别名，例如 PRICE_BENCHMARK_AGENT
     * @param assistant 所属岗位助手，为 null 时统一入口直达
     */
    public static Map<String, Object> remoteAgent(
            CodeAgentServer.Description d,
            String tenantId,
            String owner,
            String connection,
            String assistant) {
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("description", d.description());
        routing.put("examples", d.examples());
        routing.put("direct", assistant == null);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("connection", connection);
        spec.put("agentCardPath", "/.well-known/agent-card.json");
        spec.put("auth", Map.of("type", "platform-service-token"));
        spec.put("routing", routing);
        spec.put("allowedCallers", List.of());
        if (assistant != null) spec.put("assistant", assistant);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", d.skillId());
        metadata.put("namespace", "tenant." + tenantId + ".remote");
        metadata.put("version", "1.0.0");
        metadata.put("displayName", d.name());
        metadata.put("description", "代码 Agent：" + d.description());
        metadata.put("owner", owner);
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "RemoteAgent");
        asset.put("metadata", metadata);
        asset.put("spec", spec);
        return asset;
    }
}

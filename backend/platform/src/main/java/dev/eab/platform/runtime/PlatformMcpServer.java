package dev.eab.platform.runtime;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.Principals;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.type.TypeReference;

/**
 * 平台对外的 MCP Server 端点（主提示词 8.3 节）：外部的 MCP 客户端（例如 Claude Desktop、Claude Code）带用户令牌访问，
 * 能用的工具是只读的 BO 操作和语义查询工具。每次调用以平台身份 agent:platform/mcp-endpoint 做令牌交换，
 * 和 Agent 的工具调用走同一个工具网关：鉴权点 3、审计、追踪都一样，BO API 里还有鉴权点 4，用户在 ERP 里做不了的事这里也做不了。
 *
 * <p>传输用 MCP 官方 Java SDK 的无状态 Streamable HTTP（Spring WebMVC），路径 /mcp。
 */
public final class PlatformMcpServer {

    public static final String ENDPOINT = "/mcp";
    static final String AUTHORIZATION = "authorization";

    private final RuntimeService runtime;
    private final Principals principals;
    private final WebMvcStatelessServerTransport transport;
    private final McpStatelessSyncServer server;

    public PlatformMcpServer(RuntimeService runtime, Principals principals) {
        this.runtime = runtime;
        this.principals = principals;
        this.transport = WebMvcStatelessServerTransport.builder()
                .messageEndpoint(ENDPOINT)
                .contextExtractor(request -> McpTransportContext.create(Map.of(
                        AUTHORIZATION,
                        request.headers().firstHeader("Authorization") == null
                                ? ""
                                : request.headers().firstHeader("Authorization"))))
                .build();
        this.server = McpServer.sync(transport)
                .serverInfo("eab-platform", "0.1.0")
                .instructions("ERP Agent Builder 平台的只读 ERP 工具：业务对象查询与语义口径。调用按当前用户的业务权限与数据权限裁决。")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
    }

    /**
     * 按注册中心里已发布的工具来源重新登记工具。平台启动时先创建端点、再签名入库标品资产，入库之后调用一次；
     * BO 工具来源升级后也调用它。
     */
    public synchronized void refreshTools() {
        for (var existing : server.listTools()) server.removeTool(existing.name());
        for (SyncToolSpecification spec : tools()) server.addTool(spec);
    }

    public List<String> toolNames() {
        return server.listTools().stream().map(McpSchema.Tool::name).toList();
    }

    public RouterFunction<ServerResponse> router() {
        return transport.getRouterFunction();
    }

    public void close() {
        server.close();
    }

    private List<SyncToolSpecification> tools() {
        List<SyncToolSpecification> specs = new ArrayList<>();
        for (RuntimeService.McpTool tool : runtime.mcpTools()) {
            var schema = Json.MAPPER.readValue(tool.inputSchema(), new TypeReference<Map<String, Object>>() {});
            var definition = McpSchema.Tool.builder()
                    .name(tool.mcpName())
                    .description(tool.description())
                    .inputSchema(schema)
                    .build();
            specs.add(new SyncToolSpecification(definition, (context, request) -> call(context, tool, request)));
        }
        return specs;
    }

    private McpSchema.CallToolResult call(
            McpTransportContext context, RuntimeService.McpTool tool, McpSchema.CallToolRequest request) {
        DemoUser user;
        String token;
        try {
            String header = String.valueOf(context.get(AUTHORIZATION));
            var principal = principals.verify(header);
            if (!(principal.viewer() instanceof Viewer.Tenant)) {
                return error("只有租户用户可以通过 MCP 访问业务数据");
            }
            user = principal.user();
            token = principal.token();
        } catch (ApiException e) {
            return error("缺少或无效的用户令牌：" + e.getMessage());
        }
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        ToolOutcome outcome = runtime.callAsMcp(user, token, tool.name(), args);
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(outcome.content())), !outcome.ok(), null, null);
    }

    private static McpSchema.CallToolResult error(String message) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(message)), true, null, null);
    }
}

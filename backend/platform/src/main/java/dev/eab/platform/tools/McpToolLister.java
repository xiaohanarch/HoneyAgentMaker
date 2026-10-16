package dev.eab.platform.tools;

import dev.eab.spec.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 登记 MCP Server 时拉取它的工具清单（MCP 官方 Java SDK 的 Streamable HTTP 客户端，与运行时调用外部 MCP 的方式一致） */
final class McpToolLister {

    private McpToolLister() {}

    record McpTool(String name, String description, Map<String, Object> inputSchema) {}

    static List<McpTool> list(String url) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getPath() == null || uri.getPath().isBlank() ? "/mcp" : uri.getPath();
        var transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build();
        try {
            client.initialize();
            List<McpTool> tools = new ArrayList<>();
            for (McpSchema.Tool tool : client.listTools().tools()) {
                Map<String, Object> schema = new LinkedHashMap<>();
                if (tool.inputSchema() != null) {
                    schema.putAll(Json.MAPPER.convertValue(tool.inputSchema(), Map.class));
                }
                tools.add(new McpTool(
                        tool.name(), tool.description() == null ? tool.name() : tool.description(), schema));
            }
            return tools;
        } finally {
            client.closeGracefully();
        }
    }
}

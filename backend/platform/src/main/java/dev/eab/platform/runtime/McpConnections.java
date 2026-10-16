package dev.eab.platform.runtime;

import dev.eab.runtime.Ports;
import dev.eab.spec.Json;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 的工具调用（主提示词 8.3 节）：资产里只写连接别名，地址来自本环境的连接绑定；没有绑定的别名一律拒绝。
 * 用 MCP 官方 Java SDK 的 Streamable HTTP 客户端，每次调用建立一次会话，演示足够，也不会在连接之间串数据。
 */
final class McpConnections implements Ports.Mcp {

    private final Map<String, String> bindings;

    McpConnections(Map<String, String> bindings) {
        this.bindings = Map.copyOf(bindings);
    }

    @Override
    public Object call(String connection, String tool, Map<String, Object> arguments) {
        String url = bindings.get(connection);
        if (url == null) throw new IllegalStateException("连接 " + connection + " 没有在本环境绑定，不能调用外部 MCP Server");
        return callUrl(url, tool, arguments);
    }

    static Object callUrl(String url, String tool, Map<String, Object> arguments) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getPath() == null || uri.getPath().isBlank() ? "/mcp" : uri.getPath();
        var transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        try {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
            String text = text(result);
            if (Boolean.TRUE.equals(result.isError())) {
                throw new IllegalStateException("外部 MCP 工具 " + tool + " 返回错误：" + text);
            }
            if (result.structuredContent() != null) return result.structuredContent();
            try {
                return Json.MAPPER.readValue(text, Object.class);
            } catch (RuntimeException e) {
                return Map.of("text", text);
            }
        } finally {
            client.closeGracefully();
        }
    }

    private static String text(McpSchema.CallToolResult result) {
        List<String> parts = new ArrayList<>();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent t) parts.add(t.text());
        }
        return String.join("\n", parts);
    }
}

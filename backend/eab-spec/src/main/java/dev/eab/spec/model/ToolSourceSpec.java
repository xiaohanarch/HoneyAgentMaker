package dev.eab.spec.model;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 工具来源（主提示词 5.1 节 ToolSource），资产里只写连接别名 */
public record ToolSourceSpec(String type, String connection, String openapiPath, List<ToolDefinition> tools) {

    /**
     * 一个工具。写操作级别由静态检查要求必须声明；付款、过账类最高只能到 L2（主提示词 7.4 节）。
     *
     * @param operation bo-api、http 工具对应的接口；地址由连接别名在部署时绑定
     */
    public record ToolDefinition(
            String name,
            String description,
            String writeLevel,
            String category,
            Operation operation,
            JsonNode inputSchema,
            JsonNode outputSchema) {}

    public record Operation(String method, String path) {}
}

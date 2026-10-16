package dev.eab.runtime.model;

import java.util.List;
import java.util.Map;

/**
 * 模型网关的输入输出。对话用平台自己的消息类型保存，可以直接序列化进运行日志；发给模型前再转换成 Spring AI 的消息。
 */
public final class ModelTypes {

    private ModelTypes() {}

    /** 模型请求的一次工具调用；arguments 已解析为对象 */
    public record ToolCall(String id, String name, Map<String, Object> arguments) {}

    /** 对话里的一条消息 */
    public sealed interface Turn permits UserTurn, AssistantTurn, ToolTurn {}

    public record UserTurn(String text) implements Turn {}

    public record AssistantTurn(String text, List<ToolCall> toolCalls) implements Turn {}

    /** @param content 工具结果，JSON 文本 */
    public record ToolTurn(String callId, String name, String content) implements Turn {}

    /** 暴露给模型的工具，inputSchema 是 JSON Schema 文本 */
    public record ToolSpec(String name, String description, String inputSchema) {}

    /**
     * 脚本化模型和回退回复需要的运行上下文，真实模型不使用。
     *
     * @param agent namespace/name
     * @param examples Agent 的示例话术，剧本未覆盖时列给用户
     */
    public record CallContext(String agent, String agentDisplayName, String userId, List<String> examples) {}

    /**
     * 一次模型调用的结果，写进运行日志，恢复运行时原样重放，不再调用模型。
     *
     * @param output 结构化结果；脚本化模型由剧本给出，真实模型从回复里的 JSON 代码块解析，没有时为 null
     * @param stopReason end、tool_use、blocked
     * @param scriptId 脚本化模型命中的剧本编号；未命中时为 fallback；真实模型为 null
     * @param leakedSegments 回复里被遮蔽的保密片段编号，监督层据此产生告警
     */
    public record ModelResult(
            String text,
            List<ToolCall> toolCalls,
            Map<String, Object> output,
            long inputTokens,
            long outputTokens,
            boolean estimated,
            String provider,
            String model,
            String stopReason,
            String scriptId,
            double costUsd,
            boolean inputBlocked,
            List<String> leakedSegments) {}

    public static final class ModelException extends RuntimeException {
        private final String code;

        public ModelException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}

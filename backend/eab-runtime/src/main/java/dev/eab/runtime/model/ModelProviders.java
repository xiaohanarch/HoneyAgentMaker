package dev.eab.runtime.model;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按配置创建模型提供方：脚本化（默认）、Anthropic 协议、OpenAI 兼容协议。后两种用 Spring AI 的官方模块，
 * Key 和地址只来自环境变量；模型名每次调用按档位指定。
 */
public final class ModelProviders {

    private ModelProviders() {}

    public static ChatModel create(GatewayConfig config, List<Scripts.Script> scripts, Supplier<String> today) {
        return switch (config.provider()) {
            case "anthropic" ->
                AnthropicChatModel.builder()
                        .options(AnthropicChatOptions.builder()
                                .apiKey(config.apiKey())
                                .baseUrl(config.baseUrl())
                                .model(config.modelFor("reasoning"))
                                .maxTokens(config.maxTokens())
                                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                                .maxRetries(config.maxRetries())
                                .build())
                        .build();
            case "openai" ->
                OpenAiChatModel.builder()
                        .options(OpenAiChatOptions.builder()
                                .apiKey(config.apiKey())
                                .baseUrl(config.baseUrl())
                                .model(config.modelFor("reasoning"))
                                .maxTokens(config.maxTokens())
                                .timeout(java.time.Duration.ofSeconds(config.timeoutSeconds()))
                                .maxRetries(config.maxRetries())
                                .build())
                        .build();
            default -> new ScriptedChatModel(scripts, today);
        };
    }
}

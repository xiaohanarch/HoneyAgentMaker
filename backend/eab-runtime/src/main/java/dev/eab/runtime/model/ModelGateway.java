package dev.eab.runtime.model;

import dev.eab.runtime.model.ContentFilters.ConfidentialText;
import dev.eab.runtime.model.ModelTypes.AssistantTurn;
import dev.eab.runtime.model.ModelTypes.CallContext;
import dev.eab.runtime.model.ModelTypes.ModelException;
import dev.eab.runtime.model.ModelTypes.ModelResult;
import dev.eab.runtime.model.ModelTypes.ToolCall;
import dev.eab.runtime.model.ModelTypes.ToolSpec;
import dev.eab.runtime.model.ModelTypes.ToolTurn;
import dev.eab.runtime.model.ModelTypes.Turn;
import dev.eab.runtime.model.ModelTypes.UserTurn;
import dev.eab.runtime.model.PromptTemplate.KnowledgeSnippet;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer.Span;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型网关（主提示词 8.2 节）：按档位解析模型，模型调用走一条处理管道：
 *
 * <pre>
 * 知识检索注入 → 提示模板 → 脱敏 → 输入过滤 → 调用模型 → 还原 → 输出过滤
 * </pre>
 *
 * 前两个环节在这里执行，后面的环节是 Spring AI 的 Advisor（{@link PipelineAdvisors}）。每个环节在追踪里是一个子 span；
 * 模型 span 保存提示词片段，读取时按查看者裁剪。
 *
 * <p>工具由运行时自己执行：调用模型时关闭 Spring AI 的自动工具执行，模型返回的工具调用原样交给运行时，
 * 运行时在执行前做鉴权与守卫，需要确认时可以挂起（ADR 0008）。
 */
public final class ModelGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TEXT_IN_TRACE = 2000;

    private final ChatClient client;
    private final GatewayConfig config;

    public ModelGateway(ChatModel chatModel, GatewayConfig config) {
        this.client = ChatClient.builder(chatModel).build();
        this.config = config;
    }

    public GatewayConfig config() {
        return config;
    }

    public String providerName() {
        return config.provider();
    }

    /**
     * @param prompt 提示模板的输入：有效定义的片段、用户上下文、长期记忆、结构化结果字段
     * @param masker 一次运行共用一个，占位符在多轮之间保持一致
     * @param retrieve 知识检索注入：按本轮话术从挂载的语义包里取相关的指标口径和规则
     */
    public record Request(
            String profile,
            Double temperature,
            PromptTemplate.Input prompt,
            List<Turn> messages,
            List<ToolSpec> tools,
            CallContext context,
            Masker masker,
            Function<String, List<KnowledgeSnippet>> retrieve) {}

    /** 模型要求的工具名只能用字母、数字、下划线和连字符，语义查询工具名里的点换成下划线 */
    static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String lastUser(List<Turn> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserTurn u) return u.text();
        }
        return "";
    }

    /** 只用于声明工具，Spring AI 不会调用它：工具由运行时执行 */
    private record DeclaredTool(ToolDefinition definition) implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new UnsupportedOperationException("工具由平台运行时执行，不经 Spring AI 调用");
        }
    }

    static List<Message> toMessages(String system, List<Turn> turns) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (Turn turn : turns) {
            switch (turn) {
                case UserTurn u -> messages.add(new UserMessage(u.text()));
                case AssistantTurn a -> {
                    List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                    for (ToolCall c : a.toolCalls()) {
                        calls.add(new AssistantMessage.ToolCall(
                                c.id(), "function", safeName(c.name()), JSON.writeValueAsString(c.arguments())));
                    }
                    AssistantMessage.Builder<?> builder =
                            AssistantMessage.builder().content(a.text() == null ? "" : a.text());
                    if (!calls.isEmpty()) builder.toolCalls(calls);
                    messages.add(builder.build());
                }
                case ToolTurn t ->
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(
                                    new ToolResponseMessage.ToolResponse(t.callId(), safeName(t.name()), t.content())))
                            .build());
            }
        }
        return messages;
    }

    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(arguments, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (RuntimeException e) {
            throw new ModelException("BAD_TOOL_ARGUMENTS", "模型返回的工具参数不是合法的 JSON：" + arguments, e);
        }
    }

    public ModelResult complete(Request request, Span parent, String keySegment) {
        String model = config.modelFor(request.profile());
        Span span = parent.child("模型调用 · " + request.profile(), SpanKind.model, keySegment, Map.of());
        span.set("gen_ai.system", config.provider())
                .set("gen_ai.request.model", model)
                .set("eab.model.profile", request.profile());
        try {
            String utterance = lastUser(request.messages());
            Span retrieval = span.child("pipeline.retrieval", SpanKind.pipeline, "pipeline.retrieval", Map.of());
            List<KnowledgeSnippet> knowledge =
                    request.retrieve() == null ? List.of() : request.retrieve().apply(utterance);
            retrieval
                    .set("eab.retrieval.count", knowledge.size())
                    .set(
                            "eab.retrieval.ids",
                            String.join(
                                    ",",
                                    knowledge.stream().map(KnowledgeSnippet::id).toList()))
                    .end();
            Span template = span.child("pipeline.template", SpanKind.pipeline, "pipeline.template", Map.of());
            PromptTemplate.Prompt prompt = PromptTemplate.build(request.prompt(), knowledge);
            template.set("eab.prompt.segments", prompt.segments().size()).end();
            span.promptSegments(prompt.segments());

            Map<String, String> originalNames = new HashMap<>();
            List<ToolCallback> tools = new ArrayList<>();
            for (ToolSpec tool : request.tools()) {
                String name = safeName(tool.name());
                originalNames.put(name, tool.name());
                tools.add(new DeclaredTool(ToolDefinition.builder()
                        .name(name)
                        .description(tool.description())
                        .inputSchema(tool.inputSchema())
                        .build()));
            }
            Map<String, Object> toolContext = new HashMap<>();
            CallContext context = request.context();
            toolContext.put(ScriptedChatModel.CONTEXT_AGENT, context.agent());
            toolContext.put(ScriptedChatModel.CONTEXT_AGENT_NAME, context.agentDisplayName());
            toolContext.put(ScriptedChatModel.CONTEXT_USER, context.userId());
            toolContext.put(ScriptedChatModel.CONTEXT_EXAMPLES, context.examples());
            var options = ToolCallingChatOptions.builder().model(model).toolContext(toolContext);
            if (!tools.isEmpty()) options.toolCallbacks(tools);
            if (request.temperature() != null) options.temperature(request.temperature());

            List<ConfidentialText> confidential = new ArrayList<>();
            for (var s : request.prompt().segments()) {
                if (s.confidential()) confidential.add(new ConfidentialText(s.id(), s.text()));
            }
            ChatClientResponse response = client.prompt()
                    .messages(toMessages(prompt.system(), request.messages()))
                    .options(options)
                    .advisors(a -> a.advisors(
                                    new PipelineAdvisors.OutputFilter(),
                                    new PipelineAdvisors.Masking(),
                                    new PipelineAdvisors.InputFilter())
                            .param(PipelineAdvisors.SPAN, span)
                            .param(PipelineAdvisors.MASKER, request.masker())
                            .param(PipelineAdvisors.CONFIDENTIAL, confidential)
                            .param(PipelineAdvisors.UTTERANCE, utterance))
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .call()
                    .chatClientResponse();

            ModelResult result = toResult(response, originalNames, request.masker(), model);
            span.set("gen_ai.response.model", result.model())
                    .set("gen_ai.response.finish_reasons", result.stopReason())
                    .set("gen_ai.usage.input_tokens", result.inputTokens())
                    .set("gen_ai.usage.output_tokens", result.outputTokens())
                    .set("eab.usage.estimated", result.estimated())
                    .set("eab.cost.usd", result.costUsd())
                    .set(
                            "eab.tool_calls",
                            String.join(
                                    ",",
                                    result.toolCalls().stream()
                                            .map(ToolCall::name)
                                            .toList()))
                    .set("eab.response.text", truncate(result.text()))
                    .set("eab.script.id", result.scriptId())
                    .end();
            return result;
        } catch (ModelException e) {
            span.fail(e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            span.fail(e.getMessage());
            throw new ModelException("MODEL_CALL_FAILED", "模型调用失败：" + e.getMessage(), e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_IN_TRACE ? text : text.substring(0, MAX_TEXT_IN_TRACE);
    }

    @SuppressWarnings("unchecked")
    private ModelResult toResult(
            ChatClientResponse response, Map<String, String> originalNames, Masker masker, String model) {
        boolean blocked = Boolean.TRUE.equals(response.context().get(PipelineAdvisors.BLOCKED));
        List<String> leaked = (List<String>) response.context().getOrDefault(PipelineAdvisors.LEAKED, List.of());
        ChatResponse chat = response.chatResponse();
        if (chat == null || chat.getResult() == null) {
            throw new ModelException("EMPTY_RESPONSE", "模型没有返回内容", null);
        }
        AssistantMessage output = chat.getResult().getOutput();
        List<ToolCall> calls = new ArrayList<>();
        for (var call : output.getToolCalls()) {
            calls.add(new ToolCall(
                    call.id(), originalNames.getOrDefault(call.name(), call.name()), parseArguments(call.arguments())));
        }
        String text = output.getText() == null ? "" : output.getText();
        Map<String, Object> structured = null;
        Object scripted = output.getMetadata().get(ScriptedChatModel.PROPERTY_OUTPUT);
        if (scripted instanceof Map<?, ?> map) {
            structured = (Map<String, Object>) masker.unmaskValue(map);
        } else if (calls.isEmpty()) {
            PromptTemplate.Extracted extracted = PromptTemplate.extractOutput(text);
            text = extracted.text();
            structured = extracted.output();
        }
        Object scriptId = output.getMetadata().get(ScriptedChatModel.PROPERTY_SCRIPT);
        Usage usage = chat.getMetadata() == null ? null : chat.getMetadata().getUsage();
        long input = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long outputTokens = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        String responseModel = chat.getMetadata() == null
                        || chat.getMetadata().getModel() == null
                        || chat.getMetadata().getModel().isEmpty()
                ? model
                : chat.getMetadata().getModel();
        String stopReason = blocked ? "blocked" : calls.isEmpty() ? "end" : "tool_use";
        var cost = config.estimateCost(model, input, outputTokens);
        return new ModelResult(
                text,
                calls,
                structured,
                input,
                outputTokens,
                config.provider().equals("scripted"),
                config.provider(),
                responseModel,
                stopReason,
                scriptId == null ? null : scriptId.toString(),
                cost.usd(),
                blocked,
                leaked);
    }
}

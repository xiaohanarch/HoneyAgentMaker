package dev.eab.runtime.model;

import dev.eab.runtime.model.Scripts.Matched;
import dev.eab.runtime.model.Scripts.Script;
import dev.eab.runtime.model.Scripts.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * 脚本化模型（主提示词 8.2 节），默认的模型提供方：按“Agent + 用户话术”匹配演示剧本，产出确定性的工具调用和回复，
 * 断网、没有 API Key 时演示也能完整跑通。它实现 Spring AI 的 {@link ChatModel}，与真实模型走同一条处理管道。
 *
 * <p>剧本需要知道是哪个 Agent、哪个用户在说话，这些由网关放在 {@link ToolCallingChatOptions#getToolContext()} 里，
 * 只在本地使用，不会发给真实模型。
 */
public final class ScriptedChatModel implements ChatModel {

    public static final String CONTEXT_AGENT = "eab.agent";
    public static final String CONTEXT_AGENT_NAME = "eab.agentName";
    public static final String CONTEXT_USER = "eab.userId";
    public static final String CONTEXT_EXAMPLES = "eab.examples";

    /** 剧本给出的结构化结果，放在 AssistantMessage 的属性里 */
    public static final String PROPERTY_OUTPUT = "eab.output";

    public static final String PROPERTY_SCRIPT = "eab.scriptId";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<Script> scripts;
    private final Supplier<String> today;

    /** @param today 模板里的 today，测试固定日期 */
    public ScriptedChatModel(List<Script> scripts, Supplier<String> today) {
        this.scripts = List.copyOf(scripts);
        this.today = today;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model("scripted").build();
    }

    private static int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getMessageType() == MessageType.USER) return i;
        }
        return -1;
    }

    private static Object parseContent(String content) {
        try {
            return JSON.readValue(content, Object.class);
        } catch (RuntimeException e) {
            return content;
        }
    }

    /** 模板变量：vars、本轮的工具结果（同名工具取最后一次）、当前用户、今天 */
    static Map<String, Object> templateView(
            List<Message> messages, Map<String, String> vars, String userId, String today) {
        Map<String, Object> tools = new LinkedHashMap<>();
        for (Message m : messages.subList(lastUserIndex(messages) + 1, messages.size())) {
            if (m instanceof ToolResponseMessage response) {
                for (var r : response.getResponses())
                    tools.put(Scripts.templateKey(r.name()), parseContent(r.responseData()));
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("vars", new LinkedHashMap<>(vars));
        view.put("tools", tools);
        view.put("user", Map.of("id", userId));
        view.put("today", today);
        view.put("month", today.length() >= 7 ? today.substring(0, 7) : today);
        return view;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Map<String, Object> context =
                prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null
                        ? options.getToolContext()
                        : Map.of();
        String agent = String.valueOf(context.getOrDefault(CONTEXT_AGENT, ""));
        String agentName = String.valueOf(context.getOrDefault(CONTEXT_AGENT_NAME, agent));
        String userId = String.valueOf(context.getOrDefault(CONTEXT_USER, ""));
        @SuppressWarnings("unchecked")
        List<String> examples = (List<String>) context.getOrDefault(CONTEXT_EXAMPLES, List.of());

        List<Message> messages = prompt.getInstructions();
        int userIndex = lastUserIndex(messages);
        String utterance = userIndex < 0 ? "" : messages.get(userIndex).getText();
        String inputText = String.join(
                "\n",
                messages.stream()
                        .map(m -> m.getText() == null ? "" : m.getText())
                        .toList());

        Matched matched = Scripts.match(scripts, agent, userId, utterance == null ? "" : utterance);
        if (matched == null) {
            return response(Scripts.fallbackReply(agentName, examples), List.of(), null, "fallback", inputText);
        }
        int batches = 0;
        for (Message m : messages.subList(userIndex + 1, messages.size())) {
            if (m instanceof AssistantMessage a && a.hasToolCalls()) batches++;
        }
        List<Step> steps = matched.script().steps();
        Step step = batches < steps.size() ? steps.get(batches) : steps.getLast();
        Map<String, Object> view = templateView(messages, matched.vars(), userId, today.get());
        String scriptId = matched.script().id();

        if (step.calls() != null && !step.calls().isEmpty()) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (Scripts.Call call : step.calls()) {
                i++;
                calls.add(new AssistantMessage.ToolCall(
                        "call_" + (batches + 1) + "_" + i,
                        "function",
                        call.tool(),
                        JSON.writeValueAsString(Scripts.resolveRecord(call.args(), view))));
            }
            String thought = step.thought() == null ? "" : Scripts.render(step.thought(), view);
            return response(thought, calls, null, scriptId, inputText);
        }
        // 先求结构化结果，回复模板可以引用它（例如用 CEL 过滤出的未通过检查项）
        Map<String, Object> output = step.output() == null ? null : Scripts.resolveRecord(step.output(), view);
        if (output != null) view.put("output", output);
        String text = Scripts.render(step.reply(), view).strip();
        return response(text, List.of(), output, scriptId, inputText);
    }

    private static ChatResponse response(
            String text,
            List<AssistantMessage.ToolCall> calls,
            Map<String, Object> output,
            String scriptId,
            String input) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROPERTY_SCRIPT, scriptId);
        if (output != null) properties.put(PROPERTY_OUTPUT, output);
        AssistantMessage.Builder<?> builder =
                AssistantMessage.builder().content(text).properties(properties);
        if (!calls.isEmpty()) builder.toolCalls(calls);
        String produced = calls.isEmpty() ? text : JSON.writeValueAsString(calls);
        // 脚本化模型按字数估算 token，不是真实计费
        var usage = new DefaultUsage(GatewayConfig.estimateTokens(input), GatewayConfig.estimateTokens(produced));
        var metadata =
                ChatResponseMetadata.builder().model("scripted").usage(usage).build();
        return new ChatResponse(List.of(new Generation(builder.build())), metadata);
    }
}

package dev.eab.platform.feedback;

import dev.eab.assembler.Conditions;
import dev.eab.eval.JsonObjects;
import dev.eab.runtime.model.ScriptedChatModel;
import dev.eab.spec.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 从用户的纠正生成候选规则或候选已验证任务。经模型网关调用（身份 platform/feedback，脚本化模型下按剧本输出），
 * 模型给出的检查条件要能通过 CEL 检查，否则去掉检查条件，只保留规则文字；模型不可用或输出不合格时按反馈原文生成。
 * 候选只进入审核队列，审核通过才生效。
 */
public final class FeedbackCandidates {

    private static final Logger LOG = LoggerFactory.getLogger(FeedbackCandidates.class);

    public static final String FEEDBACK_AGENT = "platform/feedback";

    /** 生成候选时交给模型的上下文：用户问了什么、Agent 怎么答的、用了哪些工具，以及工具结果的片段 */
    public record Context(String agent, String utterance, String answer, String reason, List<Map<String, String>> tools) {}

    /** @param decidedBy model 或 rule */
    public record Generated(Map<String, Object> candidate, String decidedBy) {}

    private final ChatModel model;
    private final String modelName;

    public FeedbackCandidates(ChatModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    public Generated generate(Context context, String userId) {
        Map<String, Object> answer = ask(context, userId);
        Map<String, Object> accepted = answer == null ? null : accept(answer, context);
        if (accepted != null) return new Generated(accepted, "model");
        return new Generated(fallback(context), "rule");
    }

    /** 模型不可用时的候选：规则文字取反馈原文，不带检查条件 */
    static Map<String, Object> fallback(Context context) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", "rule");
        c.put("description", context.reason().length() > 30 ? context.reason().substring(0, 30) + "…" : context.reason());
        c.put("severity", "warn");
        c.put("remedy", context.reason());
        c.put("reason", "按用户反馈原文生成，没有可执行的检查条件；审核时可以补充");
        return c;
    }

    /** 核对模型的输出：规则要有说明和处置建议，检查条件要能通过 CEL 检查 */
    static Map<String, Object> accept(Map<String, Object> answer, Context context) {
        String kind = String.valueOf(answer.getOrDefault("kind", "rule"));
        Map<String, Object> c = new LinkedHashMap<>();
        if (kind.equals("verified_task")) {
            if (!(answer.get("question") instanceof String q) || q.isBlank()) return null;
            if (!(answer.get("expectedConclusion") instanceof String e) || e.isBlank()) return null;
            c.put("kind", "verified_task");
            c.put("question", q.strip());
            c.put("expectedConclusion", e.strip());
            if (answer.get("failIf") instanceof String f && !f.isBlank()) c.put("failIf", f.strip());
        } else {
            if (!(answer.get("description") instanceof String d) || d.isBlank()) return null;
            if (!(answer.get("remedy") instanceof String r) || r.isBlank()) return null;
            c.put("kind", "rule");
            c.put("description", d.strip());
            String severity = String.valueOf(answer.getOrDefault("severity", "warn"));
            c.put("severity", List.of("block", "warn", "info").contains(severity) ? severity : "warn");
            c.put("remedy", r.strip());
            if (answer.get("checkTool") instanceof String tool
                    && answer.get("checkWhen") instanceof String when
                    && context.tools().stream().anyMatch(t -> tool.equals(t.get("name")))
                    && Conditions.check(Conditions.ruleCheckEnvironment(), when).valid()) {
                c.put("checkTool", tool);
                c.put("checkWhen", when);
            }
        }
        if (answer.get("reason") instanceof String reason) c.put("reason", reason);
        return c;
    }

    Map<String, Object> ask(Context context, String userId) {
        String system = "你是企业语义资产的维护助手。用户纠正了 Agent 的一个结论，请把纠正整理成一条候选规则（kind=rule）"
                + "或一条候选已验证任务（kind=verified_task）。规则要写 description（一句话说明）、severity（block、warn、info）、"
                + "remedy（处置建议，会交给所有相关 Agent）；如果可以用某个工具的返回数据判断规则是否适用，写 checkTool（工具名，"
                + "只能取上下文里出现的工具）和 checkWhen（CEL 表达式，变量 output 是该工具的返回数据）。已验证任务写 question、"
                + "expectedConclusion、failIf。经验沉淀为语义资产，不要写成针对某个 Agent 的提示词。只输出一个 JSON 对象，再加 reason 说明理由。";
        String user = "反馈：" + context.reason() + "\n上下文：" + Json.MAPPER.writeValueAsString(context);
        try {
            ChatResponse response = ChatClient.create(model)
                    .prompt(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .options(ToolCallingChatOptions.builder()
                            .model(modelName)
                            .temperature(0.0)
                            .toolContext(Map.of(
                                    ScriptedChatModel.CONTEXT_AGENT, FEEDBACK_AGENT,
                                    ScriptedChatModel.CONTEXT_AGENT_NAME, "经验回流助手",
                                    ScriptedChatModel.CONTEXT_USER, userId,
                                    ScriptedChatModel.CONTEXT_EXAMPLES, new ArrayList<String>())))
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null) return null;
            var output = response.getResult().getOutput();
            if (output.getMetadata().get(ScriptedChatModel.PROPERTY_OUTPUT) instanceof Map<?, ?> scripted) {
                Map<String, Object> copy = new LinkedHashMap<>();
                scripted.forEach((k, v) -> copy.put(String.valueOf(k), v));
                return copy;
            }
            return JsonObjects.parse(output.getText());
        } catch (RuntimeException e) {
            LOG.warn("生成候选规则时调用模型失败，按反馈原文生成：{}", e.getMessage());
            return null;
        }
    }
}

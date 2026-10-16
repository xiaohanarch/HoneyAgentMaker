package dev.eab.runtime.routing;

import dev.eab.runtime.model.PromptTemplate;
import dev.eab.runtime.routing.Router.Candidate;
import dev.eab.runtime.routing.Router.Decision;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 由模型在候选里选择（主提示词 9.2 节第 3 步），输出选择、理由和置信度。只在配置了真实模型时使用；
 * 模型的回答不合法或选了候选之外的项时，退回按召回分数决策，并在理由里说明。
 */
public final class ModelDecider implements Router.Decider {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModelDecider.class);

    private final ChatModel model;
    private final String modelName;
    private final Router.Decider fallback = new Router.ScoreDecider();

    public ModelDecider(ChatModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    static String system(List<Candidate> candidates) {
        StringBuilder text = new StringBuilder("""
                你是企业 ERP 统一入口的路由器。根据用户的问题，从下面的候选里选出最合适的一个。
                只能从候选里选；都不合适时 choice 写 null。置信度是 0 到 1 之间的小数，没有把握时给低分。
                只输出一个 JSON 对象：{"choice": "候选的 ref", "confidence": 0.0, "reason": "一句话理由"}

                候选：
                """);
        for (Candidate c : candidates) {
            text.append("- ref: ")
                    .append(c.entry().ref())
                    .append("；名称：")
                    .append(c.entry().displayName())
                    .append("；说明：")
                    .append(c.entry().description())
                    .append("；示例：")
                    .append(String.join(" / ", c.entry().examples()))
                    .append('\n');
        }
        return text.toString();
    }

    @Override
    public Decision decide(String query, List<Candidate> candidates) {
        List<Message> messages =
                new ArrayList<>(List.of(new SystemMessage(system(candidates)), new UserMessage(query)));
        try {
            // 经 ChatClient 调用：通用选项会合并进各厂商自己的默认选项，直接传给 ChatModel 时厂商模块会拒绝
            String text = ChatClient.create(model)
                    .prompt(new Prompt(messages))
                    .options(ToolCallingChatOptions.builder().model(modelName).temperature(0.0))
                    .call()
                    .content();
            if (text == null) text = "";
            Map<String, Object> parsed = PromptTemplate.extractOutput("```json\n" + strip(text) + "\n```")
                    .output();
            if (parsed == null) throw new IllegalStateException("模型的回答不是 JSON");
            Object choice = parsed.get("choice");
            Candidate chosen = candidates.stream()
                    .filter(c -> c.entry().ref().equals(choice))
                    .findFirst()
                    .orElse(null);
            double confidence = parsed.get("confidence") instanceof Number n ? n.doubleValue() : 0;
            String reason = String.valueOf(parsed.getOrDefault("reason", ""));
            return new Decision(
                    chosen == null ? null : chosen.entry(), chosen == null ? 0 : confidence, reason, "model");
        } catch (RuntimeException e) {
            // 厂商返回的错误信息可能带内部细节，只写日志；路由说明里用固定文案
            LOG.warn("路由的模型决策失败，按召回分数选择", e);
            Decision byScore = fallback.decide(query, candidates);
            return new Decision(byScore.chosen(), byScore.confidence(), "模型决策失败，按召回分数选择：" + byScore.reason(), "score");
        }
    }

    /** 去掉模型可能包上的代码块标记 */
    static String strip(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start > 0 && end > start)
                return trimmed.substring(start + 1, end).strip();
        }
        return trimmed;
    }
}

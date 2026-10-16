package dev.eab.platform.studio;

import dev.eab.runtime.model.PromptTemplate;
import dev.eab.runtime.model.ScriptedChatModel;
import dev.eab.spec.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * 向导的“AI 推荐”（主提示词 10.2 节）。每一步先检索候选（StudioDefaults），再经模型网关请模型在候选里挑选并说明理由；
 * 模型的输出只接受候选里存在的引用，输出不合格或模型不可用时退回检索推荐。脚本化模型下按 demo/scripts/studio.yaml 的剧本输出，
 * 剧本没有覆盖的意图同样退回检索推荐。
 */
public final class StudioRecommender {

    private static final Logger LOG = LoggerFactory.getLogger(StudioRecommender.class);

    /** 推荐调用模型时的身份，脚本化模型按它匹配剧本 */
    public static final String STUDIO_AGENT = "platform/studio";

    /** 有推荐的步骤；测试与发布两步没有推荐 */
    public static final Map<String, String> STEPS = stepLabels();

    private static Map<String, String> stepLabels() {
        Map<String, String> steps = new LinkedHashMap<>();
        steps.put("purpose", "描述目的");
        steps.put("naming", "命名");
        steps.put("process", "业务流程归属");
        steps.put("collaborators", "协作 Agent");
        steps.put("knowledge", "数据与知识");
        steps.put("tools", "技能与工具");
        steps.put("triggers", "触发器");
        steps.put("channels", "渠道");
        steps.put("instructions", "指令与约束");
        steps.put("model", "模型档位");
        return steps;
    }

    /**
     * @param value 推荐的取值，前端一键采纳时写进草稿
     * @param candidates 本步的候选，前端可以在候选里改选
     * @param decidedBy model（模型在候选里选择）或 retrieval（检索推荐）
     */
    public record Recommendation(
            String step, Map<String, Object> value, Map<String, Object> candidates, String reason, String decidedBy) {}

    private final ChatModel model;
    private final String modelName;

    public StudioRecommender(ChatModel model, String modelName) {
        this.model = model;
        this.modelName = modelName;
    }

    public Recommendation recommend(
            String step, String intent, Map<String, Object> draft, StudioCatalog catalog, String userId) {
        if (!STEPS.containsKey(step)) throw new IllegalArgumentException("没有推荐的步骤：" + step);
        Map<String, Object> baseline = baseline(step, intent, draft, catalog);
        Map<String, Object> candidates = candidates(step, intent, draft, catalog);
        Map<String, Object> answer = ask(step, intent, draft, candidates, userId);
        if (answer != null) {
            Map<String, Object> value = StudioAnswers.accept(step, answer, candidates, baseline);
            if (value != null) {
                String reason = answer.get("reason") instanceof String r && !r.isBlank() ? r : "模型在候选里选择";
                return new Recommendation(step, value, candidates, reason, "model");
            }
        }
        return new Recommendation(step, baseline, candidates, baselineReason(step), "retrieval");
    }

    static Map<String, Object> baseline(String step, String intent, Map<String, Object> draft, StudioCatalog catalog) {
        return switch (step) {
            case "purpose" -> StudioDefaults.purpose(intent, catalog);
            case "naming" -> StudioDefaults.naming(intent);
            case "process" -> StudioDefaults.process(intent, catalog);
            case "collaborators" -> StudioDefaults.collaborators(intent, catalog);
            case "knowledge" -> StudioDefaults.knowledge(intent, catalog);
            case "tools" -> StudioDefaults.tools(intent, catalog);
            case "triggers" -> StudioDefaults.triggers();
            case "channels" -> StudioDefaults.channels(intent);
            case "instructions" -> StudioDefaults.instructions(intent, draft, catalog);
            case "model" -> StudioDefaults.model(intent);
            default -> throw new IllegalArgumentException(step);
        };
    }

    static String baselineReason(String step) {
        return switch (step) {
            case "purpose" -> "按意图在能力目录里检索到的相近能力，先复用、再扩展、最后新建";
            case "naming" -> "按意图生成的名称";
            case "triggers" -> "新建的 Agent 默认由统一入口直接路由";
            case "model" -> "按意图里是否有分析、策略类任务选择档位";
            default -> "按意图在候选里检索得分最高的几项";
        };
    }

    /** 本步的候选，同时交给模型和前端 */
    static Map<String, Object> candidates(
            String step, String intent, Map<String, Object> draft, StudioCatalog catalog) {
        Map<String, Object> c = new LinkedHashMap<>();
        switch (step) {
            case "purpose" -> c.put("agents", catalog.agents());
            case "process" -> c.put("processes", catalog.processes());
            case "collaborators" -> c.put("agents", catalog.agents());
            case "knowledge" -> {
                c.put(
                        "semanticPacks",
                        catalog.semanticPacks().stream()
                                .map(p -> Map.of(
                                        "ref",
                                        p.ref(),
                                        "name",
                                        p.name(),
                                        "description",
                                        String.valueOf(p.description())))
                                .toList());
                c.put("knowledgeSources", catalog.knowledge());
            }
            case "tools" -> {
                c.put("skills", catalog.skills());
                // 工具全部作为候选：按意图排序截断会把需要的工具挡在候选之外
                c.put("tools", catalog.tools());
            }
            case "triggers" ->
                c.put(
                        "triggers",
                        List.of(
                                Map.of("type", "assistant", "label", "统一入口对话"),
                                Map.of("type", "event", "label", "业务事件（需要登记委托才能自主运行）"),
                                Map.of("type", "schedule", "label", "定时")));
            case "channels" -> c.put("channels", catalog.channels());
            case "instructions" -> {
                Set<String> selected = new LinkedHashSet<>(StudioDefaults.strings(draft.get("semanticPacks")));
                c.put(
                        "rules",
                        catalog.semanticPacks().stream()
                                .filter(p -> selected.contains(p.ref()))
                                .flatMap(p -> p.rules().stream())
                                .toList());
            }
            case "model" -> c.put("profiles", catalog.profiles());
            default -> {}
        }
        return c;
    }

    static String schema(String step) {
        return switch (step) {
            case "purpose" -> "{\"intent\": \"把意图改写成一句完整的目的\", \"reason\": \"...\"}";
            case "naming" ->
                "{\"name\": \"小写字母、数字和连字符的标识\", \"displayName\": \"中文显示名\", \"description\": \"一句话描述\", \"reason\": \"...\"}";
            case "process" -> "{\"businessProcess\": [\"流程 code\"], \"reason\": \"...\"}";
            case "collaborators" -> "{\"agents\": [\"候选 Agent 的 ref\"], \"reason\": \"...\"}";
            case "knowledge" -> "{\"semanticPacks\": [\"ref\"], \"knowledgeSources\": [\"ref\"], \"reason\": \"...\"}";
            case "tools" ->
                "{\"skills\": [\"技能 ref\"], \"tools\": [{\"source\": \"工具来源 ref\", \"name\": \"工具名\"}], \"reason\": \"...\"}";
            case "triggers" -> "{\"triggers\": [{\"type\": \"assistant\"}], \"direct\": true, \"reason\": \"...\"}";
            case "channels" -> "{\"channels\": [\"渠道 value\"], \"reason\": \"...\"}";
            case "instructions" -> "{\"instructions\": \"指令草稿\", \"constraints\": [\"规则编号\"], \"reason\": \"...\"}";
            case "model" -> "{\"profile\": \"fast|reasoning\", \"temperature\": 0.2, \"reason\": \"...\"}";
            default -> "{}";
        };
    }

    /** 调用模型；任何失败都返回 null，由调用方退回检索推荐 */
    Map<String, Object> ask(
            String step, String intent, Map<String, Object> draft, Map<String, Object> candidates, String userId) {
        String system = "你是 ERP Agent Builder Studio 的建模助手，正在帮开发者完成新建 Agent 向导的“" + STEPS.get(step) + "”一步。\n"
                + "只能从候选里选择，不要编造候选之外的引用。优先复用已有能力。只输出一个 JSON 对象，格式：" + schema(step) + "\n候选："
                + Json.MAPPER.writeValueAsString(candidates);
        String user = "步骤：" + STEPS.get(step) + "\n意图：" + intent + "\n已选：" + Json.MAPPER.writeValueAsString(draft);
        try {
            ChatResponse response = ChatClient.create(model)
                    .prompt(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .options(ToolCallingChatOptions.builder()
                            .model(modelName)
                            .temperature(0.2)
                            .toolContext(Map.of(
                                    ScriptedChatModel.CONTEXT_AGENT,
                                    STUDIO_AGENT,
                                    ScriptedChatModel.CONTEXT_AGENT_NAME,
                                    "Studio 建模助手",
                                    ScriptedChatModel.CONTEXT_USER,
                                    userId,
                                    ScriptedChatModel.CONTEXT_EXAMPLES,
                                    new ArrayList<String>())))
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null) return null;
            var output = response.getResult().getOutput();
            if (output.getMetadata().get(ScriptedChatModel.PROPERTY_OUTPUT) instanceof Map<?, ?> scripted) {
                return copy(scripted);
            }
            return parse(output.getText());
        } catch (RuntimeException e) {
            LOG.warn("向导推荐调用模型失败，退回检索推荐：{}", e.getMessage());
            return null;
        }
    }

    static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 模型可能直接输出 JSON，也可能包在代码块里 */
    static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) return null;
        var extracted = PromptTemplate.extractOutput(text);
        if (extracted.output() != null) return extracted.output();
        String trimmed = text.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            Object parsed = Json.MAPPER.readValue(trimmed.substring(start, end + 1), Object.class);
            return parsed instanceof Map<?, ?> map ? copy(map) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

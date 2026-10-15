package dev.eab.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eab.spec.model.Common.Routing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 技能与工作流（主提示词 5.1、8.7 节） */
public final class StepSpecs {

    private StepSpecs() {}

    /** 工作流引擎支持的步骤类型 */
    public static final List<String> STEP_TYPES =
            List.of("tool", "skill", "agent", "transform", "condition", "parallel", "confirm", "notify", "waitEvent");

    /** 技能只允许确定性步骤，不调用 Agent，也不并行或等待事件 */
    public static final List<String> SKILL_STEP_TYPES =
            List.of("tool", "skill", "transform", "condition", "confirm", "notify");

    /** 每种步骤类型必须提供的字段 */
    public static final Map<String, List<String>> REQUIRED_FIELDS = Map.of(
            "tool", List.of("tool"),
            "skill", List.of("skill"),
            "agent", List.of("agent"),
            "transform", List.of("expression"),
            "condition", List.of("if", "then"),
            "parallel", List.of("branches"),
            "confirm", List.of("message"),
            "notify", List.of("message"),
            "waitEvent", List.of("event"));

    /**
     * 工作流步骤。with 的值可以是常量或 CEL 表达式字符串；if、then、else 是 Java 关键字，组件名加了后缀。
     */
    public record Step(
            String id,
            String type,
            String name,
            String tool,
            String source,
            String skill,
            String agent,
            @JsonProperty("with") Map<String, JsonNode> with,
            String expression,
            @JsonProperty("if") String ifExpression,
            @JsonProperty("then") List<Step> thenSteps,
            @JsonProperty("else") List<Step> elseSteps,
            List<List<Step>> branches,
            String message,
            String channel,
            String event,
            Integer timeoutSeconds,
            String output) {

        /** 本步骤及其所有嵌套分支里的步骤 */
        public List<Step> flatten() {
            List<Step> all = new ArrayList<>();
            all.add(this);
            for (Step s : orEmpty(thenSteps)) all.addAll(s.flatten());
            for (Step s : orEmpty(elseSteps)) all.addAll(s.flatten());
            for (List<Step> branch : branches == null ? List.<List<Step>>of() : branches) {
                for (Step s : branch) all.addAll(s.flatten());
            }
            return all;
        }
    }

    public static List<Step> flatten(List<Step> steps) {
        return steps.stream().flatMap(s -> s.flatten().stream()).toList();
    }

    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 技能：确定性步骤序列，作为工具挂给 Agent */
    public record SkillSpec(
            JsonNode inputSchema,
            JsonNode outputSchema,
            List<Step> steps,
            boolean agentOnly,
            String writeLevel,
            Routing routing) {}

    public record WorkflowSpec(JsonNode inputSchema, List<Step> steps) {}
}

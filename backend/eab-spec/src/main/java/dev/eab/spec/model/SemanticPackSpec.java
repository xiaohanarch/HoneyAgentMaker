package dev.eab.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** 语义包（主提示词 5.5 节），原生段的键名沿用白皮书的下划线风格 */
public record SemanticPackSpec(
        /* 租户层语义包可以扩展标品语义包，经验回流写到这里 */
        @JsonProperty("extends") String extendsRef,
        List<SemanticObject> objects,
        List<Relationship> relationships,
        List<Metric> metrics,
        List<SemanticRule> rules,
        @JsonProperty("agent_instructions") List<String> agentInstructions,
        @JsonProperty("verified_tasks") List<VerifiedTask> verifiedTasks,
        Permissions permissions) {

    public record Field(String ref, String description, Map<String, String> enumMeaning) {}

    /** 投影段只写元数据引用（例如 erp.pur.Supplier@v1），运行时从元数据服务取定义 */
    public record SemanticObject(String ref, String description, List<Field> fields) {}

    public record Relationship(String from, String to, String verb, String cardinality, String notes) {}

    public record Metric(String name, String displayName, String definition, String unit) {}

    /**
     * 规则的可执行检查：某个工具返回后，用 CEL 表达式判断规则是否触发（变量 output 是工具的返回数据）。
     * 声明了这条约束的 Agent 运行时由守卫检查，触发时写一个守卫 span，并把处置建议交给模型（ADR 0010）。
     */
    public record RuleCheck(String tool, String when) {}

    public record SemanticRule(
            String id, String ref, String severity, String description, String remedy, RuleCheck check) {}

    public record VerifiedTask(
            String id,
            String question,
            @JsonProperty("expected_conclusion") String expectedConclusion,
            @JsonProperty("fail_if") String failIf,
            List<String> tools) {}

    public record Permissions(
            @JsonProperty("filter_dimensions") List<String> filterDimensions,
            @JsonProperty("inherit_from") String inheritFrom,
            @JsonProperty("disclose_filtered") boolean discloseFiltered) {}
}

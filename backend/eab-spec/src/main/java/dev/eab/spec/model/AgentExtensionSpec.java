package dev.eab.spec.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.eab.spec.model.AgentSpec.ApprovalRule;
import dev.eab.spec.model.AgentSpec.Hooks;
import dev.eab.spec.model.AgentSpec.ToolEntry;
import dev.eab.spec.model.Common.EvalsRef;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 租户对标品 Agent 的扩展（主提示词 5.3 节） */
public record AgentExtensionSpec(
        String target,
        Append instructions,
        ListAppend constraints,
        ToolsAdd tools,
        RefsAdd skills,
        /* 语义包或知识源 */
        RefsAdd knowledge,
        Hooks hooks,
        ApprovalsAdd approvals,
        ModelSelect model,
        EvalsRef evals) {

    public record Append(String append) {}

    public record ListAppend(List<String> append) {}

    public record ToolsAdd(List<ToolEntryOrRef> add) {}

    public record RefsAdd(List<String> add) {}

    public record ApprovalsAdd(List<ApprovalRule> add) {}

    public record ModelSelect(String profile) {}

    /** 扩展里的 tools.add 可以直接写资产引用，组装时按被引用资产的 kind 归一化 */
    public sealed interface ToolEntryOrRef {

        record Ref(String ref) implements ToolEntryOrRef {
            @JsonValue
            public String value() {
                return ref;
            }
        }

        record Entry(ToolEntry entry) implements ToolEntryOrRef {
            @JsonValue
            public ToolEntry value() {
                return entry;
            }
        }

        ObjectMapper MAPPER = JsonMapper.builder().build();

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        static ToolEntryOrRef of(JsonNode node) {
            if (node.isString()) return new Ref(node.asString());
            return new Entry(MAPPER.treeToValue(node, ToolEntry.class));
        }
    }

    /** 扩展实际用到的扩展点类型，组装器与静态检查共用 */
    public List<String> usedExtensionPoints() {
        List<String> used = new java.util.ArrayList<>();
        if (instructions != null || constraints != null) used.add("instructions.append");
        if (tools != null) used.add("tools.add");
        if (skills != null) used.add("skills.add");
        if (knowledge != null) used.add("knowledge.add");
        if (hooks != null && !hooks.usedEvents().isEmpty()) used.add("hooks");
        if (approvals != null) used.add("approvals.add");
        if (model != null) used.add("model.select");
        return used;
    }

    public List<String> usedHookEvents() {
        return hooks == null ? List.of() : hooks.usedEvents();
    }

    public record Violation(String message, String path) {}

    /** 扩展是否只使用了目标 Agent 声明过的扩展点，包括钩子事件和模型档位的范围（主提示词 5.3 节） */
    public List<Violation> extensionPointViolations(List<AgentSpec.ExtensionPoint> declared) {
        List<Violation> violations = new java.util.ArrayList<>();
        for (String type : usedExtensionPoints()) {
            if (declared.stream().noneMatch(p -> p.type().equals(type))) {
                violations.add(new Violation("目标 Agent 没有声明扩展点 " + type, "spec"));
            }
        }
        declared.stream()
                .filter(p -> p.type().equals("hooks") && p.events() != null)
                .findFirst()
                .ifPresent(point -> {
                    for (String event : usedHookEvents()) {
                        if (!point.events().contains(event)) {
                            violations.add(new Violation(
                                    "目标 Agent 的 hooks 扩展点不允许挂在 " + event + " 事件上", "spec.hooks." + event));
                        }
                    }
                });
        if (model != null) {
            declared.stream()
                    .filter(p -> p.type().equals("model.select") && p.allowedProfiles() != null)
                    .findFirst()
                    .filter(point -> !point.allowedProfiles().contains(model.profile()))
                    .ifPresent(point -> violations.add(new Violation(
                            "目标 Agent 只允许选择模型档位 " + String.join("、", point.allowedProfiles()) + "，不能选 "
                                    + model.profile(),
                            "spec.model.profile")));
        }
        return violations;
    }
}

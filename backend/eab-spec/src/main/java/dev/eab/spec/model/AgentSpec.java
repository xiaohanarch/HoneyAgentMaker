package dev.eab.spec.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.eab.spec.model.Common.EvalsRef;
import dev.eab.spec.model.Common.Routing;
import dev.eab.spec.model.Common.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Agent 的 spec，字段名与主提示词 5.1 节对齐 */
public record AgentSpec(
        String expertise,
        String instructions,
        /* 公开版指令，“复制为租户 Agent”时带上（ADR 0004） */
        String publicInstructions,
        String additionalContext,
        /* 语义包规则编号（如 PUR-R-007）或自由文本约束 */
        List<String> constraints,
        ModelConfig model,
        ExecutionSteps executionSteps,
        List<ToolEntry> tools,
        List<String> semanticPacks,
        List<Trigger> triggers,
        List<String> channels,
        MemoryConfig memory,
        WritePolicy writePolicy,
        List<ApprovalRule> approvals,
        Hooks hooks,
        Output output,
        Routing routing,
        List<ExtensionPoint> extensionPoints,
        EvalsRef evals) {

    public static final List<String> HOOK_EVENTS = List.of("beforeToolCall", "afterToolCall", "onResult", "onFailure");

    /** 人工确认策略由宽到严排列，组装时取最严者（主提示词 5.4 节第 3 条） */
    public static final List<String> APPROVAL_POLICIES = List.of("auto", "require-confirmation", "require-approval");

    public static int approvalStrictness(String policy) {
        return APPROVAL_POLICIES.indexOf(policy);
    }

    public record ModelConfig(String profile, Double temperature) {}

    public record MemoryConfig(boolean longTerm) {}

    public record WritePolicy(String maxLevel) {}

    public record ToolSubset(String source, List<String> tools) {}

    /** Agent 的一个工具条目，只能写一种来源；api 对应 bo-api 与 http 类型的工具来源 */
    public record ToolEntry(
            String skill,
            String agent,
            String remoteAgent,
            ToolSubset mcp,
            ToolSubset api,
            String documents,
            String builtin) {

        public NormalizedToolEntry normalize() {
            if (skill != null) return new NormalizedToolEntry("skill", skill, null, null);
            if (agent != null) return new NormalizedToolEntry("agent", agent, null, null);
            if (remoteAgent != null) return new NormalizedToolEntry("remoteAgent", remoteAgent, null, null);
            if (documents != null) return new NormalizedToolEntry("documents", documents, null, null);
            if (mcp != null) return new NormalizedToolEntry("mcp", mcp.source(), mcp.tools(), null);
            if (api != null) return new NormalizedToolEntry("api", api.source(), api.tools(), null);
            if (builtin != null) return new NormalizedToolEntry("builtin", null, null, builtin);
            throw new IllegalStateException("工具条目没有任何来源");
        }
    }

    /**
     * 归一化后的工具条目。
     *
     * @param type skill、agent、remoteAgent、documents、mcp、api、builtin 之一
     * @param ref 资产引用，builtin 时为 null
     * @param tools mcp、api 只挂载的部分工具，null 表示全部
     * @param builtinName 内置工具名
     */
    public record NormalizedToolEntry(String type, String ref, List<String> tools, String builtinName) {}

    public record ExecutionStep(String skill, String guard) {}

    public record ExecutionSteps(List<ExecutionStep> pre, List<ExecutionStep> post) {}

    public record HookBinding(String workflow, String skill, String when) {}

    /** 每个事件可以写单个钩子或数组，schema 解析后统一成数组 */
    public record Hooks(
            List<HookBinding> beforeToolCall,
            List<HookBinding> afterToolCall,
            List<HookBinding> onResult,
            List<HookBinding> onFailure) {

        public List<HookBinding> of(String event) {
            List<HookBinding> list =
                    switch (event) {
                        case "beforeToolCall" -> beforeToolCall;
                        case "afterToolCall" -> afterToolCall;
                        case "onResult" -> onResult;
                        case "onFailure" -> onFailure;
                        default -> throw new IllegalArgumentException("未知的钩子事件 " + event);
                    };
            return list == null ? List.of() : list;
        }

        /** 实际挂了钩子的事件，按 HOOK_EVENTS 的顺序 */
        public List<String> usedEvents() {
            List<String> used = new ArrayList<>();
            for (String event : HOOK_EVENTS) if (!of(event).isEmpty()) used.add(event);
            return used;
        }
    }

    public record ApprovalRule(String tool, String policy, String when, String approverRole, String description) {}

    public record ExtensionPoint(String type, String description, List<String> allowedProfiles, List<String> events) {}

    public record OutputField(String type, String description, @JsonProperty("enum") List<String> enumValues) {}

    /** 结构化结果的字段声明，钩子条件里的 result.xxx 按这里做检查（ADR 0003） */
    public record Output(Map<String, OutputField> schema) {}
}

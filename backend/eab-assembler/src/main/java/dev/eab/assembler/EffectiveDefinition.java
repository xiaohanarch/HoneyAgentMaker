package dev.eab.assembler;

import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.Common.Routing;
import java.util.List;

/**
 * 有效定义：本次运行实际使用的 Agent 定义（主提示词 5.4 节第 7 条）。
 * 完整内容只交给运行时；对外一律经 {@link DefinitionView} 按查看者裁剪后返回。
 */
public record EffectiveDefinition(
        String name,
        String displayName,
        String expertise,
        List<Segment> segments,
        List<EffectiveTool> tools,
        List<EffectiveApproval> approvals,
        List<EffectiveHook> hooks,
        EffectiveModel model,
        List<String> semanticPacks,
        AgentSpec.WritePolicy writePolicy,
        AgentSpec.ExecutionSteps executionSteps,
        AgentSpec.Output output,
        Routing routing,
        Provenance provenance) {

    /** 指令片段的来源类别，决定拼接顺序（主提示词 5.4 节第 1 条） */
    public enum SegmentKind {
        platform_rule("platform-rule"),
        vendor_instructions("vendor-instructions"),
        vendor_context("vendor-context"),
        vendor_constraint("vendor-constraint"),
        semantic_instruction("semantic-instruction"),
        tenant_instructions("tenant-instructions"),
        tenant_constraint("tenant-constraint");

        private final String value;

        SegmentKind(String value) {
            this.value = value;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String value() {
            return value;
        }
    }

    /**
     * 一段指令。
     *
     * @param id 稳定编号，便于在追踪里回放和比较
     * @param source 来源资产，namespace/name@version
     * @param field 来源字段，例如 spec.instructions
     * @param label 规则编号或平台规则名，用于界面标注
     * @param confidential 对租户保密：标品指令原文、平台规则原文
     */
    public record Segment(
            String id,
            SegmentKind kind,
            Layer layer,
            String source,
            String field,
            String text,
            String label,
            boolean confidential) {}

    /**
     * 暴露给模型的一个工具。
     *
     * @param type skill、agent、remoteAgent、mcp、api、documents、builtin、semantic 之一
     * @param source 工具所在的资产，namespace/name@version；内置工具为 platform/builtin
     */
    public record EffectiveTool(
            String name,
            String type,
            Layer layer,
            String source,
            String description,
            String writeLevel,
            String category) {}

    public record EffectiveApproval(
            String tool, String policy, String when, String approverRole, Layer layer, String source) {}

    /**
     * @param failurePolicy 标品钩子失败中止主流程（propagate）；租户钩子失败只记录并提示（isolate）
     */
    public record EffectiveHook(
            String event,
            Layer layer,
            String source,
            String workflow,
            String skill,
            String when,
            String failurePolicy) {}

    /** @param layer 档位由谁决定 */
    public record EffectiveModel(String profile, Double temperature, Layer layer) {}

    public record ResolvedTarget(List<String> ranges, String version) {}

    /**
     * @param identity 组装出的 Agent 身份（主提示词 7.1 节）
     * @param agent 实际生效的标品版本
     */
    public record Provenance(
            String identity,
            String tenantId,
            String agent,
            ResolvedTarget resolvedTarget,
            List<String> extensions,
            List<String> platformRules,
            List<String> semanticPacks) {}
}

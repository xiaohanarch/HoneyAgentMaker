package dev.eab.policy;

import dev.eab.assembler.Capabilities;
import dev.eab.assembler.Conditions;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.assembler.EffectiveDefinition.EffectiveApproval;
import dev.eab.assembler.EffectiveDefinition.EffectiveTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把调用上下文整理成规则可用的事实。字段齐全、没有缺失的键，规则里不必写 has() 判断；
 * 缺少的信息用空字符串或空列表表示。
 */
public final class PolicyInputs {

    private PolicyInputs() {}

    /**
     * @param operations 用户的业务权限（可调用的 BO 操作）
     */
    public record SubjectFacts(String userId, String tenantId, List<String> roles, List<String> operations) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("userId", userId);
            map.put("tenantId", tenantId);
            map.put("roles", List.copyOf(roles));
            map.put("operations", List.copyOf(operations));
            return map;
        }
    }

    public record Assistant(String ref, List<String> allowedRoles) {}

    public static Map<String, Object> entry(SubjectFacts subject, String tenantId, Assistant assistant) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("tenantId", tenantId);
        target.put("assistant", assistant == null ? "" : assistant.ref());
        target.put("allowedRoles", assistant == null ? List.of() : List.copyOf(assistant.allowedRoles()));
        return Map.of("subject", subject.toMap(), "target", target);
    }

    /** @param type router、assistant、agent、external 之一 */
    public record Caller(String type, String ref, int depth) {}

    /** @param kind Agent、RemoteAgent、Assistant 之一 */
    public record HandoffTarget(String ref, String kind, boolean enabledForTenant, List<String> allowedCallers) {}

    public static Map<String, Object> handoff(SubjectFacts subject, Caller caller, HandoffTarget target) {
        Map<String, Object> callerMap = new LinkedHashMap<>();
        callerMap.put("type", caller.type());
        callerMap.put("ref", caller.ref());
        callerMap.put("depth", (long) caller.depth());
        Map<String, Object> targetMap = new LinkedHashMap<>();
        targetMap.put("ref", target.ref());
        targetMap.put("kind", target.kind());
        targetMap.put("enabledForTenant", target.enabledForTenant());
        targetMap.put("allowedCallers", List.copyOf(target.allowedCallers()));
        return Map.of("subject", subject.toMap(), "caller", callerMap, "target", targetMap);
    }

    public record Delegation(String id, List<String> scope) {}

    /** @param action submit、approve 或空字符串 */
    public record Document(String id, String action, String submittedBy) {}

    /**
     * 工具调用的上下文。
     *
     * @param mode on-behalf 代用户操作、delegated 受托自主运行、integration 机机集成
     * @param toolInput 工具入参，用于求值确认点的 when 条件
     * @param delegation 受托运行时的委托，否则为 null
     * @param document 涉及单据的写操作：单据编号、本次动作、提交人，否则为 null
     */
    public record ToolContext(
            SubjectFacts subject,
            String mode,
            EffectiveDefinition definition,
            String toolName,
            Map<String, Object> toolInput,
            boolean l3Enabled,
            Delegation delegation,
            Document document) {}

    /** 只保留 when 条件成立的确认点。条件求值出错时按“命中”处理：宁可多一次确认，也不漏掉 */
    static List<EffectiveApproval> applicable(
            List<EffectiveApproval> approvals, Map<String, Object> toolInput, SubjectFacts subject) {
        Map<String, Object> user = Map.of("id", subject.userId(), "roles", List.copyOf(subject.roles()));
        return approvals.stream()
                .filter(a -> {
                    if (a.when() == null) return true;
                    try {
                        return Conditions.test(a.when(), Map.of("input", toolInput, "user", user));
                    } catch (RuntimeException e) {
                        return true;
                    }
                })
                .toList();
    }

    public static Map<String, Object> tool(ToolContext ctx) {
        EffectiveTool tool = ctx.definition().tools().stream()
                .filter(t -> t.name().equals(ctx.toolName()))
                .findFirst()
                .orElse(null);
        List<EffectiveApproval> approvals = tool == null
                ? List.of()
                : applicable(
                        Capabilities.matchingApprovals(ctx.definition().approvals(), tool),
                        ctx.toolInput() == null ? Map.of() : ctx.toolInput(),
                        ctx.subject());
        String policy = Capabilities.strictestPolicy(approvals);
        EffectiveApproval strictest = approvals.stream()
                .filter(a -> a.policy().equals(policy))
                .findFirst()
                .orElse(null);

        Map<String, Object> toolMap = new LinkedHashMap<>();
        toolMap.put("name", ctx.toolName());
        toolMap.put("declared", tool != null);
        toolMap.put("writeLevel", tool == null || tool.writeLevel() == null ? "" : tool.writeLevel());
        toolMap.put("category", tool == null || tool.category() == null ? "" : tool.category());
        toolMap.put("approvalPolicy", policy);
        toolMap.put(
                "approverRole", strictest == null || strictest.approverRole() == null ? "" : strictest.approverRole());
        toolMap.put(
                "inDelegationScope",
                ctx.delegation() != null && ctx.delegation().scope().contains(ctx.toolName()));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subject", ctx.subject().toMap());
        input.put("mode", ctx.mode());
        input.put("agent", Map.of("ref", ctx.definition().provenance().agent()));
        input.put("tool", toolMap);
        input.put("tenant", Map.of("l3Enabled", ctx.l3Enabled()));
        input.put(
                "delegation",
                Map.of("id", ctx.delegation() == null ? "" : ctx.delegation().id()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", ctx.document() == null ? "" : ctx.document().id());
        document.put("action", ctx.document() == null ? "" : ctx.document().action());
        document.put("submittedBy", ctx.document() == null ? "" : ctx.document().submittedBy());
        input.put("document", document);
        return input;
    }
}

package dev.eab.runtime;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.policy.PolicyInputs.SubjectFacts;
import java.util.List;

/**
 * 一个 Agent 在一次运行里的上下文：谁在用（用户与租户）、以什么模式（代用户操作或受托自主运行）、用哪个 Agent 的有效定义。
 * 子 Agent 调用时换成子 Agent 的定义，深度加一，其余不变。
 *
 * @param agent namespace/name
 * @param depth 子 Agent 调用深度，入口 Agent 为 0
 */
public record RunContext(
        String runId,
        String sessionId,
        User user,
        String mode,
        String delegationId,
        Subject subject,
        String agent,
        EffectiveDefinition definition,
        String agentToken,
        int depth) {

    /**
     * 演示目录里的用户信息，运行时只读这些字段。
     *
     * @param operations 用户在 ERP 里的业务权限
     */
    public record User(
            String id,
            String name,
            String tenantId,
            String tenantName,
            List<String> roles,
            List<String> operations,
            String dataScopeLabel) {

        public SubjectFacts facts() {
            return new SubjectFacts(id, tenantId, roles, operations);
        }
    }

    /**
     * 调用 BO API 的主体：用户令牌（代用户操作）、委托编号（受托自主运行）或受限回调令牌（/mcp 的外部 Agent，
     * ADR 0014），三者只能有一个
     */
    public record Subject(String userToken, String delegationId, String restrictedToken) {}

    public boolean delegated() {
        return "delegated".equals(mode);
    }

    public String tenantId() {
        return user.tenantId();
    }

    public RunContext forSubAgent(String subAgent, EffectiveDefinition subDefinition, String subToken) {
        return new RunContext(
                runId, sessionId, user, mode, delegationId, subject, subAgent, subDefinition, subToken, depth + 1);
    }
}

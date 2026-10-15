package dev.eab.platform.audit;

import java.util.List;
import java.util.Map;

/**
 * 审计写入与查询（主提示词 7.5 节）：注册中心的写入与完整性裁决、鉴权点 1 至 3 的裁决、
 * DemoERP 推送过来的鉴权点 4 裁决与业务写入，都记在一张表里，“谁通过哪个 Agent 做了什么”在一处可查。
 */
public interface AuditStore {

    /**
     * 一条审计记录。
     *
     * @param decision allow、deny、confirm、approve 之一
     * @param agent Agent 身份与版本，例如 vendor.fin/transaction-anomaly-analyst@1.2.0
     * @param extensions 生效的租户扩展及版本
     * @param mode on-behalf 代用户操作、delegated 受托自主运行、integration 机机集成
     * @param checkpoint entry、handoff、tool、bo-api
     */
    record AuditEvent(
            long id,
            String at,
            String actor,
            String action,
            String target,
            String decision,
            String reason,
            Map<String, Object> detail,
            String tenantId,
            String userId,
            String agent,
            List<String> extensions,
            String sessionId,
            String mode,
            String checkpoint,
            String tool,
            String dataScope,
            String ruleId,
            String resultSummary) {}

    /** 新增记录的字段，必填的五项之外按需填写 */
    record NewAuditEvent(
            String actor,
            String action,
            String target,
            String decision,
            String reason,
            Map<String, Object> detail,
            String tenantId,
            String userId,
            String agent,
            List<String> extensions,
            String sessionId,
            String mode,
            String checkpoint,
            String tool,
            String dataScope,
            String ruleId,
            String resultSummary) {

        public static Builder of(String actor, String action, String target, String decision, String reason) {
            return new Builder(actor, action, target, decision, reason);
        }
    }

    /** 记录的构造器，保持调用处可读 */
    final class Builder {
        private final String actor;
        private final String action;
        private final String target;
        private final String decision;
        private final String reason;
        private Map<String, Object> detail;
        private String tenantId;
        private String userId;
        private String agent;
        private List<String> extensions;
        private String sessionId;
        private String mode;
        private String checkpoint;
        private String tool;
        private String dataScope;
        private String ruleId;
        private String resultSummary;

        Builder(String actor, String action, String target, String decision, String reason) {
            this.actor = actor;
            this.action = action;
            this.target = target;
            this.decision = decision;
            this.reason = reason;
        }

        public Builder detail(Map<String, Object> value) {
            detail = value;
            return this;
        }

        public Builder tenantId(String value) {
            tenantId = value;
            return this;
        }

        public Builder userId(String value) {
            userId = value;
            return this;
        }

        public Builder agent(String value) {
            agent = value;
            return this;
        }

        public Builder extensions(List<String> value) {
            extensions = value;
            return this;
        }

        public Builder sessionId(String value) {
            sessionId = value;
            return this;
        }

        public Builder mode(String value) {
            mode = value;
            return this;
        }

        public Builder checkpoint(String value) {
            checkpoint = value;
            return this;
        }

        public Builder tool(String value) {
            tool = value;
            return this;
        }

        public Builder dataScope(String value) {
            dataScope = value;
            return this;
        }

        public Builder ruleId(String value) {
            ruleId = value;
            return this;
        }

        public Builder resultSummary(String value) {
            resultSummary = value;
            return this;
        }

        public NewAuditEvent build() {
            return new NewAuditEvent(
                    actor,
                    action,
                    target,
                    decision,
                    reason,
                    detail,
                    tenantId,
                    userId,
                    agent,
                    extensions,
                    sessionId,
                    mode,
                    checkpoint,
                    tool,
                    dataScope,
                    ruleId,
                    resultSummary);
        }
    }

    /**
     * 查询条件，字段为 null 表示不限。
     *
     * @param agent 按前缀匹配
     * @param target 目标包含的文本，例如单据编号 PO-2026-0117
     */
    record AuditFilter(
            String action,
            String decision,
            String tenantId,
            String userId,
            String agent,
            String tool,
            String checkpoint,
            String target,
            String from,
            String to,
            Integer limit) {

        public static AuditFilter all() {
            return new AuditFilter(null, null, null, null, null, null, null, null, null, null, null);
        }

        public AuditFilter withAction(String value) {
            return new AuditFilter(value, decision, tenantId, userId, agent, tool, checkpoint, target, from, to, limit);
        }

        public AuditFilter withDecision(String value) {
            return new AuditFilter(action, value, tenantId, userId, agent, tool, checkpoint, target, from, to, limit);
        }

        public AuditFilter withTenant(String value) {
            return new AuditFilter(action, decision, value, userId, agent, tool, checkpoint, target, from, to, limit);
        }
    }

    AuditEvent append(NewAuditEvent event);

    default AuditEvent append(Builder builder) {
        return append(builder.build());
    }

    /** 按时间倒序（最新在前），默认最多 100 条 */
    List<AuditEvent> list(AuditFilter filter);

    default List<AuditEvent> list() {
        return list(AuditFilter.all());
    }
}

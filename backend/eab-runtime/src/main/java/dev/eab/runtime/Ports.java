package dev.eab.runtime;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.policy.PolicyEngine.Decision;
import java.util.List;
import java.util.Map;

/**
 * 运行时依赖的外部能力。运行时只依赖这些接口，具体实现由平台装配：组装走注册中心，BO API 走令牌交换，
 * 鉴权裁决写审计，记忆与告警写平台数据库。测试用内存实现。
 */
public final class Ports {

    private Ports() {}

    /** 组装有效定义（运行时的第一步，追踪里是 assemble span） */
    public interface Assembly {
        /**
         * @param agent namespace/name
         * @throws RuntimeException 组装失败（Agent 不存在、未发布、有错误级问题）
         */
        EffectiveDefinition assemble(String agent, String tenantId);
    }

    /**
     * 本次运行的身份：Agent 令牌由平台运行时签发，scope 是 Agent 声明的 BO 操作（主提示词 7.1 节）。
     */
    public interface Identity {
        String issueAgentToken(EffectiveDefinition definition, String tenantId);
    }

    /**
     * BO API 调用：平台实现里先以“用户 + Agent”身份做令牌交换再调用，身份透传失败即拒绝，绝不退回技术账号。
     *
     * @param query GET 的查询参数
     * @param body POST 的请求体，没有时为 null
     */
    public record BoCall(
            RunContext.Subject subject,
            String agentToken,
            String operation,
            String method,
            String path,
            Map<String, String> query,
            Map<String, Object> body,
            String idempotencyKey) {}

    /** @param body BO API 的响应体（JSON 对象），错误时是 {error: {code, reason, suggestion}} */
    public record BoResponse(int status, Object body) {}

    public interface BoApi {
        /** @throws RuntimeException 身份透传失败：令牌交换失败、委托无效、连接未配置 */
        BoResponse call(BoCall call);
    }

    /**
     * 鉴权点 2、3 的裁决：平台实现里调用策略引擎，并把裁决写入审计。
     *
     * @param facts 判定所需的事实（策略引擎的 input）
     */
    public interface Policy {
        Decision evaluate(String checkpoint, Map<String, Object> facts, RunContext context, String target);

        /** 租户级设置：L3 写操作是否开启 */
        boolean l3Enabled(String tenantId);

        /** 委托允许的操作；委托不存在时为空列表 */
        List<String> delegationScope(String delegationId);
    }

    /** 外部 MCP Server 的工具调用，connection 是资产里引用的连接别名 */
    public interface Mcp {
        Object call(String connection, String tool, Map<String, Object> arguments);
    }

    /** http 类工具来源（例如 IM 通知机器人），connection 是连接别名，地址由部署时绑定 */
    public interface Http {
        Object call(String connection, String method, String path, Map<String, Object> body);
    }

    /** 通知步骤与 IM 渠道 */
    public interface Notifier {
        void notify(RunContext context, String channel, String message);
    }

    /** 文档检索：知识源上的 BM25 检索，返回片段和引用 */
    public interface Documents {
        List<Map<String, Object>> search(String knowledgeSource, String query, int limit);
    }

    /**
     * 记忆（主提示词 8.5 节）：会话记忆保存同一会话的消息；长期记忆按“租户、用户、Agent”三元组隔离。
     */
    public interface Memory {
        List<String> longTerm(String tenantId, String userId, String agent);

        /** 会话里此前的问答，按时间顺序，每条是 [用户话术, 最终回复] */
        List<List<String>> session(String sessionId);

        void appendSession(
                String sessionId, String tenantId, String userId, String agent, String utterance, String reply);
    }

    /** 监督层的告警，写入治理中心的风险告警 */
    public interface Alerts {
        void raise(RunContext context, String type, String severity, String message, Map<String, Object> detail);

        /** 同一会话在最近 windowSeconds 秒内已执行的写操作次数，用于行为异常检测 */
        int recentWrites(String sessionId, long windowSeconds);

        void recordWrite(String sessionId, String tool);
    }
}

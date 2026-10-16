package dev.eab.a2a.sdk;

/**
 * 用代码写的 Agent（代码 Agent SDK，主提示词 10.4 节；ADR 0006 把 TypeScript SDK 改为 Java SDK）。
 *
 * <p>实现这个接口，交给 {@link CodeAgentServer} 以 A2A 协议对外提供服务，再用 {@link AgentDeclaration} 生成 RemoteAgent 资产登记到平台。
 * 平台调用它时以最终用户为主体签发服务令牌，SDK 先校验令牌，再把调用方身份交给 {@link #reply}；身份校验失败的请求不会到达这里。
 */
@FunctionalInterface
public interface CodeAgent {

    /**
     * 调用方：平台服务令牌里的最终用户、租户和发起调用的平台 Agent。
     *
     * @param agent act.sub，例如 agent:vendor.pur/procurement-assistant
     */
    record Caller(String user, String tenant, String agent) {}

    /**
     * 一次请求。
     *
     * @param contextId A2A 的上下文编号，同一段对话不变，可用来保存多轮状态
     */
    record Request(Caller caller, String text, String contextId) {}

    /**
     * 回复。
     *
     * @param needsInput 为 true 时任务停在 input-required，调用方带着同一个任务回答后再次调用
     */
    record Reply(String text, boolean needsInput) {

        public static Reply done(String text) {
            return new Reply(text, false);
        }

        public static Reply ask(String question) {
            return new Reply(question, true);
        }
    }

    Reply reply(Request request);
}

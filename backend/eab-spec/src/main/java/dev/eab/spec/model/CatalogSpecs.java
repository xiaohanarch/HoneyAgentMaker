package dev.eab.spec.model;

import dev.eab.spec.model.Common.Routing;
import dev.eab.spec.model.Common.Trigger;
import java.util.List;
import java.util.Map;

/** 能力目录与接入相关的 kind：岗位助手、远程 Agent、知识源、连接、平台规则集 */
public final class CatalogSpecs {

    private CatalogSpecs() {}

    public record Kpi(String name, String displayName, String unit, Double target, String direction) {}

    /** 岗位助手（主提示词第 3 节），统一入口先路由到助手，助手再编排一组 Agent */
    public record AssistantSpec(List<String> members, Routing routing, List<Kpi> kpis, List<Trigger> triggers) {}

    public record RemoteAuth(String type) {}

    /** 远程 Agent 登记（主提示词 8.4、10.5 节），Agent Card 地址由连接别名绑定 */
    public record RemoteAgentSpec(
            String connection,
            String agentCardPath,
            RemoteAuth auth,
            Routing routing,
            List<String> allowedCallers,
            String assistant) {}

    public record Document(String title, String path) {}

    /** 文档知识库，文档路径相对于资产目录 */
    public record KnowledgeSourceSpec(List<Document> documents) {}

    public record BindingAuth(String type, String credentialRef) {}

    public record Binding(String url, BindingAuth auth) {}

    /** 连接别名的定义：唯一允许出现地址的 kind，每个环境一份绑定，凭据只写引用 */
    public record ConnectionSpec(String alias, String type, Map<String, Binding> bindings) {}

    /**
     * 平台安全规则（主提示词 13.1 节）。
     *
     * @param instruction 注入提示词的规则原文，租户只能看到 name 和 description
     * @param enforcement prompt 只写进提示词；guard 由监督层确定性执行；both 两者都有
     */
    public record PlatformRule(String id, String name, String description, String instruction, String enforcement) {}

    public record PlatformRuleSetSpec(List<PlatformRule> rules) {}
}

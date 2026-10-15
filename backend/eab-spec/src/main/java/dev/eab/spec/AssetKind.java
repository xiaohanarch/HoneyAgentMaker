package dev.eab.spec;

import dev.eab.spec.Layers.Layer;
import dev.eab.spec.kinds.AgentSchemas;
import dev.eab.spec.kinds.OtherSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.schema.Schema;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 全部资产种类（主提示词 5.1 节，外加 PlatformRuleSet），每种对应一份 schema 和一个 Java 记录类型。
 * 扩展、开发需求、连接只属于租户层，平台规则只属于平台层，其余三层都可以有。
 */
public enum AssetKind {
    Assistant(OtherSchemas::assistant, Asset.AssistantAsset.class, List.of()),
    Agent(AgentSchemas::agent, Asset.AgentAsset.class, List.of()),
    Skill(OtherSchemas::skill, Asset.SkillAsset.class, List.of()),
    ToolSource(OtherSchemas::toolSource, Asset.ToolSourceAsset.class, List.of()),
    SemanticPack(OtherSchemas::semanticPack, Asset.SemanticPackAsset.class, List.of()),
    KnowledgeSource(OtherSchemas::knowledgeSource, Asset.KnowledgeSourceAsset.class, List.of()),
    AgentExtension(AgentSchemas::agentExtension, Asset.AgentExtensionAsset.class, List.of(Layer.tenant)),
    RemoteAgent(OtherSchemas::remoteAgent, Asset.RemoteAgentAsset.class, List.of()),
    Workflow(OtherSchemas::workflow, Asset.WorkflowAsset.class, List.of()),
    EvalSuite(OtherSchemas::evalSuite, Asset.EvalSuiteAsset.class, List.of()),
    DevRequest(OtherSchemas::devRequest, Asset.DevRequestAsset.class, List.of(Layer.tenant)),
    Solution(OtherSchemas::solution, Asset.SolutionAsset.class, List.of()),
    Connection(OtherSchemas::connection, Asset.ConnectionAsset.class, List.of(Layer.tenant)),
    PlatformRuleSet(OtherSchemas::platformRuleSet, Asset.PlatformRuleSetAsset.class, List.of(Layer.platform));

    private final Supplier<Schema> schemaFactory;
    private final Class<? extends Asset> type;
    private final List<Layer> layers;
    private Schema schema;

    AssetKind(Supplier<Schema> schemaFactory, Class<? extends Asset> type, List<Layer> layers) {
        this.schemaFactory = schemaFactory;
        this.type = type;
        this.layers = layers;
    }

    public synchronized Schema schema() {
        if (schema == null) schema = schemaFactory.get();
        return schema;
    }

    public Class<? extends Asset> type() {
        return type;
    }

    /** 只能出现在哪些层；空表示三层都可以 */
    public List<Layer> allowedLayers() {
        return layers;
    }

    public static Optional<AssetKind> of(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst();
    }

    public static AssetKind of(Asset asset) {
        return valueOf(asset.kind());
    }
}

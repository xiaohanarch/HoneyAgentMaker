package dev.eab.spec.model;

import dev.eab.spec.Layers;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.CatalogSpecs.AssistantSpec;
import dev.eab.spec.model.CatalogSpecs.ConnectionSpec;
import dev.eab.spec.model.CatalogSpecs.KnowledgeSourceSpec;
import dev.eab.spec.model.CatalogSpecs.PlatformRuleSetSpec;
import dev.eab.spec.model.CatalogSpecs.RemoteAgentSpec;
import dev.eab.spec.model.Common.Metadata;
import dev.eab.spec.model.LifecycleSpecs.DevRequestSpec;
import dev.eab.spec.model.LifecycleSpecs.EvalSuiteSpec;
import dev.eab.spec.model.LifecycleSpecs.SolutionSpec;
import dev.eab.spec.model.StepSpecs.SkillSpec;
import dev.eab.spec.model.StepSpecs.WorkflowSpec;

/**
 * 一个资产：公共信封 apiVersion、kind、metadata，加上按 kind 区分的 spec。
 * 用模式匹配区分 kind，例如 {@code if (asset instanceof AgentAsset agent)}。
 */
public sealed interface Asset {

    String apiVersion();

    String kind();

    Metadata metadata();

    Object spec();

    default Layer layer() {
        return Layers.layerOfNamespace(metadata().namespace());
    }

    /** 带精确版本的引用，例如 vendor.fin/transaction-anomaly-analyst@1.2.0 */
    default String versionedRef() {
        return metadata().namespace() + "/" + metadata().name() + "@"
                + metadata().version();
    }

    default String label() {
        return kind() + " " + versionedRef();
    }

    record AssistantAsset(String apiVersion, String kind, Metadata metadata, AssistantSpec spec) implements Asset {}

    record AgentAsset(String apiVersion, String kind, Metadata metadata, AgentSpec spec) implements Asset {}

    record SkillAsset(String apiVersion, String kind, Metadata metadata, SkillSpec spec) implements Asset {}

    record ToolSourceAsset(String apiVersion, String kind, Metadata metadata, ToolSourceSpec spec) implements Asset {}

    record SemanticPackAsset(String apiVersion, String kind, Metadata metadata, SemanticPackSpec spec)
            implements Asset {}

    record KnowledgeSourceAsset(String apiVersion, String kind, Metadata metadata, KnowledgeSourceSpec spec)
            implements Asset {}

    record AgentExtensionAsset(String apiVersion, String kind, Metadata metadata, AgentExtensionSpec spec)
            implements Asset {}

    record RemoteAgentAsset(String apiVersion, String kind, Metadata metadata, RemoteAgentSpec spec) implements Asset {}

    record WorkflowAsset(String apiVersion, String kind, Metadata metadata, WorkflowSpec spec) implements Asset {}

    record EvalSuiteAsset(String apiVersion, String kind, Metadata metadata, EvalSuiteSpec spec) implements Asset {}

    record DevRequestAsset(String apiVersion, String kind, Metadata metadata, DevRequestSpec spec) implements Asset {}

    record SolutionAsset(String apiVersion, String kind, Metadata metadata, SolutionSpec spec) implements Asset {}

    record ConnectionAsset(String apiVersion, String kind, Metadata metadata, ConnectionSpec spec) implements Asset {}

    record PlatformRuleSetAsset(String apiVersion, String kind, Metadata metadata, PlatformRuleSetSpec spec)
            implements Asset {}
}

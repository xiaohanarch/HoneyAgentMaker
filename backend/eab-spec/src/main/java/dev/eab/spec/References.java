package dev.eab.spec;

import dev.eab.spec.model.AgentExtensionSpec.ToolEntryOrRef;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.AgentSpec.Hooks;
import dev.eab.spec.model.AgentSpec.NormalizedToolEntry;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.StepSpecs.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 收集资产里对其他资产的全部引用，静态检查和血缘关系共用 */
public final class References {

    private References() {}

    /** 资产里的一处引用：位置、引用字符串、允许的目标 kind（空表示不限） */
    public record Reference(String path, String ref, List<AssetKind> kinds) {}

    private static final Map<String, List<AssetKind>> TOOL_TARGET_KINDS = Map.of(
            "skill", List.of(AssetKind.Skill),
            "agent", List.of(AssetKind.Agent),
            "remoteAgent", List.of(AssetKind.RemoteAgent),
            "mcp", List.of(AssetKind.ToolSource),
            "api", List.of(AssetKind.ToolSource),
            "documents", List.of(AssetKind.KnowledgeSource));

    /** 扩展里直接写引用字符串时，可能指向的 kind */
    private static final List<AssetKind> SHORTHAND_TOOL_KINDS = List.of(
            AssetKind.ToolSource, AssetKind.Skill, AssetKind.Agent, AssetKind.RemoteAgent, AssetKind.KnowledgeSource);

    public static List<Reference> collect(Asset asset) {
        List<Reference> out = new ArrayList<>();
        switch (asset) {
            case Asset.AgentAsset agent -> {
                AgentSpec s = agent.spec();
                for (int i = 0; i < s.tools().size(); i++) {
                    addTool(out, s.tools().get(i).normalize(), "spec.tools[" + i + "]");
                }
                refList(out, s.semanticPacks(), "spec.semanticPacks", List.of(AssetKind.SemanticPack));
                List<String> stepSkills = new ArrayList<>();
                if (s.executionSteps() != null) {
                    for (var step : s.executionSteps().pre()) if (step.skill() != null) stepSkills.add(step.skill());
                    for (var step : s.executionSteps().post()) if (step.skill() != null) stepSkills.add(step.skill());
                }
                refList(out, stepSkills, "spec.executionSteps", List.of(AssetKind.Skill));
                hooks(out, s.hooks(), "spec.hooks");
                refList(out, s.evals() == null ? List.of() : s.evals().suites(), "spec.evals.suites",
                        List.of(AssetKind.EvalSuite));
            }
            case Asset.AgentExtensionAsset ext -> {
                var s = ext.spec();
                out.add(new Reference("spec.target", s.target(), List.of(AssetKind.Agent)));
                List<ToolEntryOrRef> tools = s.tools() == null ? List.of() : s.tools().add();
                for (int i = 0; i < tools.size(); i++) {
                    String path = "spec.tools.add[" + i + "]";
                    switch (tools.get(i)) {
                        case ToolEntryOrRef.Ref ref -> out.add(new Reference(path, ref.ref(), SHORTHAND_TOOL_KINDS));
                        case ToolEntryOrRef.Entry entry -> addTool(out, entry.entry().normalize(), path);
                    }
                }
                refList(out, s.skills() == null ? List.of() : s.skills().add(), "spec.skills.add",
                        List.of(AssetKind.Skill));
                refList(out, s.knowledge() == null ? List.of() : s.knowledge().add(), "spec.knowledge.add",
                        List.of(AssetKind.SemanticPack, AssetKind.KnowledgeSource));
                hooks(out, s.hooks(), "spec.hooks");
                refList(out, s.evals() == null ? List.of() : s.evals().suites(), "spec.evals.suites",
                        List.of(AssetKind.EvalSuite));
            }
            case Asset.AssistantAsset assistant -> refList(out, assistant.spec().members(), "spec.members",
                    List.of(AssetKind.Agent, AssetKind.RemoteAgent));
            case Asset.RemoteAgentAsset remote -> {
                refList(out, remote.spec().allowedCallers(), "spec.allowedCallers",
                        List.of(AssetKind.Agent, AssetKind.Assistant));
                if (remote.spec().assistant() != null) {
                    out.add(new Reference("spec.assistant", remote.spec().assistant(), List.of(AssetKind.Assistant)));
                }
            }
            case Asset.SkillAsset skill -> steps(out, skill.spec().steps(), "spec.steps");
            case Asset.WorkflowAsset workflow -> steps(out, workflow.spec().steps(), "spec.steps");
            case Asset.EvalSuiteAsset suite -> out.add(new Reference(
                    "spec.target", suite.spec().target(), List.of(AssetKind.Agent, AssetKind.AgentExtension)));
            case Asset.SemanticPackAsset pack -> {
                if (pack.spec().extendsRef() != null) {
                    out.add(new Reference("spec.extends", pack.spec().extendsRef(), List.of(AssetKind.SemanticPack)));
                }
            }
            case Asset.SolutionAsset solution -> refList(out, solution.spec().assets(), "spec.assets", List.of());
            case Asset.DevRequestAsset request -> {
                if (request.spec().context().assistant() != null) {
                    out.add(new Reference("spec.context.assistant", request.spec().context().assistant(),
                            List.of(AssetKind.Assistant)));
                }
            }
            case Asset.ToolSourceAsset ignored -> {}
            case Asset.KnowledgeSourceAsset ignored -> {}
            case Asset.ConnectionAsset ignored -> {}
            case Asset.PlatformRuleSetAsset ignored -> {}
        }
        return out;
    }

    private static void addTool(List<Reference> out, NormalizedToolEntry tool, String path) {
        if (tool.type().equals("builtin")) return;
        out.add(new Reference(path + "." + tool.type(), tool.ref(), TOOL_TARGET_KINDS.get(tool.type())));
    }

    private static void hooks(List<Reference> out, Hooks hooks, String base) {
        if (hooks == null) return;
        for (String event : AgentSpec.HOOK_EVENTS) {
            var bindings = hooks.of(event);
            for (int i = 0; i < bindings.size(); i++) {
                var binding = bindings.get(i);
                String path = base + "." + event + "[" + i + "]";
                if (binding.workflow() != null) {
                    out.add(new Reference(path + ".workflow", binding.workflow(), List.of(AssetKind.Workflow)));
                } else {
                    out.add(new Reference(path + ".skill", binding.skill(), List.of(AssetKind.Skill)));
                }
            }
        }
    }

    private static void steps(List<Reference> out, List<Step> steps, String base) {
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String path = base + "[" + i + "]";
            if (step.skill() != null) out.add(new Reference(path + ".skill", step.skill(), List.of(AssetKind.Skill)));
            if (step.agent() != null) out.add(new Reference(path + ".agent", step.agent(), List.of(AssetKind.Agent)));
            if (step.source() != null) {
                out.add(new Reference(path + ".source", step.source(), List.of(AssetKind.ToolSource)));
            }
            if (step.thenSteps() != null) steps(out, step.thenSteps(), path + ".then");
            if (step.elseSteps() != null) steps(out, step.elseSteps(), path + ".else");
            if (step.branches() != null) {
                for (int j = 0; j < step.branches().size(); j++) {
                    steps(out, step.branches().get(j), path + ".branches[" + j + "]");
                }
            }
        }
    }

    private static void refList(List<Reference> out, List<String> refs, String base, List<AssetKind> kinds) {
        for (int i = 0; i < refs.size(); i++) out.add(new Reference(base + "[" + i + "]", refs.get(i), kinds));
    }
}

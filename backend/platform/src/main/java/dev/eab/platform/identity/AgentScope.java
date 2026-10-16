package dev.eab.platform.identity;

import dev.eab.assembler.EffectiveDefinition;
import dev.eab.directory.DemoDirectory;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.Asset.WorkflowAsset;
import dev.eab.spec.model.StepSpecs;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 声明的 BO 操作，作为 Agent 令牌的 scope（主提示词 7.1 节“Agent 声明的权限范围”）。
 * 来源有两类：直接挂载的 bo-api 工具，以及所挂技能里调用 bo-api 工具来源的步骤。
 */
public final class AgentScope {

    private AgentScope() {}

    /** 默认只认已发布的版本，草稿里声明的操作不会进入 Agent 令牌 */
    public static List<String> declaredBoOperations(EffectiveDefinition definition, AssetResolver resolver) {
        return declaredBoOperations(definition, resolver, AssetResolver.PUBLISHED);
    }

    public static List<String> declaredBoOperations(
            EffectiveDefinition definition, AssetResolver resolver, List<Status> statuses) {
        Set<String> ops = new LinkedHashSet<>();
        for (var tool : definition.tools()) {
            if (tool.type().equals("api")
                    && isBoSource(resolver, tool.source(), statuses)
                    && DemoDirectory.isBoOperation(tool.name())) {
                ops.add(tool.name());
            }
            if (!tool.type().equals("skill")) continue;
            // 有效定义里的来源带精确版本（namespace/name@1.1.0），可以直接作为版本范围解析
            resolver.resolveOfKind(tool.source(), SkillAsset.class, statuses)
                    .ifPresent(skill ->
                            collectSteps(skill.spec().steps(), resolver, statuses, ops, new LinkedHashSet<>()));
        }
        // 钩子里的工作流和技能以同一个 Agent 身份运行，它们调用的 BO 操作同样要进入 Agent 令牌
        for (var hook : definition.hooks()) {
            if (hook.workflow() != null) {
                resolver.resolveOfKind(hook.workflow(), WorkflowAsset.class, statuses)
                        .ifPresent(w -> collectSteps(w.spec().steps(), resolver, statuses, ops, new LinkedHashSet<>()));
            }
            if (hook.skill() != null) {
                resolver.resolveOfKind(hook.skill(), SkillAsset.class, statuses)
                        .ifPresent(s -> collectSteps(s.spec().steps(), resolver, statuses, ops, new LinkedHashSet<>()));
            }
        }
        return List.copyOf(ops);
    }

    /** 步骤里直接调用的 BO 工具，以及嵌套技能里的；visited 防止技能互相引用时死循环 */
    private static void collectSteps(
            List<StepSpecs.Step> steps,
            AssetResolver resolver,
            List<Status> statuses,
            Set<String> ops,
            Set<String> visited) {
        for (var step : StepSpecs.flatten(steps)) {
            if (step.type().equals("tool")
                    && step.tool() != null
                    && step.source() != null
                    && isBoSource(resolver, step.source(), statuses)
                    && DemoDirectory.isBoOperation(step.tool())) {
                ops.add(step.tool());
            }
            if (step.type().equals("skill") && step.skill() != null && visited.add(step.skill())) {
                resolver.resolveOfKind(step.skill(), SkillAsset.class, statuses)
                        .ifPresent(s -> collectSteps(s.spec().steps(), resolver, statuses, ops, visited));
            }
        }
    }

    private static boolean isBoSource(AssetResolver resolver, String ref, List<Status> statuses) {
        if (!AssetRef.isValid(ref)) return false;
        return resolver.resolveOfKind(ref, ToolSourceAsset.class, statuses)
                .map(s -> s.spec().type().equals("bo-api"))
                .orElse(false);
    }
}

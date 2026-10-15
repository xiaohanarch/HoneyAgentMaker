package dev.eab.assembler;

import dev.eab.assembler.EffectiveDefinition.EffectiveHook;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.model.AgentSpec;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import java.util.ArrayList;
import java.util.List;

/** 钩子的合并与执行（主提示词 5.4 节第 4 条） */
public final class Hooks {

    private Hooks() {}

    record HookResult(List<EffectiveHook> hooks, List<AssemblyIssue> issues) {}

    private static List<EffectiveHook> hooksOf(Asset owner, AgentSpec.Hooks hooks, String event) {
        if (hooks == null) return List.of();
        Layer layer = owner.layer();
        return hooks.of(event).stream()
                .map(b -> new EffectiveHook(
                        event,
                        layer,
                        owner.versionedRef(),
                        b.workflow(),
                        b.skill(),
                        b.when(),
                        layer == Layer.tenant ? "isolate" : "propagate"))
                .toList();
    }

    /**
     * 钩子按“标品钩子 → 租户钩子”顺序排列。条件按事件做类型检查：标品钩子条件无效是错误；
     * 租户钩子条件无效时该钩子停用并给出警告。标品升级改了输出字段导致租户钩子失效时，就是这种情况（故事 F）。
     */
    static HookResult merge(AgentAsset agent, List<AgentExtensionAsset> extensions) {
        List<EffectiveHook> all = new ArrayList<>();
        for (String event : AgentSpec.HOOK_EVENTS) {
            all.addAll(hooksOf(agent, agent.spec().hooks(), event));
            for (var ext : extensions) all.addAll(hooksOf(ext, ext.spec().hooks(), event));
        }
        List<AssemblyIssue> issues = new ArrayList<>();
        List<EffectiveHook> hooks = new ArrayList<>();
        for (var hook : all) {
            if (hook.when() == null) {
                hooks.add(hook);
                continue;
            }
            var check = Conditions.check(
                    Conditions.hookEnvironment(hook.event(), agent.spec().output()), hook.when());
            if (check.valid()) {
                hooks.add(hook);
                continue;
            }
            boolean isTenant = hook.failurePolicy().equals("isolate");
            String message = hook.event() + " 钩子的条件 \"" + hook.when() + "\" 无效：" + check.reason()
                    + (isTenant ? "。该租户钩子已停用" : "");
            issues.add(
                    isTenant
                            ? AssemblyIssue.warning(
                                    "HOOK_CONDITION_INVALID", message, hook.source(), "spec.hooks." + hook.event())
                            : AssemblyIssue.error(
                                    "HOOK_CONDITION_INVALID", message, hook.source(), "spec.hooks." + hook.event()));
        }
        return new HookResult(hooks, issues);
    }

    public record HookFailure(EffectiveHook hook, String error) {}

    /**
     * @param isolatedFailures 已隔离的租户钩子失败，主流程继续，结果里要提示
     */
    public record HookRunReport(
            List<EffectiveHook> executed, List<EffectiveHook> skipped, List<HookFailure> isolatedFailures) {}

    /** 运行时怎么跑工作流或技能由 runner 决定 */
    public interface HookRunner {
        /** 判断钩子条件是否满足；没有条件的钩子不会调用它 */
        boolean shouldRun(EffectiveHook hook) throws Exception;

        void run(EffectiveHook hook) throws Exception;
    }

    /** 按顺序执行某个事件的钩子：租户钩子失败只记录，标品钩子失败向上抛出 */
    public static HookRunReport run(List<EffectiveHook> hooks, String event, HookRunner runner) throws Exception {
        List<EffectiveHook> executed = new ArrayList<>();
        List<EffectiveHook> skipped = new ArrayList<>();
        List<HookFailure> failures = new ArrayList<>();
        for (var hook : hooks) {
            if (!hook.event().equals(event)) continue;
            try {
                if (hook.when() != null && !runner.shouldRun(hook)) {
                    skipped.add(hook);
                    continue;
                }
                runner.run(hook);
                executed.add(hook);
            } catch (Exception e) {
                if (hook.failurePolicy().equals("propagate")) throw e;
                failures.add(new HookFailure(hook, e.getMessage()));
            }
        }
        return new HookRunReport(executed, skipped, failures);
    }
}

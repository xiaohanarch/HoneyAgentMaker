package dev.eab.spec.model;

import java.util.List;

/** 开发生命周期相关的 kind：评测集、开发需求、方案 */
public final class LifecycleSpecs {

    private LifecycleSpecs() {}

    public record Assertion(
            String type,
            String tool,
            String text,
            String path,
            Double min,
            Double max,
            String expression,
            String rubric,
            String description) {}

    public record EvalCase(String id, String user, String question, String verifiedTask, List<Assertion> assertions) {}

    /**
     * 评测集（主提示词 10.8 节）。
     *
     * @param visibility internal 的评测集对其他层不可见（主提示词 5.2 节）
     * @param tier smoke 冒烟集全部用例须 3 分钟内完成
     */
    public record EvalSuiteSpec(String target, String visibility, String tier, double minPassRate, List<EvalCase> cases) {}

    public record DevRequestContext(String assistant, List<String> semanticObjects, List<String> rules) {}

    /** 业务用户提交的开发需求（主提示词 9.3 节、故事 E） */
    public record DevRequestSpec(
            String requester,
            String originalText,
            List<String> supplements,
            DevRequestContext context,
            String status) {}

    /** 方案：编译、签名、发布、晋级、回滚的单位（主提示词第 12 节） */
    public record SolutionSpec(List<String> assets) {}
}

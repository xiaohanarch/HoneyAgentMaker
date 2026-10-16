package dev.eab.platform.studio;

import dev.eab.eval.Assertions;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 评测断言的平台入口（P5 的公开接口，P6 等调用方依赖它）。断言、裁判的实现在评测引擎 eab-eval（P8，ADR 0013），
 * 这里只做类型转换和委托，接口保持不变。
 */
public final class EvalRunner {

    /** 一个用例的运行结果 */
    public record CaseRun(String text, Map<String, Object> output, List<RunEvent> events, String status, String error) {

        /** 本次运行调用过的工具（含子 Agent、技能内部的调用） */
        public Set<String> calledTools() {
            return new LinkedHashSet<>(engine().calledTools());
        }

        Assertions.CaseRun engine() {
            return new Assertions.CaseRun(text, output, events, status, error);
        }
    }

    public record AssertionResult(String type, String description, boolean passed, String detail) {

        static AssertionResult of(Assertions.AssertionResult r) {
            return new AssertionResult(r.type(), r.description(), r.passed(), r.detail());
        }

        Assertions.AssertionResult engine() {
            return new Assertions.AssertionResult(type, description, passed, detail);
        }
    }

    public record CaseResult(
            String id,
            String user,
            String question,
            boolean passed,
            List<AssertionResult> assertions,
            String text,
            String runId,
            String traceId) {

        static CaseResult of(Assertions.CaseResult r) {
            return new CaseResult(
                    r.id(),
                    r.user(),
                    r.question(),
                    r.passed(),
                    r.assertions().stream().map(AssertionResult::of).toList(),
                    r.text(),
                    r.runId(),
                    r.traceId());
        }
    }

    /** 裁判：脚本化模型下确定性，配置了真实模型时用 judge 档位的模型 */
    public interface Judge {
        AssertionResult judge(String rubric, String description, String text);

        /** 转成评测引擎的裁判 */
        default Assertions.Judge engine() {
            return (rubric, description, text) ->
                    judge(rubric, description, text).engine();
        }

        static Judge of(Assertions.Judge judge) {
            return (rubric, description, text) -> AssertionResult.of(judge.judge(rubric, description, text));
        }
    }

    /** 确定性裁判 */
    public static final Judge DETERMINISTIC = Judge.of(Assertions.DETERMINISTIC);

    /** 模型裁判；模型调用失败时判为不通过并写明原因，不静默通过 */
    public static Judge model(ChatModel model, String modelName) {
        return Judge.of(Assertions.model(model, modelName));
    }

    private EvalRunner() {}

    public static CaseResult check(EvalCase evalCase, CaseRun run, String runId, String traceId, Judge judge) {
        return CaseResult.of(Assertions.check(evalCase, run.engine(), runId, traceId, judge.engine()));
    }

    public static String toJson(List<CaseResult> results) {
        return dev.eab.spec.Json.MAPPER.writeValueAsString(results);
    }
}

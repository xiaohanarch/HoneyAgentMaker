package dev.eab.eval;

import dev.eab.eval.Assertions.CaseResult;
import dev.eab.eval.Assertions.Judge;
import dev.eab.spec.model.LifecycleSpecs.EvalCase;
import dev.eab.spec.model.LifecycleSpecs.EvalSuiteSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * 运行一个评测集：普通用例各运行一次，Given/When/Then 场景按各自的重复次数运行；按通过率与最低通过率判定。
 * 怎样运行一个用例（以哪个用户、运行哪个版本、确认点怎么处理）由调用方的 CaseExecutor 决定。
 */
public final class SuiteRunner {

    private SuiteRunner() {}

    /** 运行一个用例，返回运行结果与运行、追踪编号 */
    public interface CaseExecutor {
        Execution execute(EvalCase evalCase);
    }

    public record Execution(Assertions.CaseRun run, String runId, String traceId) {}

    /**
     * @param status passed 或 failed
     * @param millis 运行耗时
     */
    public record SuiteResult(
            String status,
            int total,
            int passed,
            double passRate,
            double minPassRate,
            List<CaseResult> cases,
            long millis) {}

    /** 评测集展开后的全部用例：普通用例加上场景（重复的场景编号后加 #n） */
    public static List<EvalCase> expand(EvalSuiteSpec spec) {
        List<EvalCase> cases = new ArrayList<>(spec.cases() == null ? List.of() : spec.cases());
        for (Scenarios.Scenario s : Scenarios.parse(spec.scenarios())) {
            for (int i = 1; i <= s.repeat(); i++) {
                EvalCase c = s.evalCase();
                String id = s.repeat() == 1 ? c.id() : c.id() + " #" + i;
                cases.add(new EvalCase(id, c.user(), c.question(), c.verifiedTask(), c.assertions()));
            }
        }
        return cases;
    }

    public static SuiteResult run(EvalSuiteSpec spec, CaseExecutor executor, Judge judge) {
        long start = System.currentTimeMillis();
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : expand(spec)) {
            Execution e;
            try {
                e = executor.execute(c);
            } catch (RuntimeException ex) {
                e = new Execution(new Assertions.CaseRun(null, null, List.of(), "failed", ex.getMessage()), null, null);
            }
            results.add(Assertions.check(c, e.run(), e.runId(), e.traceId(), judge));
        }
        int passed = (int) results.stream().filter(CaseResult::passed).count();
        double rate = results.isEmpty() ? 0 : (double) passed / results.size();
        String status = !results.isEmpty() && rate >= spec.minPassRate() ? "passed" : "failed";
        return new SuiteResult(
                status,
                results.size(),
                passed,
                Math.round(rate * 1000) / 1000.0,
                spec.minPassRate(),
                results,
                System.currentTimeMillis() - start);
    }
}

package dev.eab.runtime.routing;

import dev.eab.runtime.Bm25;
import dev.eab.runtime.routing.Catalog.Entry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 路由（主提示词 9.2 节），两级编排：入口 → 岗位助手 → Agent（或导航场景）；能力目录里标记为可直达的 Agent 也可以由入口直接选中。
 * 每一级依次是：候选召回 → 权限过滤（鉴权点 1）→ 决策；置信度低于阈值时向用户澄清，没有合适的候选时给出能力缺口。
 */
public final class Router {

    /** 召回的候选数 */
    static final int TOP_K = 5;

    /** 召回分数低于它视为没有匹配的能力 */
    public static final double MIN_SCORE = 1.0;

    /** 置信度低于它时向用户澄清 */
    public static final double CLARIFY_BELOW = 0.6;

    /** 候选召回。演示用 BM25，接口为嵌入检索预留（ADR 0009） */
    public interface Retriever {
        List<Candidate> recall(String query, List<Entry> entries, int k);
    }

    /**
     * 一个候选。
     *
     * @param matched 分数最高的那条路由说明或示例话术
     * @param filtered 被权限过滤掉时的原因，否则为 null
     */
    public record Candidate(Entry entry, double score, String matched, String filtered) {
        public Candidate withFiltered(String reason) {
            return new Candidate(entry, score, matched, reason);
        }
    }

    /**
     * 决策结果。
     *
     * @param decidedBy score（按召回分数）或 model（模型在候选里选择）
     */
    public record Decision(Entry chosen, double confidence, String reason, String decidedBy) {}

    /** 决策：在过滤后的候选里选一个，给出理由和置信度 */
    public interface Decider {
        Decision decide(String query, List<Candidate> candidates);
    }

    /** 权限过滤：允许返回 null，否则返回不允许的原因 */
    public interface Filter extends Function<Entry, String> {}

    /**
     * 一级的路由结果。
     *
     * @param outcome route（选中）、clarify（需要澄清，options 是前两个候选）、gap（没有合适的能力）
     */
    public record Level(String outcome, List<Candidate> candidates, Decision decision, List<Entry> options) {}

    private final Retriever retriever;
    private final Decider decider;

    public Router(Retriever retriever, Decider decider) {
        this.retriever = retriever;
        this.decider = decider;
    }

    public Level route(String query, List<Entry> entries, Filter filter) {
        List<Candidate> recalled = retriever.recall(query, entries, TOP_K);
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate c : recalled) {
            String denied = c.score() < MIN_SCORE ? null : filter.apply(c.entry());
            candidates.add(denied == null ? c : c.withFiltered(denied));
        }
        List<Candidate> usable = candidates.stream()
                .filter(c -> c.filtered() == null && c.score() >= MIN_SCORE)
                .toList();
        if (usable.isEmpty()) return new Level("gap", candidates, null, List.of());
        Decision decision = decider.decide(query, usable);
        if (decision.chosen() != null && decision.confidence() >= CLARIFY_BELOW) {
            return new Level("route", candidates, decision, List.of());
        }
        if (usable.size() == 1) {
            // 只有一个可用候选时不必澄清，直接选它
            Decision only = new Decision(
                    usable.getFirst().entry(), decision.confidence(), decision.reason(), decision.decidedBy());
            return new Level("route", candidates, only, List.of());
        }
        return new Level(
                "clarify",
                candidates,
                decision,
                usable.stream().limit(2).map(Candidate::entry).toList());
    }

    /** BM25 召回：每个条目取路由说明和示例话术里分数最高的一条 */
    public static final class Bm25Retriever implements Retriever {
        @Override
        public List<Candidate> recall(String query, List<Entry> entries, int k) {
            List<Bm25.Passage> passages = new ArrayList<>();
            for (Entry entry : entries) {
                for (String text : entry.texts())
                    passages.add(new Bm25.Passage(entry.ref(), entry.displayName(), text));
            }
            Map<String, Candidate> best = new LinkedHashMap<>();
            for (Bm25.Hit hit : Bm25.search(passages, query, passages.size())) {
                String ref = hit.passage().source();
                if (best.containsKey(ref)) continue;
                Entry entry = entries.stream()
                        .filter(e -> e.ref().equals(ref))
                        .findFirst()
                        .orElseThrow();
                best.put(ref, new Candidate(entry, hit.score(), hit.passage().text(), null));
            }
            return best.values().stream()
                    .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                    .limit(k)
                    .toList();
        }
    }

    /**
     * 按召回分数决策：分数最高的候选，置信度看它领先第二名的程度。脚本化模型下使用，结果确定、可复现。
     */
    public static final class ScoreDecider implements Decider {
        @Override
        public Decision decide(String query, List<Candidate> candidates) {
            Candidate top = candidates.getFirst();
            double second = candidates.size() > 1 ? candidates.get(1).score() : 0;
            double confidence = Math.round(top.score() / (top.score() + second) * 100) / 100.0;
            String reason = "问题与“" + top.matched() + "”最接近（召回分数 " + top.score()
                    + (candidates.size() > 1
                            ? "，第二名 " + candidates.get(1).entry().displayName() + " " + second
                            : "") + "）";
            return new Decision(top.entry(), confidence, reason, "score");
        }
    }
}

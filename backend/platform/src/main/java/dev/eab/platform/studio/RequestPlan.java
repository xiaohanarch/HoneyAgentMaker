package dev.eab.platform.studio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图驱动生成的六步方案卡（主提示词 10.3 节）：意图澄清、意图分析与 fit-gap、需求摘要、技术规格、资产草稿、评测集草稿。
 * 模型的输出和开发者保存的修改都先经 {@link #parse} 校验，不合格时模型路径退回检索兜底、保存路径直接报错。
 *
 * @param decidedBy model（模型生成）或 retrieval（模型不可用时的检索兜底）
 */
public record RequestPlan(
        String decidedBy,
        String generatedAt,
        List<Clarification> clarifications,
        FitGap fitGap,
        Summary summary,
        List<AssetChange> assets,
        List<DraftCard> drafts,
        EvalSuiteCard evalSuite) {

    /** 卡1 意图澄清：带选项的问题，最多 3 条 */
    public record Clarification(String question, List<String> options) {}

    /**
     * 卡2 意图分析与 fit-gap。
     *
     * @param verdict 三档结论：satisfied 标品已满足、extend 扩展标品即可、new 需要新建
     * @param score 匹配度 0~1
     */
    public record FitGap(String verdict, double score, List<Matched> matched, String reason) {

        public record Matched(String ref, String reason) {}
    }

    /** 卡3 需求摘要（PRD-lite） */
    public record Summary(String user, String scenario, List<String> metrics, String boundaries) {}

    /** 卡4 技术规格：复用或新建的资产清单 */
    public record AssetChange(String action, String kind, String ref, String note) {}

    /** 卡5 资产草稿：YAML 里带 metadata（namespace/name），采纳时按它创建工作区草稿 */
    public record DraftCard(String kind, String yaml) {}

    /** 卡6 评测集草稿 */
    public record EvalSuiteCard(String name, String yaml) {}

    static final List<String> VERDICTS = List.of("satisfied", "extend", "new");
    static final int MAX_CLARIFICATIONS = 3;

    /** 宽松地把模型输出或前端提交的树解析成方案卡；结构不合格时返回 null（调用方决定兜底或报错） */
    static RequestPlan parse(Map<String, Object> map) {
        if (map == null) return null;
        try {
            String decidedBy = string(map.get("decidedBy"), List.of("model", "retrieval"));
            if (decidedBy == null) return null;
            String generatedAt = text(map.get("generatedAt"));
            List<Clarification> clarifications = new ArrayList<>();
            for (Object item : list(map.get("clarifications"))) {
                if (!(item instanceof Map<?, ?> m)) return null;
                String question = text(m.get("question"));
                List<String> options = new ArrayList<>();
                for (Object option : list(m.get("options"))) {
                    String value = text(option);
                    if (value != null) options.add(value);
                }
                if (question == null || options.isEmpty()) return null;
                clarifications.add(new Clarification(question, options));
                if (clarifications.size() == MAX_CLARIFICATIONS) break;
            }
            if (!(map.get("fitGap") instanceof Map<?, ?> f)) return null;
            String verdict = string(f.get("verdict"), VERDICTS);
            Double score = number(f.get("score"));
            String reason = text(f.get("reason"));
            if (verdict == null || score == null || score < 0 || score > 1 || reason == null) return null;
            List<FitGap.Matched> matched = new ArrayList<>();
            for (Object item : list(f.get("matched"))) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String ref = text(m.get("ref"));
                String why = text(m.get("reason"));
                if (ref != null) matched.add(new FitGap.Matched(ref, why == null ? "" : why));
            }
            if (!(map.get("summary") instanceof Map<?, ?> s)) return null;
            String user = text(s.get("user"));
            String scenario = text(s.get("scenario"));
            String boundaries = text(s.get("boundaries"));
            if (user == null || scenario == null || boundaries == null) return null;
            List<String> metrics = new ArrayList<>();
            for (Object metric : list(s.get("metrics"))) {
                String value = text(metric);
                if (value != null) metrics.add(value);
            }
            List<AssetChange> assets = new ArrayList<>();
            for (Object item : list(map.get("assets"))) {
                if (!(item instanceof Map<?, ?> m)) return null;
                String action = string(m.get("action"), List.of("reuse", "create"));
                String kind = text(m.get("kind"));
                String ref = text(m.get("ref"));
                String note = text(m.get("note"));
                if (action == null || kind == null || ref == null) return null;
                assets.add(new AssetChange(action, kind, ref, note == null ? "" : note));
            }
            List<DraftCard> drafts = new ArrayList<>();
            for (Object item : list(map.get("drafts"))) {
                if (!(item instanceof Map<?, ?> m)) return null;
                String kind = string(m.get("kind"), List.of("Skill", "AgentExtension"));
                String yaml = text(m.get("yaml"));
                if (kind == null || yaml == null) return null;
                drafts.add(new DraftCard(kind, yaml));
            }
            EvalSuiteCard evalSuite = null;
            if (map.get("evalSuite") instanceof Map<?, ?> e) {
                String name = text(e.get("name"));
                String yaml = text(e.get("yaml"));
                if (name == null || yaml == null) return null;
                evalSuite = new EvalSuiteCard(name, yaml);
            }
            return new RequestPlan(
                    decidedBy,
                    generatedAt,
                    List.copyOf(clarifications),
                    new FitGap(verdict, score, List.copyOf(matched), reason),
                    new Summary(user, scenario, List.copyOf(metrics), boundaries),
                    List.copyOf(assets),
                    List.copyOf(drafts),
                    evalSuite);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value instanceof String s && !s.isBlank()) return s.strip();
        return null;
    }

    private static String string(Object value, List<String> allowed) {
        String text = text(value);
        return text == null || !allowed.contains(text) ? null : text;
    }

    private static Double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> l) return l;
        return List.of();
    }

    /** 保存与返回时的树形态：解析的反向，字段顺序与契约一致 */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decidedBy", decidedBy);
        map.put("generatedAt", generatedAt);
        map.put(
                "clarifications",
                clarifications.stream()
                        .map(c -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("question", c.question());
                            item.put("options", c.options());
                            return item;
                        })
                        .toList());
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("verdict", fitGap.verdict());
        gap.put("score", fitGap.score());
        gap.put(
                "matched",
                fitGap.matched().stream()
                        .map(m -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("ref", m.ref());
                            item.put("reason", m.reason());
                            return item;
                        })
                        .toList());
        gap.put("reason", fitGap.reason());
        map.put("fitGap", gap);
        Map<String, Object> prd = new LinkedHashMap<>();
        prd.put("user", summary.user());
        prd.put("scenario", summary.scenario());
        prd.put("metrics", summary.metrics());
        prd.put("boundaries", summary.boundaries());
        map.put("summary", prd);
        map.put(
                "assets",
                assets.stream()
                        .map(a -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("action", a.action());
                            item.put("kind", a.kind());
                            item.put("ref", a.ref());
                            item.put("note", a.note());
                            return item;
                        })
                        .toList());
        map.put(
                "drafts",
                drafts.stream()
                        .map(d -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("kind", d.kind());
                            item.put("yaml", d.yaml());
                            return item;
                        })
                        .toList());
        map.put("evalSuite", evalSuite == null ? null : Map.of("name", evalSuite.name(), "yaml", evalSuite.yaml()));
        return map;
    }
}

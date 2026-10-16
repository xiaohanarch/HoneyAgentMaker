package dev.eab.platform.studio;

import dev.eab.platform.studio.StudioCatalog.Item;
import dev.eab.platform.studio.StudioCatalog.Pack;
import dev.eab.platform.studio.StudioCatalog.Process;
import dev.eab.platform.studio.StudioCatalog.Tool;
import dev.eab.runtime.Bm25;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 向导每一步的检索推荐：用 BM25 在能力清单里找和意图最接近的候选，得出一份不依赖模型的推荐。
 * 它既是交给模型挑选的候选来源，也是模型不可用或输出不合格时的退路；评测集初稿也在这里从语义包的已验证任务生成。
 */
final class StudioDefaults {

    private StudioDefaults() {}

    /** 召回分数低于它的候选不推荐 */
    static final double MIN_SCORE = 0.5;

    /** 评测集初稿只收录相关度不低于最高分这个比例的已验证任务 */
    static final double RELEVANCE_RATIO = 0.8;

    record Scored<T>(T item, double score) {}

    static <T> List<Scored<T>> rank(List<T> items, Function<T, String> text, String query, int limit) {
        List<Bm25.Passage> passages = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            passages.add(new Bm25.Passage(String.valueOf(i), "", text.apply(items.get(i))));
        }
        List<Scored<T>> result = new ArrayList<>();
        for (Bm25.Hit hit : Bm25.search(passages, query == null ? "" : query, limit)) {
            if (hit.score() < MIN_SCORE) continue;
            result.add(new Scored<>(items.get(Integer.parseInt(hit.passage().source())), round(hit.score())));
        }
        return result;
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    // ── 每一步的检索推荐 ──

    static Map<String, Object> purpose(String intent, StudioCatalog catalog) {
        List<Map<String, Object>> similar = new ArrayList<>();
        for (var s : rank(catalog.agents(), Item::text, intent, 3)) {
            similar.add(Map.of("ref", s.item().ref(), "name", s.item().name(), "score", s.score()));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("intent", intent.strip());
        value.put("similar", similar);
        return value;
    }

    /** 没有模型时的命名：显示名取意图前 12 个字，标识用意图的短哈希，保证可用且不重复 */
    static Map<String, Object> naming(String intent) {
        String trimmed = intent.strip().replaceAll("[，。,.!！?？\\s]+", "");
        String displayName = trimmed.length() > 12 ? trimmed.substring(0, 12) : trimmed;
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", "agent-" + shortHash(intent));
        value.put("displayName", displayName.isEmpty() ? "新建 Agent" : displayName);
        value.put("description", intent.strip());
        return value;
    }

    static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> process(String intent, StudioCatalog catalog) {
        var ranked = rank(catalog.processes(), p -> p.name() + " " + p.description(), intent, 1);
        String code = ranked.isEmpty()
                ? catalog.processes().getFirst().code()
                : ranked.getFirst().item().code();
        return Map.of("businessProcess", List.of(code));
    }

    static Map<String, Object> collaborators(String intent, StudioCatalog catalog) {
        List<String> refs = rank(catalog.agents(), Item::text, intent, 2).stream()
                .map(s -> s.item().ref())
                .toList();
        return Map.of("agents", refs);
    }

    static Map<String, Object> knowledge(String intent, StudioCatalog catalog) {
        List<String> packs = rank(catalog.semanticPacks(), Pack::text, intent, 1).stream()
                .map(s -> s.item().ref())
                .toList();
        List<String> sources = rank(catalog.knowledge(), Item::text, intent, 1).stream()
                .map(s -> s.item().ref())
                .toList();
        return Map.of("semanticPacks", packs, "knowledgeSources", sources);
    }

    /** 工具只推荐只读的；需要写操作时由开发者自己勾选，并在确认点里声明 */
    static Map<String, Object> tools(String intent, StudioCatalog catalog) {
        List<String> skills = rank(
                        catalog.skills().stream()
                                .filter(s -> "L0".equals(s.writeLevel()))
                                .toList(),
                        Item::text,
                        intent,
                        2)
                .stream()
                .map(s -> s.item().ref())
                .toList();
        List<Map<String, Object>> tools = rank(
                        catalog.tools().stream()
                                .filter(t -> "L0".equals(t.writeLevel()))
                                .toList(),
                        t -> t.name() + " " + (t.description() == null ? "" : t.description()),
                        intent,
                        3)
                .stream()
                .map(s -> Map.<String, Object>of(
                        "source", s.item().source(), "name", s.item().name()))
                .toList();
        return Map.of("skills", skills, "tools", tools);
    }

    static Map<String, Object> triggers() {
        return Map.of("triggers", List.of(Map.of("type", "assistant")), "direct", true);
    }

    static Map<String, Object> channels(String intent) {
        List<String> channels = new ArrayList<>(List.of("assistant-web"));
        if (intent.contains("通知") || intent.contains("提醒") || intent.contains("IM")) channels.add("im");
        return Map.of("channels", channels);
    }

    /** 指令草稿与约束：约束从已选语义包的规则里取与意图相关的，带可执行检查的优先 */
    static Map<String, Object> instructions(String intent, Map<String, Object> draft, StudioCatalog catalog) {
        String name = String.valueOf(draft.getOrDefault("displayName", "这个 Agent"));
        String text = "你是" + name + "。目标：" + intent.strip() + "。\n"
                + "1. 先用工具取得事实数据，再下结论，结论要说明依据的数据和时间范围。\n"
                + "2. 引用金额时带币种，涉及数据权限过滤时说明过滤范围。\n"
                + "3. 平台约束触发时，先说明约束和处置建议，再给其他内容。";
        Set<String> selected = new LinkedHashSet<>(strings(draft.get("semanticPacks")));
        List<StudioCatalog.Rule> rules = catalog.semanticPacks().stream()
                .filter(p -> selected.contains(p.ref()))
                .flatMap(p -> p.rules().stream())
                .toList();
        List<String> constraints = rank(rules, r -> r.description() + " " + r.remedy(), intent, 2).stream()
                .map(s -> s.item().id())
                .toList();
        return Map.of("instructions", text, "constraints", constraints);
    }

    static Map<String, Object> model(String intent) {
        boolean reasoning = List.of("分析", "策略", "判断", "谈判", "诊断", "根因").stream().anyMatch(intent::contains);
        return Map.of("profile", reasoning ? "reasoning" : "fast", "temperature", reasoning ? 0.2 : 0.0);
    }

    /**
     * 评测集初稿：从已选语义包的已验证任务里取与意图最相关的几条，每条生成一个用例。
     * 断言优先用不变量：调用了任务要求的工具（Agent 挂载了的），以及按任务的期望结论做裁判。
     */
    static List<Map<String, Object>> evalCases(
            String intent, Map<String, Object> draft, StudioCatalog catalog, String testUser) {
        Set<String> selected = new LinkedHashSet<>(strings(draft.get("semanticPacks")));
        List<StudioCatalog.VerifiedTask> tasks = catalog.semanticPacks().stream()
                .filter(p -> selected.contains(p.ref()))
                .flatMap(p -> p.verifiedTasks().stream())
                .toList();
        Set<String> agentTools = new LinkedHashSet<>();
        for (Object tool : draft.get("tools") instanceof List<?> l ? l : List.of()) {
            if (tool instanceof Map<?, ?> m) agentTools.add(String.valueOf(m.get("name")));
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        var ranked = rank(tasks, StudioCatalog.VerifiedTask::question, intent, 3);
        // 只取和意图足够相关的任务：分数不低于最高分的 80%，避免把语义包里其他场景的任务带进来
        double top = ranked.isEmpty() ? 0 : ranked.getFirst().score();
        for (var scored : ranked) {
            if (scored.score() < top * RELEVANCE_RATIO) continue;
            var task = scored.item();
            List<Map<String, Object>> assertions = new ArrayList<>();
            for (String tool : task.tools()) {
                if (agentTools.isEmpty() || agentTools.contains(tool)) {
                    assertions.add(Map.of("type", "calledTool", "tool", tool, "description", "调用了 " + tool));
                }
            }
            String rubric =
                    "期望结论：" + task.expectedConclusion() + (task.failIf() == null ? "" : "；以下情况判为失败：" + task.failIf());
            assertions.add(Map.of("type", "judge", "rubric", rubric, "description", "结论符合已验证任务的期望"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", task.id().toLowerCase());
            c.put("user", testUser);
            c.put("question", task.question());
            c.put("verifiedTask", task.id());
            c.put("assertions", assertions);
            cases.add(c);
        }
        return cases;
    }

    static List<Process> processCandidates(StudioCatalog catalog) {
        return catalog.processes();
    }

    static List<Tool> toolCandidates(String intent, StudioCatalog catalog, int limit) {
        return rank(
                        catalog.tools(),
                        t -> t.name() + " " + (t.description() == null ? "" : t.description()),
                        intent,
                        limit)
                .stream()
                .map(Scored::item)
                .toList();
    }
}

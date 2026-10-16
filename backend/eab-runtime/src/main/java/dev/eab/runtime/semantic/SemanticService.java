package dev.eab.runtime.semantic;

import dev.eab.runtime.model.PromptTemplate.KnowledgeSnippet;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.SemanticPackSpec;
import dev.eab.spec.model.SemanticPackSpec.Metric;
import dev.eab.spec.model.SemanticPackSpec.SemanticObject;
import dev.eab.spec.model.SemanticPackSpec.SemanticRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义查询工具（主提示词 5.5 节）：只在 Agent 挂载的语义包范围内查询。投影段只写元数据引用，定义由 {@link MetaClient}
 * 实时从元数据服务取；返回结果同时带语义包版本和元数据版本，回答可追溯到具体口径。
 */
public final class SemanticService {

    /** 元数据服务（DemoERP 的 /meta/v1）：按带版本的引用取对象定义，不存在或版本不符时返回空 */
    @FunctionalInterface
    public interface MetaClient {
        Optional<Map<String, Object>> getObject(String ref);
    }

    public static final class SemanticException extends RuntimeException {
        private final String code;

        public SemanticException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 四个语义查询工具的入参 JSON Schema，经工具网关暴露给模型 */
    public static final Map<String, String> TOOL_SCHEMAS = Map.of(
            "semantic.describe_object",
            "{\"type\":\"object\",\"required\":[\"object\"],\"properties\":{\"object\":{\"type\":\"string\","
                    + "\"description\":\"业务对象，例如 erp.pur.Supplier 或 供应商\"}}}",
            "semantic.get_metric",
            "{\"type\":\"object\",\"required\":[\"name\"],\"properties\":{\"name\":{\"type\":\"string\","
                    + "\"description\":\"指标名或显示名，例如 overdue_days、逾期交付率\"}}}",
            "semantic.find_rules",
            "{\"type\":\"object\",\"properties\":{\"object\":{\"type\":\"string\",\"description\":\"业务对象，可选\"},"
                    + "\"keyword\":{\"type\":\"string\",\"description\":\"关键词或规则编号，可选\"}}}",
            "semantic.search",
            "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\"},"
                    + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}}}");

    private final List<SemanticPackAsset> packs;
    private final MetaClient meta;

    public SemanticService(List<SemanticPackAsset> packs, MetaClient meta) {
        this.packs = List.copyOf(packs);
        this.meta = meta;
    }

    static String packRef(SemanticPackAsset pack) {
        return pack.metadata().namespace() + "/" + pack.metadata().name() + "@"
                + pack.metadata().version();
    }

    /** 挂载的语义包里的全部规则，约束守卫按它们的检查条件判断 */
    public List<SemanticRule> rules() {
        return packs.stream().flatMap(p -> orEmpty(p.spec().rules()).stream()).toList();
    }

    /**
     * 带来源的规则。
     *
     * @param pack 规则所在的语义包，namespace/name@version
     * @param tenantLayer 规则来自租户层的语义包扩展（经验回流写入的规则在这里）
     */
    public record SourcedRule(SemanticRule rule, String pack, boolean tenantLayer) {}

    public List<SourcedRule> sourcedRules() {
        List<SourcedRule> result = new ArrayList<>();
        for (var p : packs) {
            boolean tenant = p.metadata().namespace().startsWith("tenant.");
            for (SemanticRule r : orEmpty(p.spec().rules())) result.add(new SourcedRule(r, packRef(p), tenant));
        }
        return result;
    }

    public List<String> packRefs() {
        return packs.stream().map(SemanticService::packRef).toList();
    }

    /** 去掉版本后缀：erp.pur.Supplier@v1 → erp.pur.Supplier */
    static String baseRef(String ref) {
        int at = ref.indexOf('@');
        return at < 0 ? ref : ref.substring(0, at);
    }

    private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");

    /** 中文检索用字符二元组的重合度打分，英文和数字按词；足够演示，不依赖分词库 */
    static Set<String> tokens(String text) {
        String lower = text.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = WORD.matcher(lower);
        while (m.find()) tokens.add(m.group());
        StringBuilder cjk = new StringBuilder();
        for (char c : lower.toCharArray()) if (c >= '㐀' && c <= '鿿') cjk.append(c);
        for (int i = 0; i + 1 < cjk.length(); i++) tokens.add(cjk.substring(i, i + 2));
        if (cjk.length() == 1) tokens.add(cjk.toString());
        return tokens;
    }

    static double score(String query, String text) {
        Set<String> q = tokens(query);
        if (q.isEmpty()) return 0;
        Set<String> t = tokens(text);
        long hits = q.stream().filter(t::contains).count();
        return (double) hits / q.size();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private record Found(SemanticPackAsset pack, SemanticObject object) {}

    private Optional<Found> findObject(String name) {
        String wanted = name.strip().toLowerCase();
        for (var pack : packs) {
            for (var object : orEmpty(pack.spec().objects())) {
                String ref = baseRef(object.ref()).toLowerCase();
                if (ref.equals(wanted)
                        || ref.endsWith("." + wanted)
                        || baseRef(wanted).equals(ref)) {
                    return Optional.of(new Found(pack, object));
                }
            }
        }
        // 按说明模糊匹配，例如“供应商”
        Found best = null;
        double bestScore = 0.5;
        for (var pack : packs) {
            for (var object : orEmpty(pack.spec().objects())) {
                double s = score(name, (object.description() == null ? "" : object.description()) + " " + object.ref());
                if (s > bestScore) {
                    best = new Found(pack, object);
                    bestScore = s;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    public Map<String, Object> describeObject(String name) {
        if (name == null || name.isBlank()) throw new SemanticException("BAD_INPUT", "请给出业务对象名");
        Found found = findObject(name)
                .orElseThrow(() -> new SemanticException(
                        "NOT_FOUND",
                        "挂载的语义包里没有业务对象 " + name + "，可用的对象："
                                + String.join(
                                        "、",
                                        packs.stream()
                                                .flatMap(p -> orEmpty(p.spec().objects()).stream())
                                                .map(o -> baseRef(o.ref()))
                                                .toList())));
        Optional<Map<String, Object>> definition = meta.getObject(found.object().ref());
        Map<String, SemanticPackSpec.Field> notes = new LinkedHashMap<>();
        for (var f : orEmpty(found.object().fields())) notes.put(f.ref(), f);
        List<Map<String, Object>> fields = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metaFields =
                (List<Map<String, Object>>) definition.map(d -> d.get("fields")).orElse(List.of());
        for (var field : metaFields) {
            Map<String, Object> merged = new LinkedHashMap<>(field);
            var note = notes.get(String.valueOf(field.get("name")));
            if (note != null && note.description() != null) merged.put("description", note.description());
            if (note != null && note.enumMeaning() != null) merged.put("enumMeaning", note.enumMeaning());
            fields.add(merged);
        }
        String ref = baseRef(found.object().ref());
        List<SemanticPackSpec.Relationship> relationships = packs.stream()
                .flatMap(p -> orEmpty(p.spec().relationships()).stream())
                .filter(r -> ref.equals(r.from()) || ref.equals(r.to()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ref", ref);
        result.put("label", definition.map(d -> d.get("label")).orElse(ref));
        result.put(
                "description",
                found.object().description() == null ? "" : found.object().description());
        result.put("fields", fields);
        result.put("relationships", relationships);
        result.put(
                "permissionDimension",
                definition.map(d -> d.get("permissionDimension")).orElse(null));
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("semanticPack", packRef(found.pack()));
        provenance.put("metadata", definition.map(d -> d.get("ref")).orElse("元数据服务未返回该对象"));
        result.put("provenance", provenance);
        return result;
    }

    private record MetricHit(SemanticPackAsset pack, Metric metric, double score) {}

    public Map<String, Object> getMetric(String name) {
        String wanted = name == null ? "" : name;
        List<MetricHit> candidates = new ArrayList<>();
        for (var pack : packs) {
            for (var metric : orEmpty(pack.spec().metrics())) {
                candidates.add(new MetricHit(pack, metric, score(wanted, metric.displayName() + " " + metric.name())));
            }
        }
        MetricHit hit = candidates.stream()
                .filter(c -> c.metric().name().equals(wanted)
                        || wanted.equals(c.metric().displayName()))
                .findFirst()
                .or(() -> candidates.stream()
                        .filter(c -> c.score() >= 0.5)
                        .max(Comparator.comparingDouble(MetricHit::score)))
                .orElseThrow(() -> new SemanticException(
                        "NOT_FOUND",
                        "没有指标 " + wanted + "，可用的指标："
                                + String.join(
                                        "、",
                                        candidates.stream()
                                                .map(c -> c.metric().displayName())
                                                .toList())));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", hit.metric().name());
        result.put("displayName", hit.metric().displayName());
        result.put("definition", hit.metric().definition());
        if (hit.metric().unit() != null) result.put("unit", hit.metric().unit());
        result.put("provenance", Map.of("semanticPack", packRef(hit.pack())));
        return result;
    }

    public Map<String, Object> findRules(String object, String keyword) {
        SemanticPackAsset domain = object == null || object.isBlank()
                ? null
                : findObject(object).map(Found::pack).orElse(null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var pack : packs) {
            if (domain != null && pack != domain) continue;
            for (SemanticRule rule : orEmpty(pack.spec().rules())) {
                boolean matches = keyword == null
                        || keyword.isBlank()
                        || rule.id().equals(keyword)
                        || score(keyword, (rule.description() == null ? "" : rule.description()) + " " + rule.remedy())
                                >= 0.3;
                if (!matches) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rule.id());
                if (rule.ref() != null) item.put("ref", rule.ref());
                item.put("severity", rule.severity());
                if (rule.description() != null) item.put("description", rule.description());
                item.put("remedy", rule.remedy());
                item.put("semanticPack", packRef(pack));
                items.add(item);
            }
        }
        return Map.of("items", items, "total", items.size());
    }

    public record SearchHit(String type, String id, String title, String summary, String pack, double score) {}

    public Map<String, Object> search(String query, Integer limit) {
        int max = limit == null ? 8 : limit;
        List<SearchHit> hits = hits(query == null ? "" : query);
        List<SearchHit> top = hits.subList(0, Math.min(max, hits.size()));
        return Map.of("items", top, "total", top.size(), "packs", packRefs());
    }

    private List<SearchHit> hits(String query) {
        List<SearchHit> all = new ArrayList<>();
        for (var pack : packs) {
            String p = packRef(pack);
            var spec = pack.spec();
            for (var o : orEmpty(spec.objects())) {
                String summary = String.join(
                        "；",
                        orEmpty(o.fields()).stream()
                                .map(f -> f.description() == null ? f.ref() : f.description())
                                .toList());
                all.add(new SearchHit(
                        "object",
                        baseRef(o.ref()),
                        o.description() == null ? baseRef(o.ref()) : o.description(),
                        summary,
                        p,
                        0));
            }
            for (var m : orEmpty(spec.metrics()))
                all.add(new SearchHit("metric", m.name(), m.displayName(), m.definition(), p, 0));
            for (var r : orEmpty(spec.rules())) {
                all.add(new SearchHit(
                        "rule", r.id(), r.description() == null ? r.id() : r.description(), r.remedy(), p, 0));
            }
            for (var t : orEmpty(spec.verifiedTasks())) {
                all.add(new SearchHit("verified_task", t.id(), t.question(), t.expectedConclusion(), p, 0));
            }
        }
        List<SearchHit> scored = new ArrayList<>();
        for (SearchHit hit : all) {
            double s = Math.round(score(query, hit.title() + " " + hit.summary() + " " + hit.id()) * 100) / 100.0;
            if (s > 0) scored.add(new SearchHit(hit.type(), hit.id(), hit.title(), hit.summary(), hit.pack(), s));
        }
        scored.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return scored;
    }

    /**
     * 知识检索注入（主提示词 8.2 节）：按话术取最相关的指标口径和规则，注入系统提示词。
     * 阈值较高，宁可少注入，也不把无关口径塞进提示词。
     */
    public List<KnowledgeSnippet> retrieve(String query, int limit) {
        List<KnowledgeSnippet> snippets = new ArrayList<>();
        for (SearchHit hit : hits(query)) {
            if (snippets.size() >= limit) break;
            if (!(hit.type().equals("metric") || hit.type().equals("rule")) || hit.score() < 0.25) continue;
            String text = hit.type().equals("metric")
                    ? "指标 " + hit.title() + "：" + hit.summary()
                    : "规则 " + hit.id() + "（" + hit.title() + "）：" + hit.summary();
            snippets.add(new KnowledgeSnippet(hit.id(), hit.pack(), text));
        }
        return snippets;
    }

    /** 按工具名分发调用 */
    public Map<String, Object> call(String name, Map<String, Object> args) {
        return switch (name) {
            case "semantic.describe_object" -> describeObject(str(args, "object"));
            case "semantic.get_metric" -> getMetric(str(args, "name"));
            case "semantic.find_rules" -> findRules(str(args, "object"), str(args, "keyword"));
            case "semantic.search" ->
                search(str(args, "query"), args.get("limit") instanceof Number n ? n.intValue() : null);
            default -> throw new SemanticException("NOT_FOUND", "没有语义查询工具 " + name);
        };
    }

    private static String str(Map<String, Object> args, String key) {
        return args.get(key) instanceof String s ? s : null;
    }
}

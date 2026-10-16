package dev.eab.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档检索用的 BM25（documents 类工具）：中文按字符二元组切分，英文和数字按词，不依赖分词库。
 * 文档按段落切成片段，返回片段原文和出处，回答可以引用。
 */
public final class Bm25 {

    private Bm25() {}

    static final double K1 = 1.2;
    static final double B = 0.75;

    public record Passage(String source, String title, String text) {}

    public record Hit(Passage passage, double score) {}

    private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");

    static List<String> terms(String text) {
        String lower = text.toLowerCase();
        List<String> terms = new ArrayList<>();
        Matcher m = WORD.matcher(lower);
        while (m.find()) terms.add(m.group());
        StringBuilder cjk = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if (c >= '㐀' && c <= '鿿') {
                cjk.append(c);
            } else if (!cjk.isEmpty()) {
                addBigrams(cjk, terms);
                cjk.setLength(0);
            }
        }
        addBigrams(cjk, terms);
        return terms;
    }

    private static void addBigrams(StringBuilder cjk, List<String> terms) {
        if (cjk.length() == 1) terms.add(cjk.toString());
        for (int i = 0; i + 1 < cjk.length(); i++) terms.add(cjk.substring(i, i + 2));
    }

    /** 按空行切段，段落太短的与下一段合并 */
    public static List<Passage> split(String source, String title, String markdown) {
        List<Passage> passages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String block : markdown.split("\\n\\s*\\n")) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) continue;
            if (!current.isEmpty()) current.append("\n\n");
            current.append(trimmed);
            if (current.length() >= 40) {
                passages.add(new Passage(source, title, current.toString()));
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) passages.add(new Passage(source, title, current.toString()));
        return passages;
    }

    public static List<Hit> search(List<Passage> passages, String query, int limit) {
        List<List<String>> docs = passages.stream().map(p -> terms(p.title() + " " + p.text())).toList();
        double avg = docs.stream().mapToInt(List::size).average().orElse(1);
        Map<String, Integer> df = new HashMap<>();
        for (List<String> doc : docs) {
            for (String term : doc.stream().distinct().toList()) df.merge(term, 1, Integer::sum);
        }
        List<String> q = terms(query).stream().distinct().toList();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < passages.size(); i++) {
            List<String> doc = docs.get(i);
            Map<String, Integer> tf = new HashMap<>();
            for (String term : doc) tf.merge(term, 1, Integer::sum);
            double score = 0;
            for (String term : q) {
                int f = tf.getOrDefault(term, 0);
                if (f == 0) continue;
                int n = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (passages.size() - n + 0.5) / (n + 0.5));
                score += idf * f * (K1 + 1) / (f + K1 * (1 - B + B * doc.size() / avg));
            }
            if (score > 0) hits.add(new Hit(passages.get(i), Math.round(score * 1000) / 1000.0));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.subList(0, Math.min(limit, hits.size()));
    }
}

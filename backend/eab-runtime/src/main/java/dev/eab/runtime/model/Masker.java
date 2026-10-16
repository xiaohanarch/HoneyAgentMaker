package dev.eab.runtime.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏（主提示词 8.2 节）：身份证号、银行账号、手机号在发给模型前替换成 [[MASK_n]]，模型返回后在文本和工具参数里还原。
 * 同一个值在一次运行里始终对应同一个占位符，模型可以照常引用它（例如把占位符作为工具参数），还原后工具收到的是真实值。
 *
 * <p>一次运行共用一个 Masker；映射表可以序列化后随运行状态保存。
 */
public final class Masker {

    public record Entry(String placeholder, String type, String value) {}

    private record Rule(String type, Pattern pattern) {}

    /** 顺序有意义：18 位身份证号先于 16 至 19 位银行账号匹配，11 位手机号最后 */
    private static final List<Rule> RULES = List.of(
            new Rule("id-card", Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])")),
            new Rule(
                    "bank-account",
                    Pattern.compile("(?<![0-9A-Za-z])\\d{4}(?:[ -]?\\d{4}){2,3}(?:[ -]?\\d{1,3})?(?![0-9A-Za-z])")),
            new Rule("mobile", Pattern.compile("(?<![0-9A-Za-z])1[3-9]\\d{9}(?![0-9A-Za-z])")));

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[MASK_\\d+]]");

    private final Map<String, Entry> byValue = new LinkedHashMap<>();
    private final Map<String, Entry> byPlaceholder = new LinkedHashMap<>();

    public static Masker from(List<Entry> entries) {
        Masker masker = new Masker();
        for (Entry entry : entries) masker.register(entry);
        return masker;
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(byPlaceholder.values());
    }

    private void register(Entry entry) {
        byValue.put(entry.value(), entry);
        byPlaceholder.put(entry.placeholder(), entry);
    }

    public record Masked(String text, int count) {}

    public synchronized Masked mask(String text) {
        if (text == null) return new Masked(null, 0);
        int count = 0;
        String result = text;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                count++;
                String value = matcher.group();
                Entry entry = byValue.get(value);
                if (entry == null) {
                    entry = new Entry("[[MASK_" + (byPlaceholder.size() + 1) + "]]", rule.type(), value);
                    register(entry);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(entry.placeholder()));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return new Masked(result, count);
    }

    public synchronized String unmask(String text) {
        if (text == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Entry entry = byPlaceholder.get(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(entry == null ? matcher.group() : entry.value()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 递归还原工具参数、结构化结果里的占位符 */
    public Object unmaskValue(Object value) {
        return switch (value) {
            case String s -> unmask(s);
            case List<?> list -> {
                List<Object> out = new ArrayList<>();
                for (Object item : list) out.add(unmaskValue(item));
                yield out;
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), unmaskValue(v)));
                yield out;
            }
            case null -> null;
            default -> value;
        };
    }
}

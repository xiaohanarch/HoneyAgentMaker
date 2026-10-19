package dev.eab.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 监督层（主提示词 8.2.1 节）：提示词之外的第二道防线，全部是确定性逻辑。写级别、SoD、委托范围等由策略引擎（鉴权点 3）裁决，
 * 这里做策略之外的检查：写级别不超过 Agent 声明的上限、输出引用核对、行为异常检测。
 */
public final class Supervisor {

    private Supervisor() {}

    static final List<String> LEVELS = List.of("L0", "L1", "L2", "L3");

    /** 同一会话在这段时间内写操作超过 WRITE_BURST_LIMIT 次时告警 */
    static final long WRITE_WINDOW_SECONDS = 600;

    static final int WRITE_BURST_LIMIT = 5;

    /** 写操作级别超过 Agent 的 writePolicy.maxLevel 时返回拒绝原因，否则返回 null */
    static String checkWriteLevel(String agent, String maxLevel, String toolName, String writeLevel) {
        if (writeLevel == null || maxLevel == null) return null;
        int tool = LEVELS.indexOf(writeLevel);
        int max = LEVELS.indexOf(maxLevel);
        if (tool < 0 || max < 0 || tool <= max) return null;
        return "工具 " + toolName + " 的写级别 " + writeLevel + " 超过 " + agent + " 声明的上限 " + maxLevel;
    }

    public static boolean isWrite(String writeLevel) {
        return writeLevel != null && !writeLevel.equals("L0");
    }

    /** 单号：PO-2026-0117、ANO-2026-0003、RFQ-2026-0058，以及主数据编号 S-0001、M-0007 */
    static final Pattern DOCUMENT_ID = Pattern.compile("\\b(?:[A-Z]{2,5}-\\d{4}-\\d{3,4}|[A-Z]-\\d{4})\\b");

    /** 金额：四位以上的数字，可带千分位和小数；日期、年份、单号里的数字不算 */
    static final Pattern AMOUNT =
            Pattern.compile("(?<![\\d.,A-Za-z-])(\\d{1,3}(?:,\\d{3})+|\\d{4,})(\\.\\d+)?(?![\\d年月日-])");

    /** 证据里的数字：JSON 序列化把 10 的 7 次方以上的数写成科学计数法（1.83599256E7），按数值比对而不是按文本 */
    static final Pattern NUMBER = Pattern.compile("-?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?");

    public static final String UNVERIFIED = "（未经核实）";

    /**
     * @param text 标注后的回复
     * @param unverified 在本次运行的工具结果里找不到的单号和金额
     */
    public record ReferenceCheck(String text, List<String> unverified, int checked) {}

    /**
     * 输出守卫：结论里引用的单号、金额必须能在本次运行的工具结果（或用户的话）里找到，找不到则在原文后标注“未经核实”。
     * 单号按文本比对，金额按数值比对，证据里 JSON 的科学计数法（1.83599256E7）与回复里的普通写法算同一个数。
     */
    public static ReferenceCheck checkReferences(String text, String evidence) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = DOCUMENT_ID.matcher(text);
        while (m.find()) ids.add(m.group());
        Set<Double> numbers = new HashSet<>();
        Matcher n = NUMBER.matcher(evidence);
        while (n.find()) numbers.add(parse(n.group()));
        List<String> unverified = new ArrayList<>();
        for (String id : ids) if (!evidence.contains(id)) unverified.add(id);
        Matcher a = AMOUNT.matcher(stripDocumentIds(text));
        int amounts = 0;
        Set<String> seen = new LinkedHashSet<>();
        while (a.find()) {
            amounts++;
            String raw = a.group();
            if (looksLikeYear(raw)) continue;
            if (!numbers.contains(parse(raw)) && seen.add(raw)) unverified.add(raw);
        }
        String marked = text;
        for (String ref : unverified) marked = markFirst(marked, ref);
        return new ReferenceCheck(marked, unverified, ids.size() + amounts);
    }

    private static String stripDocumentIds(String text) {
        return DOCUMENT_ID.matcher(text).replaceAll(" ").replaceAll("\\d{4}-\\d{2}-\\d{2}", " ");
    }

    /** 1900 到 2099 之间的四位整数按年份处理，不当作金额 */
    private static boolean looksLikeYear(String raw) {
        if (raw.length() != 4 || raw.contains(",") || raw.contains(".")) return false;
        int value = Integer.parseInt(raw);
        return value >= 1900 && value <= 2099;
    }

    private static double parse(String number) {
        return Double.parseDouble(number.replace(",", ""));
    }

    private static String markFirst(String text, String ref) {
        int at = text.indexOf(ref);
        if (at < 0) return text;
        int end = at + ref.length();
        return text.substring(0, end) + UNVERIFIED + text.substring(end);
    }

    /** 数据范围：BO API 响应里的可见组织（visibleScope），用于发现“访问范围突然扩大” */
    @SuppressWarnings("unchecked")
    static List<String> visibleScope(Object body) {
        if (body instanceof Map<?, ?> map && map.get("visibleScope") instanceof List<?> list) {
            return (List<String>) list;
        }
        return null;
    }

    /** 本次运行里此前看到的可见范围都不包含新的组织时，说明范围扩大了 */
    static List<String> expansion(Set<String> seenBefore, List<String> current) {
        if (seenBefore.isEmpty() || current == null) return List.of();
        List<String> added = new ArrayList<>();
        for (String org : current) if (!seenBefore.contains(org)) added.add(org);
        return added;
    }
}

package dev.eab.platform.identity;

import java.util.Map;

/**
 * 公网演示访问的按身份口令表，来自 EAB_DEMO_PASSCODES（格式 user:pass,...，支持 * 作为缺省条目）。
 * 未设置时是空表，选人即登录不受影响；口令只放在仓库外的 access.env，不进代码与数据快照。
 * 条目格式不对时解析直接抛错，让配置错误在启动时暴露，而不是静默放开或锁死登录。
 */
public record DemoPasscodes(Map<String, String> entries) {

    public static final String WILDCARD = "*";

    public DemoPasscodes {
        entries = Map.copyOf(entries);
    }

    public static DemoPasscodes empty() {
        return new DemoPasscodes(Map.of());
    }

    /** 是否启用了口令门禁 */
    public boolean required() {
        return !entries.isEmpty();
    }

    /** 精确匹配优先，缺省回退到 * 条目；都没有时返回 null，表示该身份不允许公网登录 */
    public String lookup(String userId) {
        String exact = entries.get(userId);
        return exact != null ? exact : entries.get(WILDCARD);
    }

    /** 解析 user:pass,...；空白条目忽略，缺冒号、空用户名或空口令视为配置错误 */
    public static DemoPasscodes parse(String config) {
        if (config == null || config.isBlank()) return empty();
        Map<String, String> parsed = new java.util.LinkedHashMap<>();
        for (String entry : config.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) continue;
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalArgumentException("EAB_DEMO_PASSCODES 条目格式应为 user:pass，实际是「" + trimmed + "」");
            }
            parsed.put(trimmed.substring(0, separator), trimmed.substring(separator + 1));
        }
        return new DemoPasscodes(parsed);
    }
}

package dev.eab.erp.db;

import dev.eab.common.JsNumbers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 查询结果转成以驼峰命名为键的行（与 TS 版 drizzle 返回的字段名一致），按列顺序排列；
 * 整数值的 REAL 输出成整数，JSON 里写成 800 而不是 800.0。
 */
public final class Rows {

    private Rows() {}

    public static String camel(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    public static String snake(String field) {
        StringBuilder out = new StringBuilder();
        for (char c : field.toCharArray()) {
            if (Character.isUpperCase(c)) out.append('_').append(Character.toLowerCase(c));
            else out.append(c);
        }
        return out.toString();
    }

    private static Map<String, Object> convert(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> out.put(camel(k), JsNumbers.normalize(v)));
        return out;
    }

    public static List<Map<String, Object>> list(JdbcClient jdbc, String sql, Object... params) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql(sql).params(params).query().listOfRows()) rows.add(convert(row));
        return rows;
    }

    public static Optional<Map<String, Object>> one(JdbcClient jdbc, String sql, Object... params) {
        List<Map<String, Object>> rows = list(jdbc, sql, params);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 插入一行，键是驼峰字段名 */
    public static void insert(JdbcClient jdbc, String table, Map<String, Object> row) {
        List<String> columns = row.keySet().stream().map(Rows::snake).toList();
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";
        jdbc.sql(sql).params(new ArrayList<>(row.values())).update();
    }

    /** IN 子句的占位符：(?, ?, ?)；列表为空时返回不会匹配任何行的条件 */
    public static String in(int size) {
        if (size == 0) return "(NULL)";
        return "(" + String.join(", ", java.util.Collections.nCopies(size, "?")) + ")";
    }

    public static Object[] params(Object first, List<?> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all.toArray();
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    public static double num(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }
}

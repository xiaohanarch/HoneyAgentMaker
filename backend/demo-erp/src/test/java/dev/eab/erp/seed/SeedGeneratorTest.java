package dev.eab.erp.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 种子数据与 TS 版逐条一致：seed-golden.json.gz 是删除 TS 版之前用它生成的两个租户的数据（2026-10-15T08:00:00Z）。
 * 剧本、验收脚本都依赖这些数据（例如 PO-2026-0117 逾期 120 天），不一致会让演示断掉。
 */
class SeedGeneratorTest {

    static final JsonNode GOLDEN = load();

    static JsonNode load() {
        try (InputStream in =
                new GZIPInputStream(SeedGeneratorTest.class.getResourceAsStream("/seed-golden.json.gz"))) {
            return JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean same(Object actual, JsonNode expected) {
        if (expected == null || expected.isNull()) return actual == null;
        if (expected.isNumber()) return actual instanceof Number n && n.doubleValue() == expected.asDouble();
        if (expected.isString()) return expected.asString().equals(actual);
        if (expected.isBoolean()) return Boolean.valueOf(expected.asBoolean()).equals(actual);
        return false;
    }

    @ParameterizedTest(name = "{0} 的种子数据与 TS 版逐条一致")
    @ValueSource(strings = {"t001", "t002"})
    @DisplayName("种子数据与 TS 版逐条一致")
    void matchesGolden(String tenantId) {
        var data = SeedGenerator.generate(
                tenantId, Instant.parse(GOLDEN.get("today").asString()));
        List<String> mismatches = new ArrayList<>();
        for (String table : SeedGenerator.TABLES) {
            JsonNode expectedRows = GOLDEN.get(tenantId).get(table);
            List<Map<String, Object>> actualRows = data.get(table);
            if (expectedRows.size() != actualRows.size()) {
                mismatches.add(table + " 行数 " + actualRows.size() + "，应为 " + expectedRows.size());
                continue;
            }
            for (int i = 0; i < actualRows.size() && mismatches.size() < 20; i++) {
                JsonNode expected = expectedRows.get(i);
                Map<String, Object> actual = actualRows.get(i);
                for (Iterator<String> it = expected.propertyNames().iterator(); it.hasNext(); ) {
                    String field = it.next();
                    if (!same(actual.get(field), expected.get(field))) {
                        mismatches.add(table + "[" + i + "]." + field + " = " + actual.get(field) + "，应为 "
                                + expected.get(field));
                    }
                }
                if (actual.size() != expected.size()) mismatches.add(table + "[" + i + "] 字段数不同：" + actual.keySet());
            }
        }
        assertThat(mismatches).isEmpty();
    }
}

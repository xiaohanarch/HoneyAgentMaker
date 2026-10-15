package dev.eab.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonTest {

    static final String MIGRATIONS = "classpath:db/common-test";

    @Nested
    @DisplayName("JS 数字格式")
    class Numbers {

        @Test
        @DisplayName("整数不带小数点，小数与 JS 的 String(n) 一致")
        void format() {
            assertThat(JsNumbers.format(800.0)).isEqualTo("800");
            assertThat(JsNumbers.format(46.5)).isEqualTo("46.5");
            assertThat(JsNumbers.format(0.1 + 0.2)).isEqualTo("0.30000000000000004");
            assertThat(JsNumbers.format(-3.25)).isEqualTo("-3.25");
            assertThat(JsNumbers.format(1e21)).isEqualTo("1e+21");
            assertThat(JsNumbers.format(1.5e-7)).isEqualTo("1.5e-7");
            assertThat(JsNumbers.format(Double.NaN)).isEqualTo("NaN");
            assertThat(JsNumbers.format(Double.NEGATIVE_INFINITY)).isEqualTo("-Infinity");
        }

        @Test
        @DisplayName("整数值的 REAL 归一成 long，其余原样返回")
        void normalize() {
            assertThat(JsNumbers.normalize(800.0)).isEqualTo(800L);
            assertThat(JsNumbers.normalize(7)).isEqualTo(7L);
            assertThat(JsNumbers.normalize(1.5)).isEqualTo(1.5);
            assertThat(JsNumbers.normalize("x")).isEqualTo("x");
        }
    }

    @Test
    @DisplayName("ISO 时间为 UTC 毫秒精度，日期差按自然日计算")
    void isoTime() {
        Instant at = Instant.parse("2026-10-15T08:00:00Z");
        assertThat(IsoTime.format(at)).isEqualTo("2026-10-15T08:00:00.000Z");
        assertThat(IsoTime.date(at)).isEqualTo("2026-10-15");
        assertThat(IsoTime.daysBetween("2026-06-17", "2026-10-15")).isEqualTo(120);
    }

    @Nested
    @DisplayName("SQLite 数据库")
    class Database {

        @Test
        @DisplayName("执行迁移，事务里抛异常时回滚")
        void migrateAndRollback(@TempDir Path dir) {
            try (SqliteDatabase db = SqliteDatabase.open(dir.resolve("a.db").toString(), "test", MIGRATIONS)) {
                db.jdbc().sql("INSERT INTO items (id, name) VALUES (1, 'a')").update();
                try {
                    db.inTransaction(() -> {
                        db.jdbc()
                                .sql("INSERT INTO items (id, name) VALUES (2, 'b')")
                                .update();
                        throw new IllegalStateException("回滚");
                    });
                } catch (IllegalStateException expected) {
                    // 预期
                }
                assertThat(db.jdbc()
                                .sql("SELECT COUNT(*) FROM items")
                                .query(Long.class)
                                .single())
                        .isEqualTo(1);
            }
            // 再次打开同一个库：已有迁移记录，不归档
            try (SqliteDatabase db = SqliteDatabase.open(dir.resolve("a.db").toString(), "test", MIGRATIONS)) {
                assertThat(db.jdbc()
                                .sql("SELECT COUNT(*) FROM items")
                                .query(Long.class)
                                .single())
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("迁移到 Java 之前建的库被改名归档，再建新库")
        void archivesForeignDatabase(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("platform.db");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                    var statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE legacy (id integer)");
            }
            try (SqliteDatabase db = SqliteDatabase.open(file.toString(), "test", MIGRATIONS)) {
                assertThat(db.jdbc()
                                .sql("SELECT COUNT(*) FROM items")
                                .query(Long.class)
                                .single())
                        .isZero();
            }
            try (Stream<Path> files = Files.list(dir)) {
                assertThat(files.map(p -> p.getFileName().toString()))
                        .anyMatch(name -> name.startsWith("platform.db.pre-flyway-"));
            }
        }

        @Test
        @DisplayName("内存库在整个生命周期里保持同一个连接")
        void memory() {
            try (SqliteDatabase db = SqliteDatabase.open(":memory:", "test", MIGRATIONS)) {
                db.jdbc().sql("INSERT INTO items (id, name) VALUES (1, 'a')").update();
                assertThat(db.jdbc()
                                .sql("SELECT name FROM items")
                                .query(String.class)
                                .single())
                        .isEqualTo("a");
            }
        }
    }
}

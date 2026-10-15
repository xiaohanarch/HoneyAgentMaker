package dev.eab.platform.db;

import dev.eab.common.SqliteDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 平台数据库：SQLite，迁移在 db/migration。path 为 ":memory:" 时用于测试 */
public final class PlatformDb implements AutoCloseable {

    private final SqliteDatabase database;

    private PlatformDb(SqliteDatabase database) {
        this.database = database;
    }

    public static PlatformDb open(String path) {
        return new PlatformDb(SqliteDatabase.open(path, "platform-db", "classpath:db/migration"));
    }

    public JdbcClient jdbc() {
        return database.jdbc();
    }

    public void inTransaction(Runnable work) {
        database.inTransaction(work);
    }

    @Override
    public void close() {
        database.close();
    }
}

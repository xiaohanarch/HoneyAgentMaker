package dev.eab.platform.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 平台数据库：SQLite，迁移由 Flyway 执行（db/migration）。path 为 ":memory:" 时用于测试。
 * 数据访问只经过仓储接口，换 PostgreSQL 时只需替换仓储实现。
 *
 * <p>SQLite 同一时刻只允许一个写入者，连接池固定为一个连接：请求在虚拟线程上排队，避免 SQLITE_BUSY；
 * 内存库也因此在整个生命周期里保持同一个连接，数据不会丢。
 */
public final class PlatformDb implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;

    private PlatformDb(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
    }

    public static PlatformDb open(String path) {
        if (!path.equals(":memory:")) {
            try {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("无法创建数据目录", e);
            }
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + (path.equals(":memory:") ? ":memory:" : path));
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setMaxLifetime(0);
        config.setIdleTimeout(0);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        config.setPoolName("platform-db");
        HikariDataSource dataSource = new HikariDataSource(config);
        if (!path.equals(":memory:"))
            JdbcClient.create(dataSource)
                    .sql("PRAGMA journal_mode = WAL")
                    .query()
                    .singleRow();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new PlatformDb(dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

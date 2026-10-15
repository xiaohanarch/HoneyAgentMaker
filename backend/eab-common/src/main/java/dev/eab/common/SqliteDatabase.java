package dev.eab.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SQLite 数据库，迁移由 Flyway 执行，各应用的迁移脚本放在各自的 classpath 目录里，避免同一进程里互相干扰。path 为 ":memory:" 时用于测试。
 * 数据访问只经过仓储接口，换 PostgreSQL 时只需替换仓储实现。
 *
 * <p>SQLite 同一时刻只允许一个写入者，连接池固定为一个连接：请求在虚拟线程上排队，避免 SQLITE_BUSY；
 * 内存库也因此在整个生命周期里保持同一个连接，数据不会丢。
 */
public final class SqliteDatabase implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    private SqliteDatabase(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * @param migrations Flyway 迁移脚本位置，例如 classpath:db/migration
     */
    public static SqliteDatabase open(String path, String poolName, String migrations) {
        boolean memory = path.equals(":memory:");
        if (!memory) {
            try {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("无法创建数据目录", e);
            }
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setMaxLifetime(0);
        config.setIdleTimeout(0);
        config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        config.setPoolName(poolName);
        HikariDataSource dataSource = new HikariDataSource(config);
        if (!memory)
            JdbcClient.create(dataSource)
                    .sql("PRAGMA journal_mode = WAL")
                    .query()
                    .singleRow();
        Flyway.configure().dataSource(dataSource).locations(migrations).load().migrate();
        return new SqliteDatabase(dataSource);
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** 在一个事务里执行，抛异常时回滚 */
    public void inTransaction(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

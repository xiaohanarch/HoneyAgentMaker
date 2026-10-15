package dev.eab.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(SqliteDatabase.class);

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
                archiveIfForeign(Path.of(path));
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

    /**
     * 迁移到 Java 之前由 TS 版（Drizzle）建的库没有 Flyway 的迁移记录，Flyway 会拒绝在上面执行迁移。
     * 这样的库整体改名归档（连同 -wal、-shm），再建新库；演示数据可以重新生成，归档文件保留以便追查。
     */
    static void archiveIfForeign(Path file) throws IOException {
        if (!Files.exists(file)) return;
        boolean foreign;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet tables = statement.executeQuery(
                        "SELECT SUM(name = 'flyway_schema_history'), COUNT(*) FROM sqlite_master WHERE type = 'table'")) {
            foreign = tables.next() && tables.getLong(2) > 0 && tables.getLong(1) == 0;
        } catch (SQLException e) {
            throw new IOException("无法读取数据库 " + file + "：" + e.getMessage(), e);
        }
        if (!foreign) return;
        String suffix = ".pre-flyway-"
                + Instant.now().truncatedTo(ChronoUnit.MILLIS).toString().replaceAll("[:.]", "-");
        for (String extra : new String[] {"", "-wal", "-shm"}) {
            Path source = Path.of(file + extra);
            if (Files.exists(source)) Files.move(source, Path.of(file + extra + suffix));
        }
        LOG.warn("数据库 {} 不是由 Flyway 建立的（迁移到 Java 之前的数据），已改名为 {} 并重新建库", file, file.getFileName() + suffix);
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

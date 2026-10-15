package dev.eab.erp.db;

import dev.eab.common.SqliteDatabase;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * DemoERP 的业务数据（主提示词 6.1 节）：采购、库存、财务三个域，外加交期变更单、跟进任务、幂等键与访问日志。
 * 每张业务表都带 tenant_id，所有查询都按租户隔离；日期统一存 YYYY-MM-DD 字符串。迁移脚本在 db/erp。path 为 ":memory:" 时用于测试。
 */
public final class ErpDb implements AutoCloseable {

    private final SqliteDatabase database;

    private ErpDb(SqliteDatabase database) {
        this.database = database;
    }

    public static ErpDb open(String path) {
        return new ErpDb(SqliteDatabase.open(path, "demo-erp-db", "classpath:db/erp"));
    }

    public JdbcClient jdbc() {
        return database.jdbc();
    }

    public void inTransaction(Runnable work) {
        database.inTransaction(work);
    }

    /** 在一个事务里执行并返回结果，抛异常时回滚 */
    public <T> T transaction(Supplier<T> work) {
        AtomicReference<T> result = new AtomicReference<>();
        database.inTransaction(() -> result.set(work.get()));
        return result.get();
    }

    @Override
    public void close() {
        database.close();
    }
}

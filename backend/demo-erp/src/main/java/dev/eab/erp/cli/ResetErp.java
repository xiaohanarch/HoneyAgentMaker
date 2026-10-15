package dev.eab.erp.cli;

import dev.eab.erp.ErpProperties;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.seed.Seeder;
import java.nio.file.Path;
import java.time.Instant;

/** pnpm seed:reset 的 DemoERP 部分：按今天重新生成业务数据，清空运行期数据。服务运行中也可以执行 */
public final class ResetErp {

    private ResetErp() {}

    public static void main(String[] args) {
        var root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) logback.setLevel(ch.qos.logback.classic.Level.WARN);
        String env = System.getenv("EAB_DATA_DIR");
        Path dataDir = env == null || env.isBlank()
                ? ErpProperties.repoRoot().resolve("data")
                : Path.of(env).toAbsolutePath();
        try (ErpDb db = ErpDb.open(dataDir.resolve("erp.db").toString())) {
            Seeder.seed(db, Instant.now());
        }
        System.out.println("DemoERP 演示数据已重置：" + dataDir);
    }
}

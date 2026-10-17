package dev.eab.erp.cli;

import dev.eab.erp.ErpJson;
import dev.eab.erp.ErpProperties;
import dev.eab.erp.seed.SeedGenerator;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * pnpm seed:golden：重新生成种子数据的黄金样本（backend/demo-erp/src/test/resources/seed-golden.json.gz）。
 * 改了 SeedGenerator 的生成逻辑后运行一次；基准时间固定不变，保证两次生成之间只有被改的表不同。
 */
public final class WriteSeedGolden {

    /** 黄金样本的基准时间；换一个值会让所有日期字段整体平移 */
    static final Instant TODAY = Instant.parse("2026-10-15T08:00:00Z");

    private WriteSeedGolden() {}

    public static void main(String[] args) throws Exception {
        var root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (root instanceof ch.qos.logback.classic.Logger logback) logback.setLevel(ch.qos.logback.classic.Level.WARN);
        Map<String, Object> golden = new LinkedHashMap<>();
        golden.put("today", TODAY.toString());
        for (var tenant : dev.eab.directory.DemoDirectory.TENANTS) {
            golden.put(tenant.id(), SeedGenerator.generate(tenant.id(), TODAY));
        }
        Path out = ErpProperties.repoRoot().resolve("backend/demo-erp/src/test/resources/seed-golden.json.gz");
        Files.createDirectories(out.getParent());
        try (OutputStream file = Files.newOutputStream(out);
                GZIPOutputStream zip = new GZIPOutputStream(file)) {
            ErpJson.MAPPER.writeValue(zip, golden);
        }
        System.out.println("黄金样本已写入：" + out + "（" + Files.size(out) + " 字节），基准时间 " + TODAY);
    }
}

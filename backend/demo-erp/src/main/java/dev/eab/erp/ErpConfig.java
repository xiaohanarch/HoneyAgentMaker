package dev.eab.erp;

import dev.eab.erp.db.ErpDb;
import dev.eab.erp.link.HttpPlatformLink;
import dev.eab.erp.seed.Seeder;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 构造 ErpServices，并在启动时为空库生成演示数据 */
@Configuration
@EnableConfigurationProperties(ErpProperties.class)
public class ErpConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ErpConfig.class);

    @Bean(destroyMethod = "")
    public ErpServices erpServices(ErpProperties props) {
        String secret = props.internalSecret();
        if (ErpProperties.blank(secret)) {
            secret = UUID.randomUUID().toString();
            LOG.warn("没有设置 EAB_INTERNAL_SECRET，事件推送和委托核实会失败，受托调用会被拒绝");
        }
        ErpDb db = ErpDb.open(props.resolvedDbPath());
        Runtime.getRuntime().addShutdownHook(new Thread(db::close));
        var link = new HttpPlatformLink(props.resolvedPlatformUrl(), secret, props.delegationCacheMillis());
        return ErpServices.create(db, link, Clock.systemUTC(), secret);
    }

    /** 响应保留 null 字段，与 TS 版一致 */
    @Bean
    public JsonMapper jsonMapper() {
        return ErpJson.MAPPER;
    }

    @Bean
    public ApplicationRunner seedErp(ErpProperties props, ErpServices services) {
        return args -> {
            if (props.seedData() && !Seeder.isSeeded(services.db())) {
                Seeder.seed(services.db(), services.clock().instant());
                LOG.info("DemoERP 演示数据已生成");
            }
        };
    }
}

package dev.eab.platform;

import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.audit.JdbcAuditStore;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.http.Principals;
import dev.eab.platform.identity.IdentityKeys;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.DefaultPublishGate;
import dev.eab.platform.registry.JdbcAssetRepository;
import dev.eab.platform.registry.RegistryService;
import dev.eab.platform.registry.Signer;
import java.time.Clock;

/**
 * 平台主服务的依赖装配。Spring 配置和测试都通过它创建同一套对象，测试用 ":memory:" 数据库与临时密钥。
 */
public record PlatformServices(
        PlatformDb db,
        Clock clock,
        AuditStore audit,
        RegistryService registry,
        TokenService tokens,
        Principals principals) {

    /**
     * @param publishGate 替换发布门禁；只测注册中心机制的单元测试可以传入空门禁，null 表示默认门禁
     */
    public record Options(
            String dbPath,
            Signer signer,
            IdentityKeys identityKeys,
            RegistryService.PublishGate publishGate,
            Clock clock) {}

    public static PlatformServices create(Options options) {
        Clock clock = options.clock() == null ? Clock.systemUTC() : options.clock();
        PlatformDb db = PlatformDb.open(options.dbPath());
        AuditStore audit = new JdbcAuditStore(db.jdbc(), clock);
        RegistryService registry = new RegistryService(
                new JdbcAssetRepository(db.jdbc()),
                audit,
                options.signer(),
                options.publishGate() == null ? new DefaultPublishGate() : options.publishGate(),
                clock);
        TokenService tokens = new TokenService(options.identityKeys(), clock);
        return new PlatformServices(db, clock, audit, registry, tokens, new Principals(tokens));
    }
}

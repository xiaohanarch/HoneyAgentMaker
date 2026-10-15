package dev.eab.platform;

import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.audit.JdbcAuditStore;
import dev.eab.platform.db.PlatformDb;
import dev.eab.platform.events.EventStore;
import dev.eab.platform.http.Principals;
import dev.eab.platform.identity.ClientRegistry;
import dev.eab.platform.identity.DelegationStore;
import dev.eab.platform.identity.IdentityKeys;
import dev.eab.platform.identity.IdentityService;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.policy.TenantSettingsStore;
import dev.eab.platform.registry.DefaultPublishGate;
import dev.eab.platform.registry.JdbcAssetRepository;
import dev.eab.platform.registry.RegistryService;
import dev.eab.platform.registry.Signer;
import dev.eab.policy.PolicyEngine;
import dev.eab.policy.PolicyEngine.PolicyRuleSet;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 平台主服务的依赖装配。Spring 配置和测试都通过它创建同一套对象，测试用 ":memory:" 数据库与临时密钥。
 *
 * @param internalSecret 平台与 DemoERP 之间的内部调用密钥（事件推送签名、委托核实），由 pnpm demo 每次随机生成
 */
public record PlatformServices(
        PlatformDb db,
        Clock clock,
        AuditStore audit,
        RegistryService registry,
        TokenService tokens,
        Principals principals,
        IdentityService identity,
        DelegationStore delegations,
        ClientRegistry clients,
        TenantSettingsStore tenantSettings,
        EventStore events,
        PolicyRuleSet policyRules,
        String internalSecret) {

    /**
     * @param publishGate 替换发布门禁；只测注册中心机制的单元测试可以传入空门禁，null 表示默认门禁
     * @param clients 集成账号，null 表示临时生成
     * @param internalSecret null 表示随机生成（DemoERP 无法推送事件和核实委托）
     */
    public record Options(
            String dbPath,
            Signer signer,
            IdentityKeys identityKeys,
            RegistryService.PublishGate publishGate,
            Clock clock,
            ClientRegistry clients,
            String internalSecret) {

        public Options(String dbPath, Signer signer, IdentityKeys identityKeys, RegistryService.PublishGate publishGate, Clock clock) {
            this(dbPath, signer, identityKeys, publishGate, clock, null, null);
        }
    }

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
        DelegationStore delegations = new DelegationStore(db.jdbc(), clock);
        ClientRegistry clients = options.clients() == null ? ClientRegistry.ephemeral() : options.clients();
        return new PlatformServices(
                db,
                clock,
                audit,
                registry,
                tokens,
                new Principals(tokens),
                new IdentityService(tokens, delegations, clients, audit),
                delegations,
                clients,
                new TenantSettingsStore(db.jdbc(), clock),
                new EventStore(db.jdbc(), clock),
                PolicyEngine.DEFAULT_RULES,
                options.internalSecret() == null ? UUID.randomUUID().toString() : options.internalSecret());
    }

    /** 演示用的预置委托编号，故事 D 中由赵管理员撤销 */
    public static final String DEMO_DELEGATION_ID = "DLG-0001";

    /**
     * 预置演示数据：孙库管把库存只读操作委托给库存经营分析 Agent，用于受托自主运行（主提示词 7.2 节）。
     * 已存在时不重复创建。
     */
    public void ensureDemoDefaults() {
        if (delegations.get(DEMO_DELEGATION_ID).isPresent()) return;
        var now = clock.instant();
        delegations.create(
                new DelegationStore.NewDelegation(
                        "t001",
                        "sunkg",
                        "vendor.scm/inventory-risk-analyst",
                        List.of("inventory_balance_list", "material_list", "stock_movement_list", "purchase_order_list", "delivery_list"),
                        IsoTime.format(now),
                        IsoTime.format(now.plus(Duration.ofDays(90))),
                        "交付逾期、库存低于安全线时，库存经营分析 Agent 以孙库管的委托自主分析库存影响",
                        "zhaoadmin"),
                DEMO_DELEGATION_ID);
    }
}

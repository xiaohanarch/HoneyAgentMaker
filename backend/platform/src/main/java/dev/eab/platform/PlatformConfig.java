package dev.eab.platform;

import dev.eab.common.IsoTime;
import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.runtime.RuntimeDeps;
import dev.eab.runtime.model.GatewayConfig;
import dev.eab.runtime.model.ModelGateway;
import dev.eab.runtime.model.ModelProviders;
import dev.eab.runtime.model.Scripts;
import dev.eab.platform.http.Principals;
import dev.eab.platform.identity.ClientRegistry;
import dev.eab.platform.identity.IdentityKeys;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.AssetSeeder;
import dev.eab.platform.registry.Ed25519Signer;
import dev.eab.platform.registry.RegistryService;
import java.net.InetAddress;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把 PlatformServices 里的对象注册成 Spring Bean，并在启动时灌入平台规则和标品资产 */
@Configuration
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformConfig.class);

    @Bean(destroyMethod = "")
    public PlatformServices platformServices(
            PlatformProperties props, @Value("${server.address:127.0.0.1}") String host) throws Exception {
        // 演示模式开放选人登录和令牌交换端点，只允许监听本机回环地址
        if (props.demoMode() && !InetAddress.getByName(host).isLoopbackAddress()) {
            throw new IllegalStateException("演示模式只能监听本机回环地址，当前 EAB_HOST=" + host + "；请改用 SSH 隧道访问");
        }
        var keysDir = props.resolvedDataDir().resolve("keys");
        PlatformServices services = PlatformServices.create(new PlatformServices.Options(
                props.resolvedDbPath(),
                props.ephemeralKeys() ? Ed25519Signer.ephemeral() : Ed25519Signer.fromDirectory(keysDir),
                props.ephemeralKeys() ? IdentityKeys.ephemeral() : IdentityKeys.fromDirectory(keysDir),
                null,
                null,
                props.ephemeralKeys() ? ClientRegistry.ephemeral() : ClientRegistry.fromDirectory(keysDir),
                props.internalSecret() == null || props.internalSecret().isBlank() ? null : props.internalSecret()));
        if (props.internalSecret() == null || props.internalSecret().isBlank()) {
            LOG.warn("没有设置 EAB_INTERNAL_SECRET，已随机生成；DemoERP 无法推送事件和核实委托，受托调用会被拒绝");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(services.db()::close));
        return services;
    }

    @Bean
    public DemoMode demoMode(PlatformProperties props) {
        return new DemoMode(props.demoMode());
    }

    @Bean
    public RegistryService registryService(PlatformServices services) {
        return services.registry();
    }

    @Bean
    public AuditStore auditStore(PlatformServices services) {
        return services.audit();
    }

    @Bean
    public TokenService tokenService(PlatformServices services) {
        return services.tokens();
    }

    @Bean
    public Principals principals(PlatformServices services) {
        return services.principals();
    }

    /**
     * 运行服务：模型网关按环境变量选择提供方（默认脚本化模型，读 demo/scripts 下的剧本）；选了真实提供方却缺少 Key 时启动即失败，
     * 不会静默退回脚本化模型。
     */
    @Bean
    public RuntimeService runtimeService(PlatformServices services, PlatformProperties props) {
        GatewayConfig config = GatewayConfig.fromEnv(System.getenv());
        var scripts = Scripts.loadDirectory(props.resolvedScriptsDir());
        var model = ModelProviders.create(config, scripts, () -> IsoTime.date(services.clock().instant()));
        LOG.info("模型网关：{}，剧本 {} 条", config, scripts.size());
        return new RuntimeService(
                services,
                new RuntimeService.Settings(
                        props.demoErpUrl(),
                        new ModelGateway(model, config),
                        props.otlpEndpoint(),
                        props.resolvedConnections(),
                        RuntimeDeps.DEFAULT_MAX_TURNS));
    }

    /** 启动时把平台规则和标品资产签名入库；同版本同内容时跳过 */
    @Bean
    public ApplicationRunner seedAssets(PlatformProperties props, RegistryService registry, PlatformServices services) {
        return args -> {
            services.ensureDemoDefaults();
            if (!props.seedAssets()) return;
            var report = AssetSeeder.seed(registry, props.resolvedAssetsDir(), List.of("platform", "vendor"));
            LOG.info("平台与标品资产已签名入库：{} 个", report.published().size());
            report.failed().forEach(f -> LOG.error("资产入库失败 {}：{}", f.file(), f.message()));
        };
    }
}

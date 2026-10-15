package dev.eab.platform;

import dev.eab.directory.DemoDirectory;
import dev.eab.platform.audit.AuditController;
import dev.eab.platform.http.ApiExceptionHandler;
import dev.eab.platform.identity.IdentityKeys;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.Ed25519Signer;
import dev.eab.platform.registry.RegistryController;
import dev.eab.platform.registry.RegistryService;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import java.util.Map;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 测试共用（对应 TS 版 test-support.ts）：内存数据库与临时密钥的平台服务、按身份签发用户令牌、
 * 不启动 Spring 容器的 MockMvc（相当于 TS 版的 app.inject）。
 */
public final class TestPlatform {

    private TestPlatform() {}

    public static final IdentityKeys IDENTITY_KEYS = IdentityKeys.ephemeral();

    /** 只测注册中心机制时用的空门禁 */
    public static final RegistryService.PublishGate NO_GATE = (asset, publisher, registry) -> java.util.List.of();

    public static PlatformServices services() {
        return services(null);
    }

    public static final String INTERNAL_SECRET = "test-internal-secret";

    public static PlatformServices services(RegistryService.PublishGate gate) {
        return PlatformServices.create(new PlatformServices.Options(
                ":memory:", Ed25519Signer.ephemeral(), IDENTITY_KEYS, gate, null, null, INTERNAL_SECRET));
    }

    public static MockMvc mvc(PlatformServices services) {
        return mvc(services, false);
    }

    /** demoMode 为 true 时开放选人登录等演示接口（相当于 TS 版 buildApp({ demoMode: true })） */
    public static MockMvc mvc(PlatformServices services, boolean demoMode) {
        return MockMvcBuilders.standaloneSetup(
                        new RegistryController(services.registry(), services.principals()),
                        new AuditController(services.audit(), services.principals()),
                        new dev.eab.platform.identity.IdentityController(services, new DemoMode(demoMode)),
                        new dev.eab.platform.policy.PolicyController(services),
                        new dev.eab.platform.events.EventController(services))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter(Json.MAPPER))
                .build();
    }

    /** P1 测试里的查看者写法对应的演示用户 */
    public static final Map<String, String> VIEWER_USERS = Map.of(
            "tenant:t001", "chengong",
            "tenant:t002", "zhouting",
            "vendor", "vendordev",
            "platform", "platformops");

    /** 为某个身份签发用户令牌，返回 Authorization 头的值 */
    public static String bearer(PlatformServices services, String who) {
        String userId = VIEWER_USERS.getOrDefault(who, who);
        var user = DemoDirectory.findUser(userId).orElseThrow(() -> new IllegalArgumentException("没有用户 " + who));
        return "Bearer "
                + services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles()));
    }

    public static void publish(PlatformServices services, Viewer viewer, Asset asset) {
        var result = services.registry().publishAsset(viewer, asset, yamlOf(asset));
        if (!result.ok()) {
            throw new IllegalStateException(
                    result.error().message() + " " + result.error().issues());
        }
    }

    public static String yamlOf(Asset asset) {
        return Json.YAML.writeValueAsString(Json.toTree(asset));
    }
}

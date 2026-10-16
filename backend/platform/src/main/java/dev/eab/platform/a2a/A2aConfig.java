package dev.eab.platform.a2a;

import dev.eab.a2a.A2aOutbound;
import dev.eab.platform.PlatformProperties;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.assist.AssistService;
import dev.eab.platform.runtime.RuntimeService;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * A2A 双向互通的装配（ADR 0012）：出站网关接到运行服务上，入站服务把已发布且允许外部调用的 Agent、岗位助手发布成 A2A Server。
 *
 * <p>平台对外地址取 eab.public-url（环境变量 EAB_PUBLIC_URL），没配置时用本机回环地址加实际监听端口，供远程 Agent 推送回调。
 */
@Configuration
public class A2aConfig {

    @Bean
    public A2aTaskStore a2aTaskStore(PlatformServices services) {
        return new A2aTaskStore(services.db().jdbc(), services.clock());
    }

    @Bean
    public RemoteAgentGateway remoteAgentGateway(
            PlatformServices services,
            PlatformProperties props,
            RuntimeService runtime,
            A2aTaskStore tasks,
            Environment env) {
        long seconds = env.getProperty("eab.a2a-timeout-seconds", Long.class, A2aOutbound.DEFAULT_TIMEOUT.toSeconds());
        var gateway = new RemoteAgentGateway(
                services,
                tasks,
                new RemoteAgentGateway.Settings(
                        props.resolvedConnections(), () -> publicUrl(env), Duration.ofSeconds(seconds)));
        runtime.remoteAgents(gateway);
        return gateway;
    }

    @Bean
    public InboundAgents inboundAgents(
            PlatformServices services, AssistService assist, RuntimeService runtime, Environment env) {
        return new InboundAgents(
                services,
                () -> publicUrl(env),
                exposed -> new InboundRunner(exposed, assist, runtime),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Bean
    public ExternalAgentService externalAgentService(PlatformServices services, RemoteAgentGateway gateway) {
        return new ExternalAgentService(services, gateway);
    }

    static String publicUrl(Environment env) {
        String configured = env.getProperty("eab.public-url");
        if (configured != null && !configured.isBlank()) return configured.replaceAll("/+$", "");
        return "http://127.0.0.1:" + env.getProperty("local.server.port", env.getProperty("server.port", "4000"));
    }
}

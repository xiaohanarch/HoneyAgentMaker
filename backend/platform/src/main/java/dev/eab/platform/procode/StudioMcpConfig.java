package dev.eab.platform.procode;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.extend.ExtendService;
import dev.eab.platform.extend.ExtensionReleases;
import dev.eab.platform.http.Principals;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.skills.SkillStudio;
import dev.eab.platform.studio.StudioReleases;
import dev.eab.platform.studio.StudioService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/** Studio MCP 端点（/studio-mcp）的装配（ADR 0014）：工具清单固定，启动时登记一次 */
@Configuration(proxyBeanMethods = false)
public class StudioMcpConfig {

    @Bean(destroyMethod = "close")
    public StudioMcpServer studioMcpServer(
            PlatformServices services,
            Principals principals,
            RuntimeService runtime,
            StudioService studio,
            StudioReleases releases,
            ExtendService extend,
            ExtensionReleases extensionReleases,
            SkillStudio skills) {
        return new StudioMcpServer(services, principals, runtime, studio, releases, extend, extensionReleases, skills);
    }

    @Bean
    public RouterFunction<ServerResponse> studioMcpRouter(StudioMcpServer server) {
        return server.router();
    }
}

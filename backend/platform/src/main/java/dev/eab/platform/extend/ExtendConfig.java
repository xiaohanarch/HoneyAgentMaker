package dev.eab.platform.extend;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.EvalRunner;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.tools.McpGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P6 扩展标品 Agent 的装配（ADR 0011） */
@Configuration(proxyBeanMethods = false)
public class ExtendConfig {

    @Bean
    public ExtendService extendService(PlatformServices services, RuntimeService runtime, StudioService studio) {
        return new ExtendService(services, runtime, studio);
    }

    /** 裁判与 Studio 一致：脚本化模型下确定性，配置真实模型时用 judge 档位 */
    @Bean
    public ExtensionReleases extensionReleases(ExtendService extend, RuntimeService runtime, McpGate mcpGate) {
        var gateway = runtime.settings().gateway();
        EvalRunner.Judge judge = gateway.providerName().equals("scripted")
                ? EvalRunner.DETERMINISTIC
                : EvalRunner.model(gateway.chatModel(), gateway.config().modelFor("judge"));
        return new ExtensionReleases(extend, judge, mcpGate);
    }
}

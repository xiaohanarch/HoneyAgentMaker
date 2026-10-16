package dev.eab.platform.hub;

import dev.eab.eval.Assertions;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.lifecycle.EvalService;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioStores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 治理中心、评测、生命周期、经验回流（P8，ADR 0013）的装配 */
@Configuration
public class HubConfig {

    /** 裁判：脚本化模型下确定性，配置了真实模型时用 judge 档位的模型 */
    static Assertions.Judge judge(RuntimeService runtime) {
        var gateway = runtime.settings().gateway();
        return gateway.providerName().equals("scripted")
                ? Assertions.DETERMINISTIC
                : Assertions.model(gateway.chatModel(), gateway.config().modelFor("judge"));
    }

    @Bean
    public EvalService evalService(PlatformServices services, RuntimeService runtime) {
        return new EvalService(
                services,
                runtime,
                new StudioStores.EvalRunStore(services.db().jdbc(), services.clock()),
                judge(runtime));
    }
}

package dev.eab.platform.hub;

import dev.eab.eval.Assertions;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.assist.NotificationStore;
import dev.eab.platform.feedback.FeedbackCandidates;
import dev.eab.platform.feedback.FeedbackService;
import dev.eab.platform.feedback.FeedbackStore;
import dev.eab.platform.lifecycle.EvalService;
import dev.eab.platform.lifecycle.SolutionService;
import dev.eab.platform.lifecycle.UpgradeService;
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

    static StudioStores.EvalRunStore evalRuns(PlatformServices services) {
        return new StudioStores.EvalRunStore(services.db().jdbc(), services.clock());
    }

    @Bean
    public EvalService evalService(PlatformServices services, RuntimeService runtime) {
        return new EvalService(services, runtime, evalRuns(services), judge(runtime));
    }

    @Bean
    public FeedbackService feedbackService(PlatformServices services, RuntimeService runtime) {
        var gateway = runtime.settings().gateway();
        return new FeedbackService(
                services,
                runtime,
                new FeedbackStore(services.db().jdbc(), services.clock()),
                new FeedbackCandidates(gateway.chatModel(), gateway.config().modelFor("fast")));
    }

    @Bean
    public UpgradeService upgradeService(PlatformServices services, EvalService evals, NotificationStore notifications) {
        return new UpgradeService(services, evals, notifications);
    }

    @Bean
    public SolutionService solutionService(PlatformServices services, StudioStores.DeploymentStore deployments) {
        return new SolutionService(services, deployments);
    }

    @Bean
    public MetricsService metricsService(PlatformServices services, RuntimeService runtime) {
        return new MetricsService(services, runtime.traces());
    }

    @Bean
    public IntegrationCompare integrationCompare(PlatformServices services, RuntimeService runtime) {
        return new IntegrationCompare(services, runtime);
    }

    @Bean
    public HubService hubService(PlatformServices services, RuntimeService runtime, StudioStores.DeploymentStore deployments) {
        return new HubService(services, runtime, deployments, evalRuns(services));
    }
}

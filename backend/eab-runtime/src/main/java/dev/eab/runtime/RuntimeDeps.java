package dev.eab.runtime;

import dev.eab.runtime.model.ModelGateway;
import dev.eab.runtime.semantic.SemanticService;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import java.time.Clock;
import java.util.List;

/**
 * 运行时的依赖。资产解析器给出技能、工具来源、工作流、语义包的定义（只用已发布的版本，除非测试态运行显式放开）。
 *
 * @param statuses 解析资产时接受的状态，正式运行只接受 published
 * @param maxTurns 一次 Agent 运行里“模型调用 → 工具调用”的最大轮数
 */
public record RuntimeDeps(
        ModelGateway gateway,
        AssetResolver resolver,
        List<Status> statuses,
        Ports.Assembly assembly,
        Ports.Identity identity,
        Ports.BoApi bo,
        Ports.Policy policy,
        Ports.Mcp mcp,
        Ports.Http http,
        Ports.Notifier notifier,
        Ports.Documents documents,
        Ports.Memory memory,
        Ports.Alerts alerts,
        SemanticService.MetaClient meta,
        Clock clock,
        int maxTurns,
        Ports.RemoteAgents remoteAgents) {

    public static final int DEFAULT_MAX_TURNS = 8;

    /** 不接远程 Agent 的运行环境（测试、命令行）：调用远程 Agent 时明确失败 */
    public RuntimeDeps(
            ModelGateway gateway,
            AssetResolver resolver,
            List<Status> statuses,
            Ports.Assembly assembly,
            Ports.Identity identity,
            Ports.BoApi bo,
            Ports.Policy policy,
            Ports.Mcp mcp,
            Ports.Http http,
            Ports.Notifier notifier,
            Ports.Documents documents,
            Ports.Memory memory,
            Ports.Alerts alerts,
            SemanticService.MetaClient meta,
            Clock clock,
            int maxTurns) {
        this(
                gateway,
                resolver,
                statuses,
                assembly,
                identity,
                bo,
                policy,
                mcp,
                http,
                notifier,
                documents,
                memory,
                alerts,
                meta,
                clock,
                maxTurns,
                (context, remoteRef, message) ->
                        Ports.RemoteResult.failure("REMOTE_AGENT_DISABLED", "本运行环境没有接入 A2A 网关"));
    }
}

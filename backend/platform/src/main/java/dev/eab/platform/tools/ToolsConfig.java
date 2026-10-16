package dev.eab.platform.tools;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioReleases;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P6 工具中心的装配（ADR 0011） */
@Configuration(proxyBeanMethods = false)
public class ToolsConfig {

    @Bean
    public McpApprovalStore mcpApprovalStore(PlatformServices services) {
        return new McpApprovalStore(services.db().jdbc(), services.clock());
    }

    @Bean
    public ToolCenter toolCenter(PlatformServices services, McpApprovalStore approvals, RuntimeService runtime) {
        return new ToolCenter(services, approvals, runtime.settings().erpUrl());
    }

    /** MCP 审批门禁：Agent 晋级 prod 时检查（扩展发布时由 ExtensionReleases 检查） */
    @Bean
    public McpGate mcpGate(PlatformServices services, McpApprovalStore approvals, StudioReleases releases) {
        McpGate gate = new McpGate(services, approvals);
        releases.addProdGate(gate);
        return gate;
    }
}

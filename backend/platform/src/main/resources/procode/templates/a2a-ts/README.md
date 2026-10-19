# {{name}} · A2A 外部 Agent（TypeScript）

启动与接入：

    npm install
    npm start               # 默认端口 4310
    npm run typecheck

Agent Card 地址 http://127.0.0.1:4310/.well-known/agent-card.json，
工程清单 eab-project.yaml 里的 cardUrl 已指向本机默认端口。

安全约定：除 Agent Card 外的请求都要带平台签发的服务令牌（EdDSA，受众 eab-a2a），
公钥取平台 /identity/v1/jwks.json，校验失败返回 401，不做匿名降级。

回查平台（src/callback.ts）：外部 Agent 需要查 ERP 数据时，用本次请求携带的服务令牌到平台
/identity/v1/token 换一个受限只读回调令牌（受众 eab-platform，只含发起用户的 L0 只读操作，
主体是发起用户），再用官方 MCP SDK 的 Streamable HTTP 客户端调平台 /mcp 的工具。
调用与 Agent 的工具调用走同一条工具网关（鉴权、审计、数据范围），用户在 ERP 里做不了的事
这里也做不了。executor.ts 里的「查订单」是完整示例：换不到令牌或调用被拒时如实说明，不编造数据。

本地调试通过后，在工程目录运行 `pnpm --dir <平台仓库> eab deploy`（`--dir` 可省）：平台读取卡片并把远程 Agent 资产
发布到 tenant.<租户>.remote 命名空间；要被平台 Agent 调用，资产还需声明 {type: agent} 触发器。

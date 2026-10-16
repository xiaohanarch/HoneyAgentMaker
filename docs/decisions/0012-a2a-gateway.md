# ADR 0012 A2A 网关：出站、入站与外部 Agent 接入

状态：已采纳（P7，2026-10-16）

## 背景

主提示词 8.4 节要求平台既能调用外部 Agent（出站），也能把自己的 Agent 和岗位助手发布给外部调用（入站），并且身份透传失败一律拒绝。13.3 节要求两个外部框架写的 Agent 样例、一个第三方 MCP Server 样例和一个模拟外部平台的调用脚本。10.5 节要求 Studio 提供“接入外部 Agent”页面。后端已改为 Java（ADR 0006），协议实现用 A2A 官方 Java SDK 1.4.0.Final（版本见 ADR 0002）。

## 决策

### 模块划分

- `backend/eab-a2a`：A2A 官方 Java SDK 的薄封装，不依赖 Spring。`A2aOutbound` 是出站客户端（拉取 Agent Card、同步或流式发送、超时转异步、查询任务、记录请求与响应原文）；`A2aServer` 是入站服务端（SDK 的请求处理器、任务存储、事件总线和推送通知装配在一起，按请求体返回 JSON 或 SSE）；`LegacyJsonRpc` 处理 0.3 版本客户端；`sdk` 包是代码 Agent SDK。
- 平台的 `dev.eab.platform.a2a`：`RemoteAgentGateway`（出站网关，实现运行时的 `Ports.RemoteAgents`）、`A2aCallbackController`（推送回调）、`RemoteRuns`（统一入口直接路由到外部 Agent 的运行）、`InboundAgents` 与 `A2aInboundController`（入站）、`ExternalAgentService` 与 `ExternalAgentController`（Studio 接入页面的接口）、`A2aConfig`（装配，不改 `PlatformConfig`）。
- 运行时只多了一个端口：工具网关遇到 `remoteAgent` 类型的工具时调用 `Ports.RemoteAgents`，并写一个 `A2A · <名称>` span。

### 出站

- 远程 Agent 资产（RemoteAgent）只写连接别名。地址先取部署配置（`eab.connections`，环境变量 `EAB_SUPPLIER_RISK_URL` 等），再取本租户 Connection 资产的 prod 绑定；都没有时拒绝调用。
- 每次调用以最终用户为 `sub`、发起调用的平台 Agent（或岗位助手、统一入口）为 `act.sub` 签发 5 分钟有效的服务令牌，受众 `eab-a2a`。运行没有最终用户身份、连接未绑定、调用方不在 `allowedCallers` 里时一律拒绝并写 `a2a.outbound` 审计，没有任何退回技术账号的路径。
- 对方声明支持流式时用 `SendStreamingMessage`，否则用 `SendMessage`。同步等待上限 60 秒（`EAB_A2A_TIMEOUT_SECONDS`），超时后转为异步：对方支持推送时登记回调 `/a2a/callbacks/<编号>`，带一次性令牌（只存哈希），任务结束后结果写进发起用户的通知区。
- 多轮：平台在 `a2a_tasks` 表里按会话记录对方的 contextId、taskId 和状态。对方返回 input-required 时把追问展示给用户；同一会话的下一句话由统一入口直接交回同一个外部 Agent（路由决策记为 `session`），网关带上 contextId、taskId 续接。
- 对方停机、超时或报错时返回 `REMOTE_AGENT_UNAVAILABLE`，回复里说明“本次回答不包含它提供的信息，可以稍后重试”。
- 请求与响应原文（令牌只保留类型 `Bearer ***`）写进 span 的 `eab.a2a.exchanges`，统一入口的“本次链路”可以展开查看。

### 统一入口里的远程 Agent

能力目录收录已发布的 RemoteAgent，条目类型为 `remote`，执行动作类型为 `agent-request`。声明了 `assistant` 的远程 Agent 加入该岗位助手的成员，由第二级路由选中；没有声明的由入口直达。选中后由 `RemoteRuns` 记一次运行和追踪（routing span、A2A span），不经过模型。

### 入站

- 触发器里声明了 `{type: agent}`（被其他 Agent 调用）的已发布 Agent 和岗位助手对外暴露，地址是 `/a2a/agents/<namespace>/<name>`，Agent Card 在其下的 `/.well-known/agent-card.json` 和旧路径 `/.well-known/agent.json`（0.3 格式）。标品的采购助手 1.1.1 加上了这个触发器。
- JSON-RPC 请求必须带平台签发的用户令牌，缺少或无效时返回 401 并写 `a2a.inbound` 拒绝审计；租户层资产只能由本租户用户调用。允许的调用写 `a2a.inbound` 审计，记录用户和调用方自报的平台名（请求头 `X-A2A-Caller`）。
- 岗位助手的请求走统一入口的两级路由（第一级固定为该助手），Agent 的请求直接运行，因此四处鉴权与统一入口完全一致。A2A 的 contextId 对应平台会话 `a2a-<contextId>`。运行等待人工确认时任务停在 input-required，调用方回复“确认”继续，其他内容取消。
- 入站不提供推送通知，避免平台向任意地址发请求。

### 0.3 兼容

旧路径的 Agent Card 由 1.0 的 Card 转换成 0.3 格式（`url`、`protocolVersion`、`preferredTransport`）。方法名带斜杠的请求（`message/send`、`message/stream`、`tasks/get`、`tasks/cancel`）交给 SDK 的 `JSONRPCHandler_v0_3`，它经 `Convert_v0_3_To10RequestHandler` 转给同一个请求处理器，任务和鉴权只有一套。ADR 0002 里 TypeScript 兼容层的写法相应改为 Java SDK 的这两个类。

### SDK 的两处绕行

- SDK 1.4.0 的 `DefaultRequestHandler.Builder` 把阻塞式 `SendMessage` 的等待时间写死为 Agent 5 秒、事件消费 2 秒，平台的运行经常更久。`A2aServer` 把非流式请求改为内部走流式路径，等到任务进入终态或 input-required 后再按 `GetTask` 取完整任务返回（`BlockingSend`）。
- SDK 的 `authorizationRequired` 默认为 true，必须提供任务归属判定，否则后续请求查不到任务。`OwnerTaskAuthorization` 规定任务只属于创建它的用户。

### 外部 Agent 样例

| 工程 | 实现 | 端口 |
|---|---|---|
| `external-agents/supplier-risk-py` | Python 3.12、uv、LangGraph、`a2a-sdk` 1.2.2；多轮追问；话术含“深度”时慢速返回，演示超时转异步与推送回调；校验平台服务令牌（JWKS） | 4200 |
| `external-agents/fx-rate-ts` | 只用 `@a2a-js/sdk` 1.3.0 和 Express，一个执行器加一张 Agent Card；用 `jose` 校验服务令牌 | 4210 |
| `external-agents/logistics-mcp` | `@modelcontextprotocol/sdk` 1.32.1，无状态 Streamable HTTP，工具 `track_shipment` | 4400（`/mcp`） |
| `external-agents/client-demo` | `@a2a-js/sdk` 客户端，模拟外部平台以张敏的身份调用采购助手，先演示没有身份被拒绝 | 无 |

JS SDK 的 `agentCardHandler` 直接序列化内部对象，oneof 字段会带 `$case`，其他语言的 SDK 解析不了；`fx-rate-ts` 先用 `AgentCard.toJSON` 转成协议的 JSON 形式再交给它。

`pnpm demo` 把三个服务作为可选服务启动：先找 uv（PATH 上的 `uv`，或 `python -m uv`），找到就 `uv sync --frozen` 再启动 Python Agent；可选服务启动失败或退出不影响主流程，统一入口给出降级说明。

### 代码 Agent SDK

主提示词里的“TypeScript SDK”按 ADR 0006 改为 Java。`dev.eab.a2a.sdk` 提供 `CodeAgent`（实现一个方法即可）、`CodeAgentServer`（以 A2A 对外服务，只接受平台服务令牌）、`PlatformTokens`（按平台 JWKS 校验 EdDSA 服务令牌）和 `AgentDeclaration`（生成 RemoteAgent 资产）。代码 Agent 以独立进程运行，平台把它当作远程 Agent 调用，路由、鉴权和审计与其他外部 Agent 一样。

代码 Agent 回调平台的工具网关（以用户身份查询 ERP 数据）在 P7 没有实现：服务令牌的受众是 `eab-a2a`，平台的 MCP 端点只接受用户令牌，BO API 调用又需要以用户令牌做令牌交换。要支持回调，需要一种不把用户令牌交给外部进程的交换方式（例如以服务令牌换取受限的、只读的委托令牌），放到 P9 的专业代码通道里设计。

### Studio“接入外部 Agent”

输入 Agent Card 地址后，平台拉取并解读 Card（技能、流式、推送、认证方式、接口），建议资产名、连接别名和路由说明；连通性测试以开发者本人为主体签发服务令牌发一条消息，返回回复和请求、响应原文；发布时写入 `tenant.<租户>.remote` 命名空间的 RemoteAgent（同名内容不同时升补丁版本），连接别名还没有登记时一并写入 Connection 资产。为避免被当作任意地址的请求转发器，只允许访问本机回环地址，或者连接中心、部署配置里已经登记过的地址。

## 影响

- 新增平台迁移 V6（`a2a_tasks`）。启动入库的目录增加 `tenants/<租户>/remote`，演示数据里登记了供应商风险舆情 Agent 和汇率查询 Agent。
- 故事 A 的价格谈判助手在协作 Agent 一步推荐外部的供应商风险舆情 Agent，测试剧本多一步 A2A 调用；外部 Agent 不可用时回复里给出降级说明。
- protobuf 运行时统一到 4.35.1：A2A SDK 的 BOM 管理到 4.35.0，CEL 的生成代码要求不低于 4.35.1。

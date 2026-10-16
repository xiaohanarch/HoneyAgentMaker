# ADR 0002 A2A 与 MCP 的协议版本和 SDK 版本

状态：已采纳（P0，2026-10-14）；2026-10-15 因后端改用 Java（ADR 0006）补充 Java SDK 版本；2026-10-16 P7 实现 A2A 时重新核对版本，补充实际用法（见文末）。

## 背景

主提示词 8.3、8.4 节要求使用 A2A 和 MCP 的官方 SDK，协议版本以官方最新稳定版为准；Agent Card 要同时在 `/.well-known/agent-card.json` 和旧路径 `/.well-known/agent.json` 提供，兼容使用 A2A 0.3.0 的客户端。以下版本信息在 2026-10-14 从 npm 和 PyPI 查得。

## 决策

| 组件 | 选用版本 | 协议版本 | 用在哪里 |
|---|---|---|---|
| `@a2a-js/sdk` | 1.3.0 | A2A v1.0.0 | 平台的 A2A 网关（出站客户端、入站服务端）、`external-agents/fx-rate-ts` |
| `a2a-sdk`（Python） | 1.2.2 | A2A v1.0 | `external-agents/supplier-risk-py` |
| `langgraph`（Python） | 1.2.15 | 不涉及 | `external-agents/supplier-risk-py` 的 Agent 逻辑 |
| `@modelcontextprotocol/sdk` | 1.32.1 | MCP 2025-11-25 | MCP 网关、平台对外 MCP 端点、Studio MCP Server、`external-agents/logistics-mcp` |

依赖声明使用插入号范围（例如 `^1.3.0`），由 `pnpm-lock.yaml` 锁定实际版本。

### A2A

- 平台以 A2A v1.0 为主协议，传输层用 JSON-RPC。SDK 也支持 HTTP+JSON/REST 和 gRPC，演示中不启用 gRPC。
- 兼容 0.3.0 客户端用 SDK 自带的兼容层 `@a2a-js/sdk/compat/v0_3`：
  - 入站使用 `LegacyJsonRpcTransportHandler` 处理 0.3 风格的方法名（如 `message/send`），与 v1.0 处理器共用同一个 `DefaultRequestHandler`，因此任务存储、鉴权、审计只有一套。
  - 出站时，A2A 网关拉取 Agent Card 后用 `isLegacyAgentCard` 判断对方版本，0.3 的对端改用 `LegacyJsonRpcTransport`。
- Agent Card 路径：`/.well-known/agent-card.json` 返回 v1.0 格式的 Card；`/.well-known/agent.json` 返回 0.3 格式的 Card。两份 Card 从同一份能力目录数据生成，内容一致，只有格式不同。
- 认证按主提示词第 20 节的默认决策：平台签发服务令牌，Agent Card 里声明 bearer 认证。

### MCP

- 传输层统一用 Streamable HTTP，不提供已废弃的 HTTP+SSE 传输。
- SDK 同时支持 2025-06-18、2025-03-26 等旧版本，客户端协商时自动降级，不需要额外处理。
- 平台对外 MCP 端点的授权：完整的 MCP 授权规范基于 OAuth 2.1，超出演示范围。演示中由模拟身份服务为用户签发绑定租户和用户的访问令牌，外部 MCP 客户端以 bearer 方式携带；令牌同样经过鉴权点 3。生产环境应按 MCP 授权规范接入企业 IdP。

### 后端改用 Java 之后（2026-10-15）

平台和 DemoERP 改为 Java 实现后，服务端使用 Java 版 SDK，协议版本不变：

| 组件 | 选用版本 | 用在哪里 |
|---|---|---|
| MCP Java SDK `io.modelcontextprotocol.sdk:mcp` | 2.0.0，由 `spring-ai-mcp` 2.0.1 引入（J3 实际解析到的版本） | MCP 网关接入外部 MCP Server（`HttpClientStreamableHttpTransport`） |
| Spring AI `mcp-spring-webmvc` | 2.0.1 | 平台对外 MCP 端点 `/mcp`，用无状态的 Streamable HTTP 传输（`WebMvcStatelessServerTransport`） |
| A2A Java SDK `org.a2aproject.sdk` | 1.4.0.Final | A2A 网关的出站与入站 |

平台对外的 MCP 端点选用无状态传输：每个请求独立鉴权（Bearer 用户令牌经传输上下文传给工具处理函数），不需要服务端保存会话，多实例部署也不用粘性会话。端点只暴露只读工具，调用时以 `agent:platform/mcp-endpoint` 的身份做令牌交换。

A2A Java SDK 在 2026 年把 groupId 从 `io.github.a2asdk` 改为 `org.a2aproject.sdk`，包名从 `io.a2a` 改为 `org.a2aproject.sdk`，查资料时注意区分。上文 TypeScript SDK 的兼容层用法在 P7 按 Java SDK 的接口重新核对。外部 Agent 样例仍按上表使用 Python 和 JS SDK。

### P7 核对结果（2026-10-16）

P7 实现前在 Maven Central、PyPI 和 npm 重新查了一遍，各 SDK 的最新稳定版与上表一致，没有升级：

| 组件 | 版本 | 用在哪里 |
|---|---|---|
| A2A Java SDK `org.a2aproject.sdk`（BOM `a2a-java-sdk-bom`） | 1.4.0.Final | `backend/eab-a2a`：client、client-transport-jsonrpc、server-common、transport-jsonrpc、compat-0.3-transport-jsonrpc |
| `a2a-sdk[http-server]`（Python） | 1.2.2 | `external-agents/supplier-risk-py` |
| `langgraph`（Python） | 1.2.15 | 同上 |
| `@a2a-js/sdk` | 1.3.0 | `external-agents/fx-rate-ts`（服务端）、`external-agents/client-demo`（客户端） |
| `@modelcontextprotocol/sdk` | 1.32.1 | `external-agents/logistics-mcp` |

外部 Agent 样例的依赖固定到上表的精确版本（Python 由 `uv.lock` 锁定）。0.3 兼容在 Java 侧用 `JSONRPCHandler_v0_3` 和 `Convert_v0_3_To10RequestHandler` 实现，取代上文 TypeScript 兼容层的写法；平台出站只面向 1.0 的 Agent Card。实现细节和两处绕行见 ADR 0012。

## 影响

- 入站 A2A 请求无论走 v1.0 还是 0.3 路径，都经过同一套四处鉴权和审计，P7 为两种路径各写一组测试。
- A2A v1.0 与 0.3 的字段差异（Card 结构、方法名、任务状态的表示）由 SDK 的兼容层处理，平台代码只面向 v1.0 的类型。

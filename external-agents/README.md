# 外部 Agent 样例

这里的工程运行在平台之外，演示其他框架写的 Agent 经 A2A 协议接入平台，以及外部平台反向调用平台里的岗位助手。设计见 `docs/decisions/0012-a2a-gateway.md`。

| 工程 | 说明 | 端口 |
|---|---|---|
| `supplier-risk-py` | 供应商风险舆情 Agent，Python 3.12、uv、LangGraph、A2A 官方 Python SDK；多轮追问、异步任务与推送回调 | 4200 |
| `fx-rate-ts` | 汇率查询 Agent，只用 A2A 官方 JS SDK，不用 Agent 框架，演示最小接入成本 | 4210 |
| `logistics-mcp` | 物流跟踪 MCP Server，MCP 官方 TypeScript SDK，Streamable HTTP | 4400 |
| `client-demo` | 模拟外部平台的 Agent，经 A2A 调用平台的采购助手 | 无 |

`pnpm demo` 会把前三个作为可选服务一起启动；Python 工程需要 uv（`pip install --user uv`），启动时自动按 `uv.lock` 安装依赖。单独运行 client-demo：`pnpm --filter @eab/client-demo run start`。

## 写一个新的外部 Agent

前两个工程可以直接当模板用。接入平台需要满足三点：

1. 在 `/.well-known/agent-card.json` 提供 A2A v1.0 的 Agent Card，声明 bearer 认证；需要兼容 0.3 客户端时在 `/.well-known/agent.json` 也提供一份。
2. JSON-RPC 端点校验平台签发的服务令牌：EdDSA 签名，公钥取平台的 `/identity/v1/jwks.json`，签发者 `eab-identity`，受众 `eab-a2a`，种类 `service`。令牌的 `sub` 是最终用户，`act.sub` 是发起调用的平台 Agent。没有令牌或校验失败时返回 401。
3. 需要用户补充信息时把任务置为 input-required；处理时间可能超过 60 秒时支持推送通知，平台会登记回调地址。

然后在 Studio 的“接入外部 Agent”页面输入 Agent Card 地址，做连通性测试并发布。

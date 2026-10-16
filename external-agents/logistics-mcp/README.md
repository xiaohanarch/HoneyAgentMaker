# 物流跟踪 MCP Server

第三方 MCP Server 样例：MCP 官方 TypeScript SDK，无状态的 Streamable HTTP 传输，端点 `/mcp`，一个只读工具 `track_shipment`（按采购订单号或运单号查询在途运单，数据为虚构）。平台用连接别名 `LOGISTICS_MCP` 访问它。

```
pnpm --filter @eab/logistics-mcp run start  # 默认 127.0.0.1:4400
```

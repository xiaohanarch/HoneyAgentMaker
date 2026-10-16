# 汇率查询 Agent（TypeScript，只用 A2A JS SDK）

演示不用任何 Agent 框架时的最小接入成本：`src/executor.ts` 一个执行器，`src/app.ts` 一张 Agent Card 加两个 Express 路由，`src/auth.ts` 用 `jose` 校验平台服务令牌。汇率是演示数据（`src/rates.ts`）。

```
pnpm --filter @eab/fx-rate-ts run start     # 默认 127.0.0.1:4210
```

JS SDK 的 `agentCardHandler` 会原样序列化传入的对象，这里先用 `AgentCard.toJSON` 转成协议的 JSON 形式，其他语言的 SDK 才能解析。

# ADR 0001 后端框架选用 Fastify

状态：已被 ADR 0006 替代（2026-10-15）。原为 P0 采纳，后端已改为 Java 与 Spring Boot

## 背景

主提示词第 14 节要求后端在 Fastify 和 Hono 之间二选一，并用 zod 做 schema 校验、生成 JSON Schema 和 OpenAPI。后端有两个服务：平台主服务 `apps/platform` 和模拟 ERP `apps/demo-erp`。平台主服务要同时承载几类协议端点：

- 普通的 REST API（资产、治理、身份），需要 OpenAPI 描述；
- 流式对话，用 SSE；
- MCP Server 端点，使用 MCP 官方 TypeScript SDK 的 Streamable HTTP 传输；
- A2A Server 端点，使用 A2A 官方 JS SDK。

两个协议 SDK 的服务端接口决定了框架选型的约束。核对 SDK 当前版本（见 ADR 0002）后得到的事实：

- MCP SDK 1.32.1 的 `StreamableHTTPServerTransport.handleRequest(req, res, body)` 直接接收 Node 原生的 `IncomingMessage` 和 `ServerResponse`。
- A2A JS SDK 1.3.0 自带的服务端适配器只有 Express 版本（`@a2a-js/sdk/server/express`）；同时它导出了与框架无关的 `JsonRpcTransportHandler`，以及 v0.3 兼容层里的 `LegacyJsonRpcTransportHandler`，输入是解析后的 JSON-RPC 请求，输出是结果对象或异步事件流。

## 决策

两个后端服务都使用 Fastify 5。

- REST API 用 `fastify-type-provider-zod` 把 `packages/spec` 里的 zod schema 同时用于请求校验和 OpenAPI 生成（`@fastify/swagger`），schema 只维护一份。
- MCP 端点在 Fastify 路由里取 `request.raw`、`reply.raw` 交给 SDK 的传输层，并调用 `reply.hijack()` 让 SDK 接管响应。
- A2A 端点在 Fastify 路由里调用 `JsonRpcTransportHandler`，把返回的事件流写成 SSE。不引入 Express。如果后续发现框架无关的处理器覆盖不全，再用 `@fastify/express` 挂载 SDK 的 Express 路由作为退路。

## 理由

- Fastify 的插件体系和 zod、OpenAPI 的集成在 Node 生态里最成熟，主提示词要求“所有对外接口有 OpenAPI 描述”，这一点比较重要。
- 两个协议 SDK 都以 Node 原生 HTTP 对象为基础，Fastify 拿到原始 req/res 很直接；Hono 基于 Web 标准的 Request/Response，接 MCP SDK 的 Node 传输层需要额外的适配。
- Hono 的优势是可以跑在边缘运行时，本项目只在 Node 上运行，用不到。
- 演示系统要给架构师读代码，Fastify 的路由、钩子、插件写法直观，容易对照。

## 影响

- 每个服务用 `buildApp()` 构造实例，测试里直接调用 `app.inject()`，不监听端口。
- 鉴权以 Fastify 的 `onRequest` 钩子调用 `packages/policy` 实现，业务路由里不写鉴权逻辑。

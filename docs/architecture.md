# 架构说明

本文随阶段推进逐步补全。完整的目标架构见 `docs/brief/02-主提示词.md` 第 4 节，本文只描述已经实现的部分。

## 当前状态（P0 工程骨架）

仓库是 pnpm workspace 单仓，全部使用 TypeScript（strict），由三部分组成。

`apps/` 下是三个可运行的服务。`platform`（端口 4000）和 `demo-erp`（端口 4100）基于 Fastify，目前只有 `/health`；每个服务通过 `buildApp()` 构造实例，测试直接调用 `app.inject()`，不监听端口。`studio-web`（端口 5173）是 React + Vite 前端，按主提示词第 20 节的默认决策，统一入口、Studio、治理中心是同一个前端工程里的三个路由入口，开发服务器把 `/api` 转发到 platform、`/erp` 转发到 demo-erp，前端只访问同源地址。

`packages/` 下是平台各模块。各包直接以 TypeScript 源码作为入口（`exports` 指向 `src/index.ts`），服务用 tsx 运行，前端由 Vite 编译，不需要预先构建。目前有实际内容的两个包：

- `packages/spec` 定义三层资产的命名空间规则（`platform`、`vendor.*`、`tenant.<id>.*`），后续所有资产 schema 都放在这里；
- `packages/ui` 定义视觉规范：三类资产来源（标品、租户、外部，另加平台）的颜色、图标与标签组件，写操作级别标签，鉴权裁决标签。颜色以 CSS 变量定义，浅色和深色模式各一套，前端通过 Tailwind 的主题变量使用。

其余包是空壳，在对应阶段实现。

各服务的监听地址由专用环境变量决定，不读取通用的 `PORT`、`HOST`，以免继承开发者 shell 里的设置：`EAB_HOST`（默认 127.0.0.1，容器内为 0.0.0.0）、`EAB_PLATFORM_PORT`（默认 4000）、`EAB_DEMO_ERP_PORT`（默认 4100）；前端代理目标由 `EAB_PLATFORM_URL`、`EAB_DEMO_ERP_URL` 指定。docker-compose 把端口只发布在本机回环地址上。

`scripts/demo.mjs` 用 concurrently 并行启动各服务，轮询健康检查，全部就绪后打印访问地址；某个必需服务在 90 秒内没有就绪时，停止全部进程并报错。服务项可以标记为可选，可选服务失败不影响演示主流程（给后续的 Python 外部 Agent 预留）。

## 技术选型

| 方面 | 选择 | 依据 |
|---|---|---|
| 后端框架 | Fastify 5 | ADR 0001 |
| 协议 SDK | A2A JS SDK 1.3.0（协议 v1.0，带 0.3 兼容层）、MCP TS SDK 1.32.1（协议 2025-11-25） | ADR 0002 |
| 前端 | React 19、Vite 8、Tailwind CSS 4、Radix 原语（shadcn/ui 风格）、TanStack Query、React Router | 主提示词第 14 节 |
| 测试 | Vitest 5、Playwright | 主提示词第 14 节 |
| TypeScript | 6.0 | typescript-eslint 8.x 目前支持到 6.0，暂不升级到 7.x |

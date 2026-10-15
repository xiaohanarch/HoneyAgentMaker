# 架构说明

本文随阶段推进逐步补全。完整的目标架构见 `docs/brief/02-主提示词.md` 第 4 节，本文只描述已经实现的部分。

## 工程结构（P0）

仓库是 pnpm workspace 单仓，全部使用 TypeScript（strict），由三部分组成。

`apps/` 下是三个可运行的服务。`platform`（端口 4000）和 `demo-erp`（端口 4100）基于 Fastify；每个服务通过 `buildApp()` 构造实例，测试直接调用 `app.inject()`，不监听端口。`studio-web`（端口 5173）是 React + Vite 前端，按主提示词第 20 节的默认决策，统一入口、Studio、治理中心是同一个前端工程里的三个路由入口，开发服务器把 `/api` 转发到 platform、`/erp` 转发到 demo-erp，前端只访问同源地址。

`packages/` 下是平台各模块。各包直接以 TypeScript 源码作为入口（`exports` 指向 `src/index.ts`），服务用 tsx 运行，前端由 Vite 编译，不需要预先构建。`packages/ui` 定义视觉规范：三类资产来源（标品、租户、外部，另加平台）的颜色、图标与标签组件，写操作级别标签，鉴权裁决标签。颜色以 CSS 变量定义，浅色和深色模式各一套，前端通过 Tailwind 的主题变量使用。尚未实现的包是空壳，在对应阶段实现。

各服务的监听地址由专用环境变量决定，不读取通用的 `PORT`、`HOST`，以免继承开发者 shell 里的设置：`EAB_HOST`（默认 127.0.0.1，容器内为 0.0.0.0）、`EAB_PLATFORM_PORT`（默认 4000）、`EAB_DEMO_ERP_PORT`（默认 4100）；前端代理目标由 `EAB_PLATFORM_URL`、`EAB_DEMO_ERP_URL` 指定。docker-compose 把端口只发布在本机回环地址上。

`scripts/demo.mjs` 用 concurrently 并行启动各服务，轮询健康检查，全部就绪后打印访问地址；某个必需服务在 90 秒内没有就绪时，停止全部进程并报错。服务项可以标记为可选，可选服务失败不影响演示主流程（给后续的 Python 外部 Agent 预留）。

## 资产模型与组装（P1）

资产模型由三部分组成：`packages/spec` 定义资产，`packages/assembler` 把资产组装成有效定义，平台主服务里的注册中心负责存储与可见性。术语见 `docs/concepts.md`。

### packages/spec

每个 kind 一个 zod schema，加上公共信封（`apiVersion`、`kind`、`metadata`、`spec`），共 14 个 kind。`parseAssetYaml` 先做 YAML 语法检查，再按 kind 校验，报错用中文，并通过 YAML 节点位置换算出行号和字段路径，格式如 `第 17 行第 15 列 spec.tools[2].mcp.source：……`。schema 用 `strictObject`，拼错的字段名会被指出来。

这个包还提供几样共用的东西：

- 命名空间分层规则和资产引用解析，`resolveAsset` 按 semver 范围在指定状态的版本里取最高版本；
- `AssetResolver` 接口，组装器、静态检查、注册中心共用；
- 按查看者裁剪资产的 `redactAsset`，以及写入权限判断 `canWriteNamespace`；
- 静态检查 `lintAsset`，目前实现四条规则；
- `@eab/spec/node` 子入口读取资产目录，`@eab/spec/testing` 提供测试用的资产构造器；
- 由 schema 生成的 JSON Schema 放在 `packages/spec/json-schema/`，用 `pnpm spec:json-schema` 重新生成，有测试检查它与 schema 同步。

### packages/assembler

`assemble()` 是纯函数：输入要组装的 Agent、候选扩展、平台规则和解析器，输出有效定义和问题列表，不抛异常。主提示词 5.4 节七条规则的实现位置如下：

| 规则 | 实现 |
|---|---|
| 指令按固定顺序拼接，每段带来源 | `segments.ts`，规则编号约束展开为语义包规则的处置建议 |
| 工具取并集，租户重名拒绝 | `capabilities.ts` 的 `buildTools`，工具来源展开为具体工具，挂了语义包的 Agent 自动获得语义查询工具 |
| 确认策略取最严 | `mergeApprovals`，租户放宽标品要求时报 `APPROVAL_RELAXED` |
| 钩子先标品后租户，租户失败隔离 | `hooks.ts`，`runHooks` 供运行时复用 |
| 模型档位限制在标品允许范围 | `resolveModel` |
| 扩展目标版本范围解析 | `assemble.ts`，取同时满足全部扩展范围的最高已发布版本，写入 `provenance.resolvedTarget` |
| 有效定义按查看者裁剪 | `view.ts`，依据片段的 `confidential` 标记 |

钩子和确认点的条件用 CEL，组装时按标品 Agent 的输出结构做类型检查（ADR 0003）。

### 注册中心

注册中心位于 `apps/platform/src/registry/`，数据存在 SQLite（better-sqlite3 + Drizzle），迁移文件在 `apps/platform/drizzle/`，启动时执行。业务逻辑只依赖 `AssetRepository` 和 `AuditStore` 两个接口，换 PostgreSQL 时替换实现即可。

资产按版本存储，同时保存作者提交的 YAML 原文和解析后的规范化 JSON（RFC 8785），内容哈希基于规范化 JSON 计算。各种操作的规则如下：

- 写入：只有本层身份能写本层命名空间，越层写入返回 403 并写审计。
- 发布：标品层和平台层的资产发布时用平台私钥做 Ed25519 签名。密钥在首次启动时生成，存放在 `data/keys/`。
- 读出：注册中心作为解析器给组装器读资产时，每次都核对内容哈希并验签，不符就拒绝加载并写审计。
- 读取接口：按查看者裁剪后返回，租户看不到其他租户的资产，也看不到其他层的草稿。

平台启动时把 `assets/platform` 和 `assets/vendor` 以本层身份发布入库，同版本同内容跳过，同版本不同内容报错（修改标品必须升版本号）。`assets/tenants/t001` 是演示完成态快照，启动时不加载。

HTTP 接口的请求校验和 OpenAPI 描述都从 zod 生成（`GET /openapi.json`），响应统一为 `{ success, data | error }` 信封。P1 的查看者身份暂时从请求头 `x-eab-viewer` 读取，P2 起改为平台签发的令牌。

| 接口 | 作用 |
|---|---|
| `GET /registry/assets` | 列出当前身份可见的资产版本 |
| `GET /registry/assets/:namespace/:name` | 读取资产（按身份裁剪），`?version=` 指定版本 |
| `PUT /registry/assets` | 提交 YAML，新建草稿版本 |
| `PATCH /registry/assets/:namespace/:name/:version/status` | 变更状态，标品发布时签名 |
| `POST /registry/validate` | 校验 YAML 并运行静态检查 |
| `GET /registry/agents/:namespace/:name/effective` | 有效定义预览 |
| `GET /audit/events` | 审计查询（P1 仅平台身份） |

`pnpm assets:validate` 和 `pnpm assets:assemble` 两个开发命令用内存数据库和临时密钥建一个注册中心，把仓库里全部资产按发布路径灌进去，然后做校验或打印有效定义，走的是和平台启动相同的代码路径。

## 技术选型

| 方面 | 选择 | 依据 |
|---|---|---|
| 后端框架 | Fastify 5 | ADR 0001 |
| 协议 SDK | A2A JS SDK 1.3.0（协议 v1.0，带 0.3 兼容层）、MCP TS SDK 1.32.1（协议 2025-11-25） | ADR 0002 |
| 前端 | React 19、Vite 8、Tailwind CSS 4、Radix 原语（shadcn/ui 风格）、TanStack Query、React Router | 主提示词第 14 节 |
| 测试 | Vitest 5、Playwright | 主提示词第 14 节 |
| TypeScript | 6.0 | typescript-eslint 8.x 目前支持到 6.0，暂不升级到 7.x |
| 资产 schema | zod 4（中文报错用自带的 zh-CN 语言包）、yaml 2 | 主提示词第 14 节 |
| 存储 | SQLite：better-sqlite3 13（npm 包内自带各平台预编译二进制，不需要编译工具链）+ Drizzle 0.45 | 主提示词第 14 节 |
| 条件表达式 | CEL，@marcbachmann/cel-js 8 | ADR 0003 |

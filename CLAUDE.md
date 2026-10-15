# CLAUDE.md

本仓库是 ERP Agent Builder 的开源概念演示：一个参照 SAP Joule Studio 产品形态、面向多租户 ERP 的 Agent 开发平台。完整需求见 `docs/brief/02-主提示词.md`，分阶段计划见 `docs/brief/03-分阶段提示词.md`，背景调研见 `docs/brief/01-Joule-Studio调研摘要.md`。需求有冲突时以主提示词为准。

## 常用命令

```
pnpm install          安装依赖
pnpm demo             启动全部服务，健康检查通过后打印地址（scripts/demo.mjs）
pnpm dev              开发模式，后端 tsx watch 热重载
pnpm test             单元测试（Vitest，根目录 vitest.config.ts 收集 apps/*、packages/* 下的 *.test.ts(x)）
pnpm test:e2e         Playwright 端到端测试，自动拉起或复用演示环境；首次需 pnpm exec playwright install chromium
pnpm lint             ESLint
pnpm typecheck        全部包的 tsc 检查，以及 e2e 目录
pnpm format           Prettier
pnpm test:coverage    带覆盖率的单元测试
pnpm assets:validate  校验 assets/ 下全部资产：schema、静态检查、试组装
pnpm assets:assemble vendor.fin/transaction-anomaly-analyst --tenant t001 --as tenant|vendor
                      打印有效定义，租户视角下标品指令为占位块；加 --json 输出 JSON
pnpm spec:json-schema 修改 packages/spec 的 schema 后重新生成 JSON Schema（有测试检查同步）
```

pnpm seed:reset       重置演示数据：DemoERP 重新生成，平台清空审计、事件、委托（资产与密钥保留）

以下命令在对应阶段加入：`pnpm agent:run`（P3）、`pnpm eval:smoke`（P8）。

本机只有独立安装的 `docker-compose`（没有 `docker compose` 插件），容器方式启动用 `docker-compose up --build`。

## 工程约定

- 各包以 TypeScript 源码为入口（`exports` 指向 `src/index.ts`），不预先构建；服务用 tsx 运行。
- 后端服务用 `buildApp()` 构造 Fastify 实例，测试用 `app.inject()`，不监听端口。
- 前端只访问同源的 `/api`（平台）和 `/erp`（DemoERP），由 Vite 代理转发。
- 资产来源的颜色、图标、写级别和裁决标签统一从 `@eab/ui` 取，不在页面里另写颜色；Tailwind 类名不要动态拼接。
- TypeScript 固定在 6.0.x，typescript-eslint 目前不支持 7.x。
- better-sqlite3 13 的 npm 包自带预编译二进制，不要把它加进 `pnpm.onlyBuiltDependencies`，否则 pnpm 会尝试用 node-gyp 编译。
- zod 4 的 refine 在前一个校验失败后仍会继续执行，refine 里不要调用会抛异常的函数（例如 `parseAssetRef`），先用 `isAssetRef` 判断。
- 测试里的资产用 `@eab/spec/testing` 的构造器生成，不手写整段 YAML。
- 平台接口的身份只来自 Bearer 用户令牌；测试用 `apps/platform/src/test-support.ts` 的 `createTestServices`、`loginAll`。
- 平台调用 BO API 只能走 `callBoApi`（先令牌交换再调用），不要直接请求 DemoERP，也不要引入集成账号的降级路径。
- 演示用户、角色、数据范围只在 `packages/demo-directory` 定义。新增 BO 操作时，同步修改 `BO_OPERATIONS`、DemoERP 路由和 `assets/vendor/erp/demo-erp-bo.yaml`（并升版本号），有测试检查三者一致。
- 标品资产改动必须升版本号：同版本不同内容在启动入库时会报错。

## 架构要点

- 资产 schema 的唯一来源是 `packages/spec`，前端、后端、CLI、Studio MCP Server 都从这里导入，不要另写一份类型。
- 运行时组装规则在 `packages/assembler`，修改前先读主提示词 5.4 节，改完必须补测试。
- 鉴权逻辑只放在 `packages/policy` 和 DemoERP 的 BO API 层，不要散落在业务代码里。
- 身份透传失败一律拒绝，任何情况下都不能退回到技术账号。
- 写操作级别、SoD、金额阈值这类硬约束写在工具定义、守卫和 BO API 里，不写在提示词里。
- 资产里只引用连接别名，不出现地址和凭据。
- 三层资产的可见性：租户视角永远看不到标品指令原文，包括在追踪、有效定义预览、版本差异里。

## 工作约定

- 按阶段推进。每个阶段结束时：测试通过、`pnpm demo` 可运行、更新 `docs/`、提交一次。提交信息用中文，说明本阶段完成了什么。
- 用到 A2A、MCP 及其官方 SDK 时，先查官方最新文档，不凭记忆写 API。选用的版本记录在 `docs/decisions/`。
- 遇到需求未覆盖的问题，按主提示词第 20 节的默认决策处理；仍不确定的，写 ADR 后继续，不要停下来等。
- 新增演示话术时，同步更新 `demo/scripts/` 下的脚本化模型剧本，保证断网可演示。
- 界面文案、文档、演示数据用中文；代码标识符、目录名、API 路径用英文。
- 不在代码、数据、文档里出现真实公司信息、真实人名和密钥；不使用 SAP 的商标、Logo 和产品名作为本系统的名字。

## 文档写作风格

`docs/` 下的文档面向架构师和管理者，用平实的中文直接描述系统本身：少用破折号和粗体，不用"标签：内容"式的内联小标题，不写"不是 X 而是 Y"式的排比和口号式标题，不用"业界普遍认为"这类模糊归因。

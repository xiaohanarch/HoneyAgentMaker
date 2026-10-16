# P6、P7、P8 并行开发约定

P6、P7、P8 三个阶段同时开发，各在独立的 git worktree 和分支里进行，完成后依次合入 main（顺序 P7、P6、P8），合并后统一部署验收。本文约定三个分支之间的分工和共享资源的用法，目的是让合并时的冲突尽量少、尽量机械。阶段需求以 `docs/brief/03-分阶段提示词.md` 和主提示词为准，工程约定以 CLAUDE.md 为准。

## 分支与基线

| 阶段 | 分支 | 基线 |
|---|---|---|
| P6 扩展标品 Agent、工具中心、技能编辑器 | `feat/p6-extend-tools-skills` | main b86bc1c 之后提交本文的版本 |
| P7 A2A 双向互通与外部 Agent | `feat/p7-a2a` | 同上 |
| P8 治理中心、评测、生命周期、升级回归、经验回流 | `feat/p8-governance-eval` | 同上 |

三个分支互不合并，也不推送、不部署、不建 PR，由主会话负责。

## 预先分配的编号与端口

| 资源 | P6 | P7 | P8 |
|---|---|---|---|
| 平台 Flyway 迁移 | V5（需要多个时用 V5_1、V5_2） | V6（V6_1…） | V7（V7_1…） |
| DemoERP Flyway 迁移（`db/erp`） | V2 | V3 | V4 |
| ADR 编号 | 0011 | 0012（另外更新 0002 的 A2A、MCP 版本） | 0013 |
| 验收脚本 | `scripts/acceptance-p6.mjs` | `scripts/acceptance-p7.mjs` | `scripts/acceptance-p8.mjs` |
| Playwright | `e2e/extend.spec.ts` | `e2e/a2a.spec.ts` | `e2e/hub.spec.ts` |
| 演示剧本 | `demo/scripts/story-b.yaml` | `demo/scripts/story-c.yaml` | `demo/scripts/story-df.yaml` |
| 端口 | 无 | 4200 供应商风险舆情（Python）、4210 汇率（TS）、4400 物流 MCP、4220 client-demo（如需要） | 无 |

端口 4200 和 4400 已经写在租户连接资产里（`assets/tenants/t001/connections`），P7 按它们实现。

## 代码的归属

每个阶段把新代码放在自己的包和目录里：

| | P6 | P7 | P8 |
|---|---|---|---|
| 后端 | `dev.eab.platform.extend`、`dev.eab.platform.tools`、`dev.eab.platform.skills` | 新模块 `backend/eab-a2a`，平台里的 `dev.eab.platform.a2a` | 新模块 `backend/eab-eval`，平台里的 `dev.eab.platform.hub`、`dev.eab.platform.lifecycle`、`dev.eab.platform.feedback` |
| 前端 | `apps/studio-web/src/studio/extend/`、`src/studio/tools/`、`src/studio/skills/` | `src/studio/external/` | `src/hub/`，以及 `pages/HubPage.tsx` |
| 其他 | | `external-agents/`、Java 代码 Agent SDK（主提示词里的“TypeScript SDK”按 ADR 0006 改为 Java SDK） | 生成历史数据的脚本 |

Spring 装配：每个阶段新建自己的 `@Configuration` 类（例如 `ExtendConfig`、`A2aConfig`、`HubConfig`），不改 `PlatformConfig`。确实要改 `PlatformConfig` 的，改动要小，并在 PR 说明里写明。

## 共享文件的改法

下面这些文件三个分支都可能改。规则是只做增量、尽量少改，合并时由主会话处理冲突：

- `apps/studio-web/src/App.tsx`：每个路由一行，加在 `{ path: 'hub', ... }` 之前，不调整已有路由。
- `apps/studio-web/src/pages/StudioPage.tsx` 的入口卡片：P6 接上“扩展标品 Agent”“新建技能”“导入工具”，P7 接上“接入外部 Agent”，只改自己的卡片。
- `apps/studio-web/src/assist/api.ts`：只加新函数，不改已有函数的签名。
- `docs/architecture.md`、`docs/concepts.md`：各自在文末“技术选型”一节之前加自己的一节（标题带阶段号），不改其他章节。
- `CLAUDE.md`、`README.md`：不在分支里改，把需要增加的内容写进完成报告，由主会话合并。
- `scripts/demo.mjs`、`deploy/`、`docker-compose.yml`：只有 P7 改（启动外部 Agent）。
- `backend/pom.xml`（父工程的 modules 列表）：P7 加 `eab-a2a`，P8 加 `eab-eval`，各加一行。
- `schemas/*.schema.json`：改了 eab-spec 的 schema 后照常运行 `pnpm spec:json-schema`，合并时再重新生成一次。

## 接口与资产的约定

评测引擎归 P8：

- P8 可以把评测相关代码移到 `backend/eab-eval`，并补上 Given/When/Then 对话测试、冒烟集与完整集分层、最低通过率。但必须保留 `dev.eab.platform.studio.EvalRunner` 的公开接口（`CaseRun`、`CaseResult`、`AssertionResult`、`Judge`、`DETERMINISTIC`、`model(...)`、`check(...)`）和 `StudioService.runEvals`、`EvalRunStore` 的行为，可以改成委托给新模块。
- P6 的“发布前强制跑标品公开评测集加扩展评测集”只调用上面这些接口，不改评测引擎的内部实现。

工具网关（`dev.eab.runtime.ToolGateway`）归 P7：

- P7 实现 remoteAgent 工具类型（A2A 出站），以及外部 Agent 停机时的降级提示。
- P6 的“MCP Server 登记与审批状态”在注册中心、静态检查和晋级门禁里实现（未审批的 MCP Server 不能用在 prod），不改工具网关。

标品资产的版本：

- `vendor.fin/transaction-anomaly-analyst` 的升级（故事 F，例如 1.2.0 升到 1.3.0）由 P8 负责，P6 不改这个资产。P6 的故事 B 只改租户扩展 `assets/tenants/t001/finance/anomaly-analyst-ext.yaml`，以及它的评测集。
- 其他标品资产需要改动时，在完成报告里说明。CLAUDE.md 的规则照常适用：标品资产改动必须升版本号。
- 远程 Agent 资产（故事 C 的供应商风险舆情 Agent）由 P7 新增。P5 向导里“P7 接入后可调用”的提示，以及 `StudioCatalog` 里由连接别名生成的候选，由 P7 改成真实可用的远程 Agent。

资产 schema（`backend/eab-spec`）的改动只能新增可选字段，不能改已有字段的含义。

## 构建与测试

- Maven 只用 reactor 构建（`-pl <模块> -am`），不要执行 `mvnw install`。`~/.m2` 是共享的，并行 install 会互相覆盖 jar。
- 不要运行 `pnpm demo`，也不要对固定端口跑 Playwright：三个 worktree 会争用 4000、4100、5173 端口。后端用随机端口的端到端测试（参照 `StudioEndToEndTest`、`AssistEndToEndTest`）；Playwright 用例和验收脚本照常写好，由主会话合并后统一运行。
- 每个分支完成时需要通过：`mvnw -B verify`（reactor，覆盖率不低于 80%）、`npx vitest run`、`pnpm lint`、`pnpm typecheck`、`pnpm assets:validate`。
- 前端用 Vitest 测试纯逻辑；界面的走查由主会话在合并后完成。

## 完成报告

每个分支完成时提交到自己的分支，并在报告里写明：

1. 分支名、最后的提交、改动的文件；
2. 对共享文件的改动；
3. 需要追加到 CLAUDE.md、README 的内容；
4. 与主提示词、阶段要求的偏差，以及留下的问题；
5. 需要主会话在合并后手动验证的事项（Playwright、验收脚本、真实模型、主机上需要安装的软件）。

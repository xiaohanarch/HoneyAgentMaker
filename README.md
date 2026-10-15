# ERP Agent Builder

一个面向多租户 ERP 的 Agent 开发平台的开源概念演示。

ERP 厂商的研发团队用它开发随产品交付的标品 Agent；租户和生态伙伴用它在标品之上扩展 Agent、从一句话意图新建 Agent，或者把用其他框架开发的 Agent 经 A2A 接进来。最终用户只面对一个统一入口。演示的目标是让架构师和管理者在 15 分钟内看懂这类产品怎么用、背后的机制是什么，核心机制（三层资产组装、身份透传与四处鉴权、A2A/MCP 互通、评测门禁）都有真实实现，可以作为自建平台的参考原型。

## 声明

本项目与 SAP 没有任何关系，也没有得到 SAP 的认可或授权。产品形态参照了 SAP Joule Studio 的公开资料，系统名称、界面、代码均为独立设计和实现，不使用 SAP 的商标、Logo 和产品名作为本系统的名字。演示中的公司、人名、业务数据全部是虚构的。

## 快速开始

需要 Node.js 22 或更新版本、pnpm 9，以及 JDK 21（后端为 Java，见 docs/decisions/0006）。Maven 由仓库内的 Maven Wrapper 自动下载。演示默认使用脚本化模型，不需要任何大模型 API Key，断网也能运行。

```bash
pnpm install
pnpm demo
```

启动完成后终端会打印各服务地址，浏览器打开 http://localhost:5173 。顶部可以在统一入口、Studio、治理中心之间切换，右上角切换演示身份。

也可以用 Docker：

```bash
docker compose up --build      # 独立安装的旧版 Compose 用 docker-compose up --build
```

## 服务与端口

| 服务 | 端口 | 说明 |
|---|---|---|
| platform | 4000 | 平台主服务：资产注册与组装、路由、Agent 运行时、模型网关、MCP 网关、A2A 网关、治理中心 API、模拟身份服务 |
| demo-erp | 4100 | 模拟 ERP：BO API、Open API、业务事件、元数据服务 |
| studio-web | 5173 | 前端，包含统一入口、Studio、治理中心三个入口；`/api` 转发到 platform，`/erp` 转发到 demo-erp |

外部 Agent 样例（4200、4300）和第三方 MCP Server 样例（4400）在后续阶段加入。

## 常用命令

| 命令 | 作用 |
|---|---|
| `pnpm demo` | 启动全部服务，等健康检查通过后打印地址 |
| `pnpm dev` | 同 `pnpm demo`，前端热更新 |
| `pnpm test` | 单元测试（Vitest 和 Maven） |
| `pnpm test:e2e` | 端到端测试（Playwright），会自动拉起演示环境 |
| `pnpm lint` | ESLint 和 Spotless |
| `pnpm typecheck` | 前端的 TypeScript 类型检查 |
| `pnpm seed:reset` | 把演示数据重置到初始状态 |
| `pnpm assets:validate` | 校验 `assets/` 下全部资产 |
| `pnpm format` | Prettier 与 Spotless 格式化 |

首次运行端到端测试前执行一次 `pnpm exec playwright install chromium`。

## 目录结构

```
backend/       Java 后端（Maven 多模块）：eab-spec、eab-assembler、eab-policy、eab-directory、eab-common，应用 platform、demo-erp
apps/          前端 studio-web
packages/      前端共用的视觉规范 ui
schemas/       由资产 schema 导出的 JSON Schema
assets/        平台层、标品层的声明式资产（YAML），以及演示租户的完成态快照
external-agents/  外部 Agent 与 MCP Server 样例
demo/          脚本化模型剧本、种子数据、演示讲解词
docs/          架构说明、术语、ADR；docs/brief/ 是项目的需求输入材料
e2e/           Playwright 端到端测试
```

## 文档

- 需求说明：`docs/brief/02-主提示词.md`
- 分阶段计划：`docs/brief/03-分阶段提示词.md`
- 架构说明：`docs/architecture.md`
- 架构决策记录：`docs/decisions/`

## 许可证

Apache-2.0，见 `LICENSE`。

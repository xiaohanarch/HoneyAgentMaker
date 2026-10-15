# CLAUDE.md

本仓库是 ERP Agent Builder 的开源概念演示：一个参照 SAP Joule Studio 产品形态、面向多租户 ERP 的 Agent 开发平台。完整需求见 `docs/brief/02-主提示词.md`，分阶段计划见 `docs/brief/03-分阶段提示词.md`，背景调研见 `docs/brief/01-Joule-Studio调研摘要.md`。需求有冲突时以主提示词为准。

## 常用命令

```
pnpm install          安装依赖
pnpm demo             启动全部服务（脚本化模型，无需 API Key）
pnpm dev              开发模式
pnpm test             单元测试
pnpm test:e2e         Playwright 端到端测试（六个演示故事）
pnpm eval:smoke       冒烟评测集，必须 3 分钟内完成
pnpm seed:reset       重置演示数据到初始状态
pnpm lint && pnpm typecheck
```

（P0 阶段建好脚本后，按实际情况更新这一节。）

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

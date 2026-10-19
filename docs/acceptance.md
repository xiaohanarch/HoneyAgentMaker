# 验收清单

对照 `docs/brief/02-主提示词.md` 第 18 节的八条验收标准逐条自查，每条给出状态和证据。文末是复核记录，最后一次复核是 P10 收尾（本地全套加 ECS 主机各跑一遍）。

## 1. 一条命令拉起全部服务

状态：通过。

`pnpm install && pnpm demo` 不需要任何大模型 API Key：默认是脚本化模型，剧本在 `demo/scripts/`。`scripts/demo.mjs` 在 jar 比源码旧时先用 Maven Wrapper 打包，再并行启动各服务并轮询健康检查，全部就绪后打印访问地址；外部样例作为可选服务，失败不影响主流程，Python 样例由 uv 按锁文件自动安装依赖。Playwright 的 webServer 配置直接复用 `pnpm demo`（`playwright.config.ts`），端到端测试每次都从这条命令冷启动，等同于持续回归。

## 2. 真实模型下故事 A、B、C、E 跑通

状态：通过。

切换真实模型只改环境变量（`EAB_MODEL_PROVIDER` 与对应的 Key），界面操作路径不变。ADR 0009 记录了经 OpenAI 兼容协议接入真实模型后四种交互模式的验证，以及路由决策、推理输出上限、超时重试三个问题的修复过程。ECS 主机长期以真实模型运行演示环境，阶段验收脚本在主机上抽样通过，断言只看工具调用和约束触发，不比对回复文字；本地与端到端测试用脚本化模型，断网可跑。`DemoScriptsTest` 检查剧本可加载、示例话术有剧本，保证两边的行为对齐。

## 3. 六个故事各有 Playwright 端到端测试

状态：通过。

| 故事 | spec | 覆盖 |
| --- | --- | --- |
| A | `e2e/studio.spec.ts` | 一句话意图、十三步向导、评测、私有环境测试、发布晋级到 prod、张敏在统一入口使用 |
| B | `e2e/extend.spec.ts` | 扩展编辑、有效定义预览（标品指令占位块）、评测发布、王芳确认执行 |
| C | `e2e/a2a.spec.ts` | 读取 Agent Card、连通测试、发布、出站调用、入站无身份被拒绝与审计 |
| D | `e2e/story-d.spec.ts` | D-1 到 D-5：数据范围对照、集成账号对照、L2 确认与 SoD 拒绝、委托撤销后事件被拒、审计反查 |
| E | `e2e/requests.spec.ts` | 缺口卡提交需求、生成方案、采纳草稿、发布交付、张敏重新提问拿到请购单草稿 |
| F | `e2e/story-f.spec.ts` | F-1 到 F-3：升级回归失败可见、经验回流改写结论、架构视图回放点亮组件 |

全套 `pnpm test:e2e` 串行执行（workers: 1），spec 之间的状态衔接按文件名字典序设计，`demo-console` 与 `story-f` 以重置收尾，后续 spec 拿到首次启动状态。

## 4. 核心模块有单元测试

状态：通过。

组装器（`eab-assembler`：AssemblerTest、CapabilitiesAndHooksTest，覆盖主提示词 5.4 节七条规则）、策略模块（`eab-policy` PolicyEngineTest 加平台的 AssistRulesTest）、路由（`eab-runtime` routing 包 RouterTest）、令牌交换（platform identity 的 IdentityServiceTest、EndToEndTest）、评测引擎（`eab-eval` EvalEngineTest）都有单元测试，运行时（AgentRuntimeTest、ModelGatewayTest、ConstraintGuardTest）、追踪（TracingTest）、注册中心（RegistryServiceTest、SigningTest）同样覆盖。`pnpm test:java` 走 Maven Wrapper 的 verify，JUnit 5 加 AssertJ 加 MockMvc，JaCoCo 出覆盖率报告，全部后端测试类共 60 余个。

## 5. 冒烟评测集 3 分钟内跑完

状态：通过。

`pnpm eval:smoke` 在进程内启动平台与 DemoERP，逐个运行该租户可见的评测集（冒烟档），不需要先起演示环境。计时结果见文末复核记录。

## 6. 1280 宽度下无横向滚动

状态：通过。

`e2e/shell.spec.ts` 在 1280 宽下对全部可达路由断言没有横向滚动：统一入口、IM 渠道、Studio 各页面、治理中心全部标签页、架构视图。深色模式同样检查。界面在 1280 与 1440、浅色与深色四种组合下截图审读过。

## 7. 文档与 ADR

状态：通过。

`docs/architecture.md` 有五层两翼的架构图（「总体架构」节，与前端架构视图同一组件清单）和逐模块说明；`docs/concepts.md` 收录全部术语；`demo/DEMO.md` 是逐步讲解词（每个故事一张表：点哪里、讲解词、观众看到什么、对应能力）；`docs/joule-mapping.md` 列出与 Joule Studio 的能力对应。`docs/decisions/` 共 15 篇 ADR，本条要求的四篇对应：协议版本（0002）、后端框架（0006）、向量检索方案（0009，BM25 先行、`Router.Retriever` 预留嵌入检索）、标品指令可见性（0004）。

## 8. 无真实信息与密钥，声明与 SAP 无关

状态：通过。

README「声明」一节写明本项目与 SAP 没有关系、是参照公开资料的概念演示；演示里的公司、人名、业务数据全部虚构。密钥只从环境变量读取，真实模型配置放在仓库外的 `~/.config/eab/model.env`（部署主机是 `/home/eab/.config/eab/model.env`），不进仓库、不进数据快照；资产里只允许写连接别名，`eab-spec` 的静态检查（Lint）拦截硬编码地址和凭据，`pnpm assets:validate` 每次全量校验。仓库内按常见密钥模式扫描无命中。

## 复核记录

- 2026-10-19，本地：`pnpm lint`、`pnpm typecheck`、`pnpm test`（Vitest 加 Maven verify）、`pnpm test:e2e` 全量、`pnpm eval:smoke` 计时、`pnpm assets:validate`、重置后 `pnpm demo` 加 `scripts/acceptance-p2.mjs` 到 `acceptance-p10.mjs` 与 `acceptance-procode.mjs` 逐个通过，`scripts/demo-walkthrough.mjs` 整段走查通过（脚本化模型，含 D-1 两个能力示例相近时路由先澄清、按讲解词点选后重问）。冒烟评测合计用时 10.4 秒（预算 180 秒，含进程启动整条命令 34 秒）。
- 2026-10-19，ECS 主机：部署 feat/p10-demo-docs（3a85b3b）时在 Linux 上重跑单元测试、lint、typecheck、资产校验与 Maven verify，全量端到端 39 项通过，部署后恢复真实模型配置、健康检查通过并留存数据快照。真实模型下按 `scripts/demo-walkthrough.mjs` 的检查项走查 `demo/DEMO.md` 主线（分故事 D、故事 F 两段执行，断言只看路由、工具调用、约束触发与审计裁决）：控制台重置与委托重建，故事 D 的同问按数据范围过滤（华东、华南）、集成账号对照、交期变更经 L2 确认提交变更单、审批本人变更单被职责分离拒绝、审计反查定位运行，故事 F 的升级回归暴露扩展钩子用例失败并通知管理员（真实模型下单次约 9 分钟）、经验回流经审核写入租户语义包后重新提问生效、架构视图回放数据就绪，全部通过。随后切脚本化模型窗口重跑 `scripts/acceptance-p10.mjs` 与抽样 p2、p4、p8（p8 依赖的扩展由故事 F 起点快照就位），收尾 `pnpm seed:reset` 并恢复真实模型，复核后的数据快照拉回本地。

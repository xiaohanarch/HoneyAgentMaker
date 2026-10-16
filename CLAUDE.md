# CLAUDE.md

本仓库是 ERP Agent Builder 的开源概念演示：一个参照 SAP Joule Studio 产品形态、面向多租户 ERP 的 Agent 开发平台。完整需求见 `docs/brief/02-主提示词.md`，分阶段计划见 `docs/brief/03-分阶段提示词.md`，背景调研见 `docs/brief/01-Joule-Studio调研摘要.md`。需求有冲突时以主提示词为准。

## 常用命令

```
pnpm install          安装依赖
pnpm demo             启动全部服务，健康检查通过后打印地址（scripts/demo.mjs）；jar 比源码旧时先打包
pnpm dev              同 pnpm demo；前端由 Vite 热更新，后端改动后重新运行
pnpm test             前端单元测试（Vitest，apps/*、packages/* 下的 *.test.ts(x)）加 pnpm test:java
pnpm test:java        Java 后端 mvnw verify（JUnit 测试与 JaCoCo 覆盖率报告）
pnpm test:e2e         Playwright 端到端测试，自动拉起或复用演示环境；首次需 pnpm exec playwright install chromium
pnpm lint             ESLint 与 Spotless 检查（Java 格式为 palantir-java-format）
pnpm typecheck        前端各包的 tsc 检查，以及 e2e 目录
pnpm format           Prettier 与 Spotless 格式化
pnpm test:coverage    带覆盖率的前端单元测试
pnpm assets:validate  校验 assets/ 下全部资产：schema、静态检查、试组装
pnpm assets:assemble vendor.fin/transaction-anomaly-analyst --tenant t001 --as tenant|vendor
                      打印有效定义，租户视角下标品指令为占位块；加 --json 输出 JSON
pnpm spec:json-schema 修改 eab-spec 的 schema 后重新生成 schemas/ 下的 JSON Schema（有测试检查同步）
pnpm seed:reset       重置演示数据：DemoERP 重新生成，平台清空审计、事件、委托（资产与密钥保留）
pnpm data:backup      数据快照：data/backups/eab-data-<时间>.tar.gz，保留最近 10 份
pnpm data:simulate    生成 30 天演示历史（运行、流程与业务指标），供治理中心展示趋势
pnpm eval:smoke [--tier full] [--tenant t001] [--verbose]
                      进程内启动平台与 DemoERP，逐个运行该租户可见的评测集，冒烟集 3 分钟内跑完
pnpm agent:run vendor.pur/supplier-performance-analyst --user zhangmin "百润精密最近表现怎么样" [--yes]
                      进程内启动平台与 DemoERP 跑一次 Agent，打印流式事件和追踪树，断网可用
pnpm tools:import-bo  从运行中的 DemoERP 的 OpenAPI 生成 BO 工具入参 schema 并与资产比对；加 --write 写回并升版本
node scripts/acceptance-p2.mjs … acceptance-p8.mjs   对运行中的 pnpm demo 做阶段验收（只用 HTTP）
pnpm --filter @eab/client-demo run start   模拟外部平台经 A2A 调用平台的采购助手（先演示缺少身份被拒绝）
cd external-agents/supplier-risk-py && uv sync && uv run pytest -q   Python 外部 Agent 的测试
```

资产、数据类命令经 `scripts/java-cli.mjs` 运行 Java 命令行工具（`platform`、`demo-erp` 模块的 `cli` 包）。

模型提供方由环境变量决定：`EAB_MODEL_PROVIDER` 取 scripted（默认）、anthropic、openai；Key 只从环境变量读取（`ANTHROPIC_API_KEY`、`OPENAI_API_KEY` 加 `EAB_OPENAI_BASE_URL`），档位映射 `EAB_MODEL_FAST/REASONING/JUDGE`，单次输出上限 `EAB_MODEL_MAX_TOKENS`（默认 16384，推理模型的推理也计入），超时 `EAB_MODEL_TIMEOUT_SECONDS`（默认 300）与重试 `EAB_MODEL_MAX_RETRIES`（默认 1）。本机的真实模型配置放在仓库外的 `~/.config/eab/model.env`，主机上是 `/home/eab/.config/eab/model.env`，都不进仓库和数据快照。

本机只有独立安装的 `docker-compose`（没有 `docker compose` 插件），容器方式启动用 `docker-compose up --build`。

## 工程约定

- 后端是 Java（ADR 0006）：`backend/` 是 Maven 多模块工程（Spring Boot 4.1、Spring AI 2.0、JDK 21），包名 `dev.eab.*`。前端 `apps/studio-web` 和 `packages/ui` 仍是 TypeScript。对外 HTTP 契约（路径、响应信封、端口、令牌、环境变量名）与迁移前一致。
- Java 命令都在 `backend/` 下用 Maven Wrapper 执行（Windows 用 `mvnw.cmd`），或经 `node scripts/mvnw.mjs <参数>` 调用；JAVA_HOME 指向 JDK 21。可执行 jar 是 `target/<模块>-exec.jar`。
- Java 数据访问用 `JdbcClient` 加仓储接口，不用 JPA；测试用 JUnit 5、AssertJ 和 MockMvc，测试名用 `@DisplayName` 写中文描述。
- 数据库迁移用 Flyway：平台在 `classpath:db/migration`，DemoERP 在 `classpath:db/erp`，两处分开，两个应用才能在同一个测试进程里启动。数据目录默认是仓库根目录下的 `data/`；迁移到 Java 之前建的库在首次打开时自动改名归档（`*.pre-flyway-<时间>`）。
- 资产 schema 的唯一来源是 `backend/eab-spec`：`kinds/` 下是 schema 定义（校验、默认值、JSON Schema），`model/` 下是对应的记录类型，两处一起改（ADR 0007）。测试里的资产用 `dev.eab.spec.testing.Fixtures` 构造，不手写整段 YAML。
- 平台测试用 `TestPlatform.services()` 建内存库的服务，用 `TestPlatform.mvc()` 发请求（不启动 Spring 容器），用 `TestPlatform.bearer()` 取用户令牌。DemoERP 测试用 `TestErp.create()`，令牌用 `TestErp.delegatedToken` 等方法本地签发。平台与 DemoERP 的联调测试是 `EndToEndTest`。
- Java 进程输出中文时加 `-Dstdout.encoding=UTF-8`，否则 Windows 控制台会乱码。
- Ed25519 令牌校验按 kid 选出 OKP 公钥后用 `Ed25519Verifier` 直接验签；Nimbus 的通用 `JWSVerificationKeySelector` 不支持 OKP 密钥。
- DemoERP 的种子数据必须与 `seed-golden.json.gz` 逐条一致（剧本和验收脚本依赖它），改生成逻辑时同步更新黄金样本并说明原因。
- 运行时（ADR 0008）：模型调用用 Spring AI 的 `ChatClient`，必须关闭工具调用 Advisor 的自动注册，工具一律由平台的 `ToolGateway` 执行（鉴权点 3、守卫、确认）。新增工具类型时在 `ToolGateway.execute` 和 `inputSchema` 里各加一个分支。
- 挂起与恢复靠运行日志重放（`Journal`）：会产生副作用或不确定结果的步骤（模型调用、工具调用、鉴权裁决）都要经 `journal.record` 按结构化路径记录，span 用 `Exec.span` 开，路径与日志一致。循环里不要引入日志之外的不确定输入。
- 演示话术改动时同步 `demo/scripts/*.yaml` 和对应 Agent 的 `routing.examples`（并升 Agent 版本）；回复里的单号和金额必须从工具结果渲染，`DemoScriptsTest` 检查剧本可加载、示例话术有剧本。
- BO 操作的参数改动后运行 `BoToolSourceSyncTest`（加 `-Deab.updateBoTools=true` 写回）或 `pnpm tools:import-bo --write`，保持 `demo-erp-bo.yaml` 的入参 schema 与 OpenAPI 一致。
- 前端只访问同源的 `/api`（平台）和 `/erp`（DemoERP），由 Vite 代理转发。
- 资产来源的颜色、图标、写级别和裁决标签统一从 `@eab/ui` 取，不在页面里另写颜色；Tailwind 类名不要动态拼接。
- TypeScript 固定在 6.0.x，typescript-eslint 目前不支持 7.x。
- 平台接口的身份只来自 Bearer 用户令牌。
- Studio（ADR 0010）：草稿在 `studio_drafts` 里以 YAML 为准，测试与评测经 `OverlayResolver` 叠加运行；发布写入注册中心后按 dev → test → prod 晋级，租户 Agent 部署到 prod 才进入统一入口。接口契约在 `docs/studio-api.md`，改接口时同步改它和 `apps/studio-web/src/studio/api.ts`。
- 租户扩展（ADR 0011）发布即生效，不经环境晋级；发布前标品公开评测集和扩展评测集都要通过，评测时确认点一律拒绝。未审批的租户 MCP Server 不能用于 prod，由 `McpGate` 在 prod 门禁和扩展发布时检查，不在工具网关里检查。
- 演示剧本可以用 `match.tools` 要求 Agent 挂载了某些工具才匹配（优先于不带工具条件的剧本），故事 B 靠它区分扩展发布前后。
- A2A（ADR 0012）：`pnpm demo` 同时启动外部样例（4200 供应商风险舆情 Python、4210 汇率 TS、4400 物流 MCP），它们是可选服务，起不来不影响演示；Python 样例需要 uv（`python -m pip install --user uv`），依赖自动安装。远程 Agent 资产放在 `tenant.<租户>.remote`；出站 A2A 调用只走 `RemoteAgentGateway`，每次携带最终用户的短期服务令牌，没有用户身份时拒绝，不退回技术账号；对外暴露要求资产声明 `{type: agent}` 触发器。
- 语义规则的 `check`（工具名 + CEL）由约束守卫在工具返回后判断，硬约束优先写成可检查的规则，而不是只写在指令里。
- 平台调用 BO API 只能走 `DelegatedClient.callBoApi`（先令牌交换再调用），不要直接请求 DemoERP，也不要引入集成账号的降级路径。
- 演示用户、角色、数据范围只在 `backend/eab-directory` 的 `DemoDirectory` 定义。新增 BO 操作时，同步修改 `BO_OPERATIONS`、DemoERP 的 `BoTypes.OPERATIONS` 与路由、`assets/vendor/erp/demo-erp-bo.yaml`（并升版本号），有测试检查它们一致。
- 标品资产改动必须升版本号：同版本不同内容在启动入库时会报错。

## 架构要点

- 资产 schema 的唯一来源是 `backend/eab-spec`，平台、命令行和后续的 Studio MCP Server 都从这里取，不要另写一份类型。前端只用到命名空间分层规则（`packages/ui/src/layers.ts`）。
- 运行时组装规则在 `backend/eab-assembler`，修改前先读主提示词 5.4 节，改完必须补测试。
- Agent 运行时在 `backend/eab-runtime`（Agent 循环、工具网关、技能与工作流、监督层、模型网关），平台里的 `RuntimeService` 把它的端口接到注册中心、令牌交换和审计上。
- 鉴权逻辑只放在 `backend/eab-policy`、平台的身份服务和 DemoERP 的 BO API 层（`BoAuthorizer`），不要散落在业务代码里。
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

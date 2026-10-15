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
```

资产、数据类命令经 `scripts/java-cli.mjs` 运行 Java 命令行工具（`platform`、`demo-erp` 模块的 `cli` 包）。

以下命令在对应阶段加入：`pnpm agent:run`（P3）、`pnpm eval:smoke`（P8）。

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
- 前端只访问同源的 `/api`（平台）和 `/erp`（DemoERP），由 Vite 代理转发。
- 资产来源的颜色、图标、写级别和裁决标签统一从 `@eab/ui` 取，不在页面里另写颜色；Tailwind 类名不要动态拼接。
- TypeScript 固定在 6.0.x，typescript-eslint 目前不支持 7.x。
- 平台接口的身份只来自 Bearer 用户令牌。
- 平台调用 BO API 只能走 `DelegatedClient.callBoApi`（先令牌交换再调用），不要直接请求 DemoERP，也不要引入集成账号的降级路径。
- 演示用户、角色、数据范围只在 `backend/eab-directory` 的 `DemoDirectory` 定义。新增 BO 操作时，同步修改 `BO_OPERATIONS`、DemoERP 的 `BoTypes.OPERATIONS` 与路由、`assets/vendor/erp/demo-erp-bo.yaml`（并升版本号），有测试检查它们一致。
- 标品资产改动必须升版本号：同版本不同内容在启动入库时会报错。

## 架构要点

- 资产 schema 的唯一来源是 `backend/eab-spec`，平台、命令行和后续的 Studio MCP Server 都从这里取，不要另写一份类型。前端只用到命名空间分层规则（`packages/ui/src/layers.ts`）。
- 运行时组装规则在 `backend/eab-assembler`，修改前先读主提示词 5.4 节，改完必须补测试。
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

# 架构说明

本文随阶段推进逐步补全。完整的目标架构见 `docs/brief/02-主提示词.md` 第 4 节，本文只描述已经实现的部分。

## 工程结构（P0）

仓库是单仓，由三部分组成：`backend/` 下是 Java 后端（Maven 多模块工程，Spring Boot 4.1、Spring AI 2.0、JDK 21，见 ADR 0006），`apps/studio-web` 和 `packages/ui` 是 TypeScript 前端（pnpm workspace），`assets/` 下是资产 YAML。

后端有两个可运行的应用。`platform`（端口 4000）是平台主服务，`demo-erp`（端口 4100）是模拟 ERP，都是 Spring MVC 应用，运行在虚拟线程上。测试不启动 Spring 容器：服务对象由 `PlatformServices`、`ErpServices` 直接构造，控制器挂到独立的 MockMvc 上发请求。`studio-web`（端口 5173）是 React + Vite 前端，按主提示词第 20 节的默认决策，统一入口、Studio、治理中心是同一个前端工程里的三个路由入口，开发服务器把 `/api` 转发到 platform、`/erp` 转发到 demo-erp，前端只访问同源地址。

后端模块如下：

| 模块 | 内容 |
|---|---|
| `eab-common` | SQLite 连接与 Flyway 迁移、时间格式、按 JS 规则格式化数字 |
| `eab-spec` | 资产 schema、YAML 解析、引用与版本、可见性与裁剪、静态检查、JSON Schema 导出 |
| `eab-assembler` | 组装规则、CEL 条件的检查与求值、钩子执行 |
| `eab-directory` | 演示租户、组织、角色、用户 |
| `eab-policy` | 策略规则与判定引擎（鉴权点 1 到 3） |
| `platform` | 注册中心、身份、策略、审计、事件接收，以及资产与数据命令行 |
| `demo-erp` | 模拟 ERP：BO API（鉴权点 4）、Open API、元数据、页面、业务事件 |

`packages/ui` 定义视觉规范：三类资产来源（标品、租户、外部，另加平台）的颜色、图标与标签组件，写操作级别标签，鉴权裁决标签。颜色以 CSS 变量定义，浅色和深色模式各一套，前端通过 Tailwind 的主题变量使用。

各服务的监听地址由专用环境变量决定，不读取通用的 `PORT`、`HOST`，以免继承开发者 shell 里的设置：`EAB_HOST`（默认 127.0.0.1，容器内为 0.0.0.0）、`EAB_PLATFORM_PORT`（默认 4000）、`EAB_DEMO_ERP_PORT`（默认 4100）；前端代理目标由 `EAB_PLATFORM_URL`、`EAB_DEMO_ERP_URL` 指定。docker-compose 把端口只发布在本机回环地址上。

`scripts/demo.mjs` 在 jar 比源码旧时先用 Maven Wrapper 打包，再用 concurrently 并行启动各服务（后端用 `java -jar`），轮询健康检查，全部就绪后打印访问地址；某个必需服务在 90 秒内没有就绪时，停止全部进程并报错。服务项可以标记为可选，可选服务失败不影响演示主流程（给后续的 Python 外部 Agent 预留）。

## 资产模型与组装（P1）

资产模型由三部分组成：`eab-spec` 定义资产，`eab-assembler` 把资产组装成有效定义，平台主服务里的注册中心负责存储与可见性。术语见 `docs/concepts.md`。

### eab-spec

每个 kind 一份 schema 定义（`kinds/`）和对应的记录类型（`model/`），加上公共信封（`apiVersion`、`kind`、`metadata`、`spec`），共 14 个 kind，写法见 ADR 0007。`AssetParser` 先做 YAML 语法检查，再按 kind 校验，报错用中文，并带出行号和字段路径，格式如 `第 17 行第 15 列 spec.tools[2].mcp.source：……`。未知字段会被指出来。

这个模块还提供几样共用的东西：

- 命名空间分层规则（`Layers`）和资产引用解析（`AssetRef`、`Versions`），按 npm 风格的版本范围在指定状态的版本里取最高版本；
- `AssetResolver` 接口，组装器、静态检查、注册中心共用；
- 按查看者裁剪资产的 `Visibility.redact`，以及写入权限判断 `canWriteNamespace`；
- 静态检查 `Lint`；
- `AssetLoader` 读取资产目录，测试夹具 `Fixtures` 提供资产构造器；
- 由 schema 导出的 JSON Schema 放在仓库根目录的 `schemas/`，用 `pnpm spec:json-schema` 重新生成，有测试检查它与 schema 同步。

### eab-assembler

`Assembler` 不抛异常：输入要组装的 Agent、候选扩展、平台规则和解析器，输出有效定义和问题列表。主提示词 5.4 节七条规则的实现位置如下：

| 规则 | 实现 |
|---|---|
| 指令按固定顺序拼接，每段带来源 | `Segments`，规则编号约束展开为语义包规则的处置建议 |
| 工具取并集，租户重名拒绝 | `Capabilities`，工具来源展开为具体工具，挂了语义包的 Agent 自动获得语义查询工具 |
| 确认策略取最严 | `Capabilities`，租户放宽标品要求时报 `APPROVAL_RELAXED` |
| 钩子先标品后租户，租户失败隔离 | `Hooks`，运行时复用 |
| 模型档位限制在标品允许范围 | `Capabilities` |
| 扩展目标版本范围解析 | `Assembler`，取同时满足全部扩展范围的最高已发布版本，写入 `provenance.resolvedTarget` |
| 有效定义按查看者裁剪 | `DefinitionView`，依据片段的 `confidential` 标记 |

钩子和确认点的条件用 CEL（cel-java），组装时按标品 Agent 的输出结构做类型检查（ADR 0003）。

### 注册中心

注册中心位于 `platform` 模块的 `registry` 包，数据存在 SQLite，用 Spring 的 `JdbcClient` 访问，迁移脚本在 `db/migration`，启动时由 Flyway 执行。业务逻辑只依赖 `AssetRepository` 和 `AuditStore` 两个接口，换 PostgreSQL 时替换实现即可。

资产按版本存储，同时保存作者提交的 YAML 原文和解析后的规范化 JSON（RFC 8785），内容哈希基于规范化 JSON 计算。各种操作的规则如下：

- 写入：只有本层身份能写本层命名空间，越层写入返回 403 并写审计。
- 发布：标品层和平台层的资产发布时用平台私钥做 Ed25519 签名。密钥在首次启动时生成，存放在 `data/keys/`。
- 读出：注册中心作为解析器给组装器读资产时，每次都核对内容哈希并验签，不符就拒绝加载并写审计。
- 读取接口：按查看者裁剪后返回，租户看不到其他租户的资产，也看不到其他层的草稿。

平台启动时把 `assets/platform` 和 `assets/vendor` 以本层身份发布入库，同版本同内容跳过，同版本不同内容报错（修改标品必须升版本号）。`assets/tenants/t001` 是演示完成态快照，启动时不加载。

OpenAPI 描述由 springdoc 生成（`GET /openapi.json`），响应统一为 `{ success, data | error }` 信封。调用者身份从 Bearer 用户令牌推导（见下一节）。

| 接口 | 作用 |
|---|---|
| `GET /registry/assets` | 列出当前身份可见的资产版本 |
| `GET /registry/assets/:namespace/:name` | 读取资产（按身份裁剪），`?version=` 指定版本 |
| `PUT /registry/assets` | 提交 YAML，新建草稿版本 |
| `PATCH /registry/assets/:namespace/:name/:version/status` | 变更状态，标品发布时签名 |
| `POST /registry/validate` | 校验 YAML 并运行静态检查 |
| `GET /registry/agents/:namespace/:name/effective` | 有效定义预览 |

`pnpm assets:validate` 和 `pnpm assets:assemble` 两个开发命令（`platform` 模块的 `cli` 包）用内存数据库和临时密钥建一个注册中心，把仓库里全部资产按发布路径灌进去，然后做校验或打印有效定义，走的是和平台启动相同的代码路径。

## 身份与四处鉴权（P2）

一次请求要经过四处鉴权（主提示词 7.3 节）。前三处在平台：鉴权点 1 是入口，鉴权点 2 是 Agent 交接，鉴权点 3 是工具与 MCP 网关；第四处在 DemoERP 的 BO API。每一处的裁决都写进同一张审计表。身份与令牌的设计见 ADR 0005。

### 演示目录

`eab-directory` 的 `DemoDirectory` 定义租户、组织（公司主体、采购组织）、角色与其允许的 BO 操作，以及用户和用户的数据范围。平台的身份服务和 DemoERP 都从这里读，是演示身份数据的唯一来源。

用户包括主提示词 6.2 节的 6 个，另加三个：

- t002 的采购经理周婷，用来证明租户隔离；
- 平台运维；
- t001 的库存经理孙库管，作为受托运行的委托人。

### 平台身份服务

身份服务位于 `platform` 模块的 `identity` 包，提供以下接口：

| 接口 | 作用 |
|---|---|
| `GET /identity/v1/users` | 演示用户列表 |
| `POST /identity/v1/login` | 选人即登录，签发用户令牌（仅演示） |
| `POST /identity/v1/token` | 客户端凭证模式，或 RFC 8693 令牌交换模式 |
| `GET /identity/v1/jwks.json` | 令牌校验公钥 |
| `GET/POST/DELETE /identity/v1/delegations` | 委托的查询、登记、撤销（租户管理员） |
| `GET /identity/v1/delegations/:id/status` | 委托核实，供 DemoERP 调用，需要内部调用密钥 |
| `POST /identity/v1/demo/agent-token` | 演示模式下由平台运维代运行时签发 Agent 令牌，用于手工验证 |

平台调用 BO API 的唯一通道是 `DelegatedClient.callBoApi`：先换取委托令牌，再带着它调用，并且只允许落在配置的 ERP 地址上的 `/bo/v1/` 路径。失败时抛出 `DelegationException`，不存在使用集成账号的路径。

### 策略引擎

`eab-policy` 负责鉴权点 1 到 3。

规则是 JSON（`policy/default.json`），条件用 CEL 编写，加载时逐条校验。`PolicyEngine` 的判定顺序是：命中 deny 立即拒绝；命中 approve（需要审批）、confirm（需要确认）的规则会累积起来，结果取其中最严的一种；都没有命中则允许。规则求值出错时一律拒绝。

判定所需的事实由 `PolicyInputs` 从有效定义、租户设置、单据和委托整理出来。确认点按条件过滤后取最严，复用组装器的 `matchingApprovals`、`strictestPolicy`。

租户级开关（L3 是否开启、岗位助手开放给哪些角色）存放在 `tenant_settings` 表。`GET /policy/v1/rules` 查看规则，`POST /policy/v1/evaluate` 做策略试算，试算结果写入审计。

### DemoERP

DemoERP 是 `demo-erp` 模块，数据存放在 `data/erp.db`，迁移脚本在 `db/erp`（与平台的 `db/migration` 分开）。

数据包括采购、库存、财务三个域，另有交期变更单、跟进任务、幂等键和访问日志。业务单号在租户内唯一，主键是“租户编号 + 单号”。

种子数据用固定种子的伪随机数（mulberry32）生成，可重复执行，与迁移前的 TS 版逐条一致（测试用黄金样本比对）。日期相对于生成当天，逾期天数在查询时计算，口径与语义包指标 `overdue_days` 一致。

BO API 的每个操作在 `PurchasingRoutes`、`InventoryFinanceRoutes` 里声明（操作名、路径、参数、处理函数），参数声明同时用于校验请求和生成 OpenAPI。`BoApi` 负责调度：匹配路由、校验参数、鉴权、幂等键、执行、记录访问日志。鉴权点 4 由 `BoAuthorizer` 实现，按下面的顺序检查：

1. 用平台 JWKS 验证委托令牌（按 kid 选出 Ed25519 公钥直接验签）；
2. 令牌必须带 `act`（Agent 身份）；
3. 令牌的租户与用户一致；
4. 用户有这个操作的业务权限；
5. Agent 声明过这个操作；
6. 受托运行时，委托仍然有效；
7. 按数据范围过滤，采购域按采购组织，库存和财务按公司主体。

受托运行时只允许只读操作。写操作支持 `Idempotency-Key`，同一个键只给同一个用户、同一份请求内容回放结果，写操作和幂等记录在同一个事务里提交。错误统一为 `{ code, reason, suggestion }`。列表响应带 `filtered`、`visibleScope`、`scopeLabel`。交期变更单的审批在 ERP 侧也做职责分离检查。

每次裁决都写入 ERP 访问日志，并以带 HMAC 签名的 webhook 推送到平台的 `/events/v1/ingest`，平台写入审计。令牌验签通过之后，任何一步被拒绝，都带着调用方身份推送，所以拒绝记录也能在租户审计里查到。

DemoERP 的其他接口：

| 接口 | 作用 |
|---|---|
| `/bo/v1/...` | BO API，`operationId` 与工具名一致，OpenAPI 在 `/bo/v1/openapi.json`，带 `x-write-level` |
| `/open/v1/...` | 集成接口，只做租户隔离，作为反面对照 |
| `/meta/v1/...` | 元数据服务：对象、字段、枚举、规则、权限维度 |
| `/demo/v1/events/trigger` | 演示控制台触发业务事件，需要内部调用密钥 |
| `/pages/...` | 供应商、采购订单的只读页面，用作导航型交互的深链接 |

### 审计

审计表记录的字段有：时间、租户、用户、Agent（含版本）、扩展、会话、调用模式、鉴权点、工具、数据范围、命中的规则、裁决、结果摘要。

查询接口 `GET /audit/events` 支持按用户、Agent、工具、鉴权点、目标（例如单据编号）、时间过滤。不同身份能查到的范围不同：

- 普通租户用户只能查自己的记录；
- 租户管理员能查本租户的记录；
- 平台身份能查全部；
- 厂商身份不能查。

## 技术选型

| 方面 | 选择 | 依据 |
|---|---|---|
| 后端框架 | Java 21、Spring Boot 4.1、Spring AI 2.0 | ADR 0006（替代 ADR 0001） |
| 协议 SDK | MCP Java SDK 2.0.1（经 Spring AI 引入）、A2A Java SDK 1.4.0.Final | ADR 0002、ADR 0006 |
| 前端 | React 19、Vite 8、Tailwind CSS 4、Radix 原语（shadcn/ui 风格）、TanStack Query、React Router | 主提示词第 14 节 |
| 测试 | JUnit 5、AssertJ、MockMvc、JaCoCo；前端 Vitest 5；端到端 Playwright | ADR 0006 |
| TypeScript（前端） | 6.0 | typescript-eslint 8.x 目前支持到 6.0，暂不升级到 7.x |
| 资产 schema | `eab-spec` 自写的 schema 描述，Jackson 3 解析 YAML | ADR 0007 |
| 存储 | SQLite（sqlite-jdbc）、Flyway 迁移、`JdbcClient` 加仓储接口 | 主提示词第 14 节、ADR 0006 |
| 条件表达式 | CEL，cel-java 0.14 | ADR 0003 |
| 令牌 | Nimbus JOSE JWT 10.10，EdDSA（Tink） | ADR 0005 |

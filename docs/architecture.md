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
| `eab-tracing` | span 模型（兼容 OpenTelemetry 语义）、按查看者裁剪、OTLP JSON |
| `eab-runtime` | 模型网关（基于 Spring AI）、工具网关、Agent 循环、技能与工作流、监督层、语义查询 |
| `platform` | 注册中心、身份、策略、审计、事件接收、运行接口与 MCP 端点，以及资产与数据命令行 |
| `demo-erp` | 模拟 ERP：BO API（鉴权点 4）、Open API、元数据、页面、业务事件 |
| `eab-cli` | `pnpm agent:run`：进程内启动平台与 DemoERP，跑一次 Agent |

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

## 运行时（P3）

运行时让 Agent 真正跑起来，并且每一步都可追踪。设计取舍见 ADR 0008。

### 一次运行

`AgentRuntime` 是一个自研的轻量循环：

1. 组装有效定义（assemble span），签发本次运行的 Agent 令牌，scope 是 Agent 声明的 BO 操作（含技能、钩子工作流里用到的）；
2. 读取长期记忆和会话记忆（memory span），执行前置技能；
3. 最多八轮“模型调用、工具调用”。模型返回工具调用时，每个调用都交给工具网关；没有工具调用时得到最终回复；
4. 后置步骤：输出守卫核对回复里的单号和金额，回复复述了保密片段时产生告警；结构化结果满足条件时执行 onResult 钩子，标品钩子失败中止运行，租户钩子失败只在回复里提示；
5. 写会话记忆，推送最终结果。

运行事件逐个推给调用方：step（思考步骤摘要）、tool_call、tool_result、confirmation_required、text_delta、final、error。`POST /runs` 默认以 SSE 推送，请求头 `Accept: application/json` 时同步返回全部事件。

子 Agent 是一种工具：先过鉴权点 2（例如调用深度不超过三层），再组装子 Agent，以它自己的 Agent 身份递归执行，事件和 span 挂在父运行下面。

### 模型网关

模型提供方都是 Spring AI 的 `ChatModel`：脚本化模型（默认，读 `demo/scripts`）、Anthropic 协议、OpenAI 兼容协议，由环境变量 `EAB_MODEL_PROVIDER` 选择。Agent 只引用档位（fast、reasoning、judge），档位到模型名的映射也来自环境变量。每次调用记录 token 数和估算成本。

模型调用经过一条处理管道：

| 环节 | 做什么 |
|---|---|
| 知识检索注入 | 按话术从挂载的语义包里取最相关的指标口径和规则 |
| 提示模板 | 有效定义的片段（每段带来源标记）、用户上下文、长期记忆、检索到的知识、结构化结果格式 |
| 脱敏与还原 | 身份证号、银行账号、手机号在发给模型前换成占位符，回复和工具参数里还原 |
| 输入过滤 | 拦截提示注入特征，拦截时不调用模型 |
| 输出过滤 | 去掉片段标记，遮蔽复述的保密片段并报告片段编号 |

后三个环节实现为 Spring AI 的 Advisor。工具由平台执行：调用模型时关闭 Spring AI 的自动工具执行，模型返回的工具调用交给工具网关。

### 工具网关

平台暴露给 Agent 的所有工具都经过工具网关。每次调用依次经过：

1. 鉴权点 3：策略引擎裁决工具是否声明、调用模式、写级别、委托范围、确认与审批点，裁决写入审计；
2. 守卫：写级别不能超过 Agent 声明的上限；
3. 确认：需要确认时挂起运行；需要审批的写操作在审批收件箱上线（P8）之前一律拒绝；
4. 按工具类型执行。

| 类型 | 执行方式 |
|---|---|
| BO API | 按工具定义的方法和路径构造请求，经令牌交换以“用户加 Agent”的身份调用；写操作带幂等键 |
| 语义查询 | `semantic.describe_object` 等四个工具，只在 Agent 挂载的语义包范围内查询，投影段的定义实时从元数据服务取 |
| 内置工具 | 计算器用 CEL 计算四则运算 |
| 技能 | 技能执行器按步骤执行，步骤里的工具调用同样经过工具网关 |
| 子 Agent | 见上文 |
| 外部 MCP Server | 连接别名在部署配置里绑定地址，用 MCP 官方 Java SDK 的 Streamable HTTP 客户端调用 |
| http | IM_WEBHOOK 连到模拟的 IM 渠道，其他别名按部署配置调用 |
| 文档检索 | 知识源列出的 Markdown 文档按段落切分，用 BM25 检索 |
| 远程 Agent | P7 实现 |

BO 工具的入参 schema 从 DemoERP 的 OpenAPI 生成：`pnpm tools:import-bo` 读取 `/bo/v1/openapi.json`，与资产比对，加 `--write` 写回资产并升次版本号；有测试检查两者一致。

### 技能与工作流

技能和工作流是确定性的步骤序列，不调用模型。步骤类型有 tool、skill、agent、transform、condition、parallel、confirm、notify、waitEvent，其中 agent、parallel、waitEvent 只用于工作流。with、transform、condition 的值是 CEL 表达式，confirm 和 notify 的消息用 mustache 渲染。技能调用前已经取得确认时，技能内部的确认步骤和写操作不再重复询问。waitEvent 在 P3 只做挂起，P4 接上事件触发后恢复。

### 挂起与恢复

运行需要确认时挂起，保存的状态是一份运行日志：按结构化路径记录模型调用、工具调用、鉴权裁决和确认决定。用户决定后，运行从头重放：日志里已有的步骤直接取记录的结果，不再调用模型、不再重复写入。spanId 由运行编号和同一条路径派生，挂起时以 suspended 落库的 span 在恢复后被最终状态覆盖。

### 监督层

监督层是提示词之外的第二道防线，全部是确定性逻辑：

- 写操作前：写级别、SoD、委托范围由策略引擎裁决，写级别上限由守卫检查；
- 输出守卫：回复里的单号和金额必须能在本次运行的工具结果里找到，找不到的在原文后标注“未经核实”；
- 行为异常：同一会话十分钟内写操作超过五次，或者运行中访问的数据范围扩大，写入风险告警（`GET /runtime/alerts`）；回复复述了保密片段时同样告警。

### 记忆

会话记忆保存同一会话的问答，注入下一次运行。长期记忆按“租户、用户、Agent”三元组隔离，保存用户偏好和确认过的事实，注入系统提示词；`GET/POST/DELETE /memory` 查看、添加和清除本人的记忆。

### 追踪

span 存在平台数据库里，种类有 run、assemble、pipeline、model、policy、tool、skill、workflow、agent、guard、hook、confirmation、memory，属性名尽量沿用 OpenTelemetry 的 `gen_ai.*` 语义约定。模型 span 只保存系统提示词的片段数组，`GET /traces/{traceId}` 按查看者裁剪：租户视角下标品指令和平台规则原文换成占位块；用户只能看自己的运行，租户管理员能看本租户的，平台运维能看全部，厂商看不到租户的运行。设置 `EAB_OTLP_ENDPOINT` 时运行结束后按 OTLP/HTTP JSON 导出，导出内容只带片段编号。

### 平台对外的 MCP 端点

`/mcp` 用 MCP 官方 Java SDK 的无状态 Streamable HTTP 传输。外部 MCP 客户端带用户令牌访问，能用的工具是只读的 BO 操作和语义查询工具。每次调用以 `agent:platform/mcp-endpoint` 的身份做令牌交换，经过工具网关，鉴权点 3、4 与审计照常，并记一次运行。

### 命令行

`pnpm agent:run <namespace/name> --user <用户> "<话术>"` 在进程内启动平台（加载仓库资产，签名入库）和 DemoERP（内存库），两者经本机随机端口互通，链路与部署相同，不依赖 `pnpm demo`。终端里流式打印运行事件，遇到确认时提示 y/n（加 `--yes` 自动确认），结束后打印追踪树。

## 统一入口（P4）

最终用户只面对一个入口。入口把问题路由到合适的岗位助手和 Agent，或者直接给出 DemoERP 页面的链接；设计取舍见 ADR 0009。

### 两级路由

`AssistService` 处理一次提问：第一级在本租户的岗位助手（和标记为可直达的 Agent）里选，第二级在所选岗位助手的成员 Agent 和两个导航场景里选。每一级依次是 BM25 召回前五个候选、权限过滤、决策。岗位助手的权限过滤是鉴权点 1；Agent 要求用户至少有它声明的一项 BO 操作的权限。脚本化模型下按召回分数决策，配置了真实模型时由 fast 档位的模型在候选里选择并给出理由。

路由有四种结果：

| 结果 | 处理 |
|---|---|
| 选中 Agent | 交给运行时执行，路由信息写进这次运行追踪的 routing span |
| 选中导航场景 | 找到用户问的采购订单号或供应商（只在用户有权查看的供应商里找），签发 10 分钟有效的页面令牌，返回 DemoERP 只读页面的深链接 |
| 澄清 | 置信度低于 0.6 时给出前两个候选，用户点选后带 target 再问一次，这一级直接选中所选候选（仍然过权限过滤） |
| 能力缺口 | 说明原因（没有匹配的能力，或候选都被权限过滤），提供“提交扩展需求” |

后三种结果不调用 Agent，但同样记一次运行和追踪，Agent 记为 `platform/router`。

四种交互模式落在不同的 Agent 上：事务型由采购订单处理 Agent 提交交期变更单（L2，写入前确认）；导航型由导航场景处理；信息型由采购制度问答 Agent 检索知识源里的制度文档；分析型由供应商绩效分析 Agent 按语义包的指标口径计算。

### 接口

| 接口 | 说明 |
|---|---|
| `POST /assist` | 提问，body 是 message、sessionId、target；默认 SSE，`Accept: application/json` 时同步返回。事件在运行事件之外多了 routing、navigation、clarify、gap |
| `POST /dev-requests` | 提交扩展需求，在本租户的 `tenant.<租户>.requests` 命名空间里创建一份 DevRequest 草稿（评审与交付在 P9） |
| `GET /notifications`、`POST /notifications/{id}/read` | 本人的通知 |
| `POST /demo/v1/events` | 演示模式下触发业务事件，平台带内部调用密钥转发给 DemoERP，DemoERP 再按正常的 webhook 推回平台 |

统一入口只对租户用户开放，每人每分钟最多 30 次提问。

### 业务事件触发

`EventTriggers` 订阅业务事件。事件到达后，找出本租户已发布、声明了这个事件触发器的 Agent，用登记过的有效委托以受托方式运行（只读），结果写进委托人的通知区。没有有效委托的 Agent 不运行，并写一条 `trigger.event` 拒绝审计，不会退回到技术账号。演示故事里，交付逾期事件触发库存经营分析 Agent，以孙库管的委托评估对库存的影响。

### 前端

统一入口页面以 SSE 接收事件，一轮问答里依次显示思考步骤、工具卡片、确认卡片（L2 写操作，显示写级别、参数和原因，确认或拒绝后恢复运行）、导航卡片、澄清选项、能力缺口卡片，最后是结果卡片（回答、数据范围、依据的数据来源、未核实的数字）。右侧是路由说明（两级候选、分数、过滤原因、决策理由）、本次链路（span 树）和通知区。`/channels/im` 模拟 IM 渠道：左侧是 Agent 推送到风险提醒群的消息，右侧以 IM 方式提问，卡片降级为文字，需要确认的操作引导回统一入口。

## Studio（P5）

Studio 是开发态的入口，接口见 `docs/studio-api.md`，设计取舍见 ADR 0010。

### 从意图新建 Agent

开发者输入一句话意图后，平台新建一份工作区草稿（`studio_drafts`），进入 13 步向导：描述目的、命名、业务流程归属、协作 Agent、数据与知识、技能与工具、触发器、渠道、指令与约束、模型档位、评测集、测试、发布。前十步每一步都有 AI 推荐：`StudioRecommender` 先用 BM25 在能力清单（`StudioCatalog`：本租户可见的 Agent、远程 Agent、语义包及其规则与已验证任务、知识源、技能、工具、流程目录、渠道、档位）里检索候选，再以 `platform/studio` 的身份经模型网关请模型在候选里挑选并说明理由。模型给出的引用必须在候选里，不合格时退回检索推荐。采纳一步时，`StudioDraftComposer` 只改这一步负责的字段，草稿以 YAML 为准，右侧实时显示，可以切换到编辑模式。

### 私有环境测试与评测

测试和评测运行草稿本身：草稿经 `OverlayResolver` 叠加在租户的解析范围之上，遮住注册中心里的同名资产，只对这一次运行生效。演示模式下可以选择测试用户，平台为该用户签发令牌，按他的身份和数据权限运行，写审计；测试运行记下发起的开发者，开发者可以查看它的追踪（时间线和 span 详情，租户视角下标品指令已裁剪）。

评测集初稿从已选语义包的已验证任务生成，每个用例运行一次草稿，逐条核对断言：调用了某工具、没有调用某工具、结果包含、结果不包含、数值区间、不变量（CEL）和裁判。裁判在脚本化模型下是确定性的，配置真实模型时用 judge 档位。评测结果按被测内容的哈希（去掉版本号）记录在 `eval_runs`。

### 静态检查、发布与晋级

静态检查在资产校验之外加上试组装的问题和路由重叠检查（场景描述与能力目录里已有场景的字二元组相似度达到 0.6 时告警）。发布把 Agent 和评测集写入注册中心并签名，部署到 dev；同一内容重复发布不升版本。每个租户有 dev、test、prod 三个环境（`deployments`），晋级 test 要求已在 dev，晋级 prod 还要求静态检查没有错误、同一内容的评测通过；可以回滚到上一个版本。租户的岗位助手和 Agent 部署到 prod 后才进入统一入口的能力目录，并按 prod 的版本运行。

### 约束守卫

语义包规则可以带检查条件（工具名加 CEL 表达式）。Agent 的约束引用了这类规则时，运行时在对应工具返回后判断是否触发，触发时写一个守卫 span，推送 `constraint` 事件，并把规则和处置建议写进交给模型的工具结果。PUR-R-007 检查采购订单里是否有逾期超过 90 天的订单，故事 A 里价格谈判助手和它调用的供应商绩效分析 Agent 都会触发。

### 连接中心

连接中心列出本租户的连接别名、每个环境的绑定（地址、认证方式、凭据引用）和引用它的工具来源与 Agent。租户管理员可以修改绑定，每次修改写入 Connection 资产的新补丁版本并发布；凭据引用只能是 `env:` 或 `vault:` 开头的引用。连接是环境配置，启动时随平台和标品资产一起入库。

## 治理与演进（P8）

治理中心是租户管理员的入口，接口在 `/hub/*`，设计取舍见 ADR 0013。

### 治理中心

资产清单列出本租户可见的助手、Agent、扩展、远程 Agent 和工具来源，标出来源（标品、租户、扩展、外部、平台）、签名和各环境的部署版本；血缘图画出助手与成员 Agent、扩展与标品 Agent、Agent 与协作的远程 Agent 之间的边。选中一个资产可以比较两个版本：差异在按查看者裁剪后的视图上计算，租户视角下标品指令只显示“已变更”。

上架准入列出尚未进入 prod 的租户 Agent 和扩展，检查评测门禁（同一内容的评测通过记录）、工具写级别、MCP Server 审批和完整性，全部通过后管理员批准，平台按 Studio 的晋级流程部署到 prod。审计可以从一个业务对象反查：按审计记录的目标和结果摘要匹配，再按时间找到对应的运行和追踪。委托管理复用身份服务的委托接口，撤销后事件触发的运行在鉴权时被拒绝。风险告警来自监督层。

### 评测引擎

`backend/eab-eval` 是独立的评测引擎，Studio、治理中心、升级回归和 `pnpm eval:smoke` 共用。评测集可以写普通用例，也可以写 Given/When/Then 场景，场景可重复运行，评测集按最低通过率判定。用例以用例指定的用户身份运行，遇到确认点自动确认；调用过的工具从事件和追踪（含技能内部的调用）里收集。

### 升级回归与方案包

模拟升级时，平台用扩展所在租户的评测集先跑基线，再发布标品新版本，重新组装扩展、再跑一次，基线通过而升级后失败的用例记为回归，连同组装问题写入 `upgrade_regressions` 并通知。方案包从扩展出发收集本租户资产，编译出清单、哈希和签名，按 dev、test、prod 部署，每次部署重新验签，test、prod 要求上一环境当前是同一个包，可以回滚。

### 经验回流

用户在结果卡片上反馈结论有误，平台生成候选规则（带检查工具和 CEL 条件），管理员审核后写入租户语义包扩展 `tenant.<租户>.semantic/<基础包>-ext`。组装时被扩展的语义包会带上扩展包，约束守卫对其中带检查条件的规则生效，守卫 span 记下规则来源。

### 指标

`run_metrics` 保存每次运行的耗时、令牌、费用、确认次数和工具调用，从运行记录和追踪补算；`business_metrics` 保存流程指标和业务结果。治理中心按 Agent 汇总技术指标，按天画运行量、流程指标和业务结果的趋势，并按岗位助手的 KPI 定义展示当前值、上期值和目标。`pnpm data:simulate` 生成 30 天的演示历史。

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
| 模型调用 | Spring AI 2.0：`ChatClient`、Advisor、Anthropic 与 OpenAI 模块；脚本化模型自己实现 `ChatModel` | ADR 0006、ADR 0008 |
| 模板 | jmustache（剧本回复、确认与通知消息） | ADR 0008 |
| MCP | MCP Java SDK 2.0：Streamable HTTP 客户端，Spring WebMVC 无状态服务端 | ADR 0002 |
| 令牌 | Nimbus JOSE JWT 10.10，EdDSA（Tink） | ADR 0005 |

# ADR 0014 专业代码通道：eab CLI、Studio MCP Server 与代码工程导出

状态：已采纳（P9，2026-10-18）。

## 背景

主提示词 10.10 节要求专业代码通道：CLI 让开发者把资产拉到本地、放进 Git、再推回平台；Studio 的能力（语义包查询、能力目录、扩展点目录、资产校验、评测运行、部署）以 MCP 工具暴露给编码 Agent；低代码里建到一半的 Agent 能导出成代码工程，与 init 的模板同构，不维护两套体系。界面与文件必须始终是同一份数据，不能出现改了文件界面不知道、改了界面文件丢了的分叉。验收标准：按 `docs/pro-code.md` 的步骤，在一个新的 Claude Code 会话里经 Studio MCP 查到语义资产并成功部署一个扩展。

## 决策

### eab CLI 是对运行中平台的 HTTP 客户端

CLI 不进程内启动平台（区别于 `pnpm agent:run`、`pnpm eval:smoke` 的进程内模式）：专业开发者面对的平台是别人起好的演示或共享环境，CLI 只依赖 HTTP 契约，这同时让它成为对外契约的活文档。平台地址用 `--url` 或 `EAB_PLATFORM_URL`（默认 `http://127.0.0.1:4000`）。会话（登录令牌）存仓库外的 `~/.config/eab/cli-session.json`，与 model.env 的约定一致：凭据不进仓库、不进数据快照。令牌 8 小时有效，过期重新 login。

### push 走 Studio 草稿，不直接写注册中心

push 逐资产找回（或新建）同一条草稿，再整份替换 YAML。理由：评测、静态检查、MCP 审批这些门禁，扩展与评测集的联动发布，部署记录，都只存在于 Studio 的发布链路上；直接写注册中心会绕开全部门禁。找回顺序：先试工程清单里的 draftId（404 视为草稿已删），再按 kind + namespace + name 在 `GET /studio/home` 里匹配。建稿按资产类型走三条通道：Agent 用 `POST /studio/drafts`，扩展用 `POST /studio/extensions`（target 剥掉版本范围，接口按 key 找标品 Agent），技能用 `POST /studio/skills`。评测集不是独立推送的资产：推到主草稿的 evalYaml 槽，发布时服务端一并写注册中心。

### 工程清单与模板

`eab-project.yaml` 是工程的锚点：`template`（agent、extension、a2a-python、a2a-ts）、`platform`、`user`、`target`、`cardUrl`、`assets: [{file, kind, ref, draftId?}]`。draftId 在 push 后回写，pull 与导出都带上，来回切换始终找回同一条草稿。四套模板资源在 `backend/platform/src/main/resources/procode/templates/`，占位符用 String.replace 替换，不引模板引擎；YAML 骨架与 Studio 的空草稿对齐，保证 schema 可过。A2A 模板与外部样例用同版本 SDK（a2a-sdk 1.2.2、@a2a-js/sdk 1.3.0），默认端口避开演示样例（4300、4310）。

一个打包细节：模板里的 gitignore 存成不带点的文件名，写出时才落成 `.gitignore`。maven-jar-plugin 底层的 plexus-archiver 按 Ant 默认排除清单跳过点开头的文件，直接叫 `.gitignore` 的资源根本进不了 jar，exec jar 里的 init 会报模板文件缺失。

### 导出与 init 同构是结构性保证

模板、工程清单、文件组织都在平台的 procode 包（`dev.eab.platform.procode`）：init、`GET /studio/drafts/{id}/export`、`eab pull --draft` 调同一份生成器（ProjectTemplates、ProjectExport），三种入口的产物逐字节一致。同构不靠约定靠代码结构：`EabCliEndToEndTest` 里有导出 zip、解包成新工程、再 push 找回同一条草稿且内容不变的回环断言，是"界面与文件是同一份数据的两种视图"的机器证明。

### validate 走批量校验，工程内引用一起解析

新端点 `POST /registry/validate-batch`（一次最多 20 份）。新工程的扩展会引用还没发布的评测集与技能，单份校验会报引用不可解析；批量校验把解析成功的资产放进 `OverlayResolver` 的叠加层，工程内部的相互引用在叠加层里解析。叠加机制与 Studio 草稿测试同源（ADR 0010）。

### Studio MCP Server

端点 `/studio-mcp`，与 `/mcp` 同栈：Streamable HTTP 无状态传输（ADR 0002），Spring AI mcp-spring-webmvc，多 RouterFunction Bean 由 Spring 组合。区别在身份：`/mcp` 面向业务用户，`/studio-mcp` 只对租户开发者与租户管理员开放，14 个工具按前缀分两组：

- `semantic_*` 四个：复用 `/mcp` 的语义查询工具（BuiltinTools.SEMANTIC），点名里的点换成下划线后注册，调用走 `RuntimeService.callAsMcp`，同样过鉴权点 3、写审计。语义查询的行为与权限在两个端点完全一致。
- `studio_*` 十个：能力目录、扩展点目录、草稿列表与详情、校验、评测生成与运行、发布、晋级，指向 Studio 的同一批服务。

每次调用独立鉴权（Bearer 用户令牌），角色不符或令牌缺失返回 isError 的工具结果而不是 HTTP 错误，编码 Agent 能把原因读给用户；评测与部署类工具按用户限流（每分钟 30 次）。发布仍走 Studio 的门禁链路，MCP 工具没有特权。

### ADR 0012 的回调设计（只设计，不实现）

代码 Agent 回调平台查数据时，不把用户令牌交给外部进程。设计：

1. 平台给代码 Agent 签发的服务令牌（aud 为 eab-a2a，TTL 5 分钟）保持不变。
2. 新增交换端点：Agent 用服务令牌换受限的只读委托令牌，aud 为 eab-platform，sub 是发起用户，act.sub 是 Agent 的身份（RFC 8693 的委托语义），scope 限只读工具（L0），TTL 5 分钟。
3. `/mcp` 端点扩展接受该令牌类型：按 act.sub 记审计，调用仍过鉴权点 3，数据范围按 sub 用户。
4. 委托令牌不能换写操作，不能再次交换，没有用户身份时拒绝；不存在任何退回技术账号的路径。

先不实现的原因：演示里的代码 Agent（A2A 模板）不回调平台也能讲清协作关系，回调链路没有对应的故事。设计落在这里，实现排到有真实需求时。

## 影响

- 新增 `pnpm eab`（`scripts/java-cli.mjs` 增加 eab-cli 模块入口）；验收脚本 `scripts/acceptance-procode.mjs` 幂等，不发布资产，重复运行不破坏演示状态。
- ResetDemo 显式撤回 pro-code 演示发布的故事 E 完成态资产（`tenant.t001.pur/purchase-order-clerk-ext`、`tenant.t001.skills/alternative-supplier-lookup`、`tenant.t001.evals/purchase-clerk-ext-smoke`）：这些命名空间不在启动入库范围，`pnpm seed:reset` 不撤回就会残留，故事 E 的缺口演示就恢复不回去。（2026-10-18 更新：P10 起撤回范围扩大为除基础设施外的全部租户资产，见 0015。）
- 实录见 `docs/pro-code.md`，接口契约见 `docs/studio-api.md` 的"专业代码通道"一节。
- 建稿、评测、发布接口被 CLI 与 MCP 复用，改这些接口时要同时考虑两个入口（EabCliEndToEndTest 与 StudioMcpEndToEndTest 会拦住破坏）。

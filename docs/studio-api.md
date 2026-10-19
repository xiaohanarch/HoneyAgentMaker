# Studio 接口

Studio（开发态）调用的平台接口。前端经同源的 `/api` 访问，下面的路径省略 `/api` 前缀。响应都是统一信封 `{success, data, error}`，错误时 `error` 带 `code` 和 `message`。身份只来自 Bearer 用户令牌，Studio 接口要求租户开发者（`tenant-developer`）或租户管理员（`tenant-admin`），只能操作本租户的资产。

设计取舍见 ADR 0010：草稿存在可修改的工作区里，测试和评测时以叠加方式交给运行时（私有环境）；发布时写入注册中心成为签名版本，再按 dev → test → prod 晋级。

## 数据结构

```ts
interface DraftView {
  draft: {
    id: string; intent: string; step: number; kind: 'Agent' | 'AgentExtension' | 'Skill'
    namespace: string; name: string; publishedVersion: string | null
    owner: string; updatedAt: string
  }
  yaml: string                    // 资产草稿，向导的“实时 YAML”
  asset: Record<string, unknown>  // yaml 解析出的树，解析失败时为 null
  issues: { code: string; message: string; path: string | null; line?: number }[]  // schema 问题
  evalYaml: string | null         // 评测集草稿
}

interface Recommendation {
  step: string                    // purpose naming process collaborators knowledge tools triggers channels instructions model
  value: Record<string, unknown>  // 推荐取值，一键采纳时原样（或改过后）交给 apply
  candidates: Record<string, unknown[]>  // 本步候选，前端可以在候选里改选
  reason: string
  decidedBy: 'model' | 'retrieval'
}
```

各步 `value` 的字段（apply 接受同样的字段）：

| step | value |
|---|---|
| purpose | `intent`，`similar: {ref,name,score}[]`（只读，提示先复用） |
| naming | `name`（小写字母数字连字符）、`displayName`、`description` |
| process | `businessProcess: string[]`（流程 code） |
| collaborators | `agents: string[]`（Agent ref；`connection:` 开头的是外部 Agent 候选，P7 前不可用，不写入草稿） |
| knowledge | `semanticPacks: string[]`、`knowledgeSources: string[]` |
| tools | `skills: string[]`、`tools: {source, name}[]` |
| triggers | `triggers: {type}[]`、`direct: boolean` |
| channels | `channels: ('assistant-web'|'im'|'api')[]` |
| instructions | `instructions: string`、`constraints: string[]`（规则编号） |
| model | `profile: 'fast'|'reasoning'`、`temperature: number` |
| examples | `examples: string[]`（路由示例话术，测试一步可补充） |

候选里的条目：Agent、技能、知识源是 `{ref, name, description, layer, writeLevel, available, note, examples}`；工具是 `{source, name, description, writeLevel, category}`；流程是 `{code, name, description}`；规则是 `{id, severity, description, remedy, checkable}`；渠道和档位是 `{value, label, description}`。

## 首页与能力清单

- `GET /studio/home` → `{ drafts: DraftView['draft'][], agents: {ref, name, version, environments: {dev?: string, test?: string, prod?: string}}[], devRequests: {ref, displayName, originalText, requester, status, createdAt}[] }`（devRequests 只列最近 10 条，从新到旧）
- `GET /studio/catalog` → `{processes, agents, semanticPacks, knowledge, skills, tools, channels, profiles}`

## 草稿与向导

- `POST /studio/drafts` body `{intent}` → `DraftView`
- `GET /studio/drafts/{id}` → `DraftView`
- `POST /studio/drafts/{id}/recommend` body `{step}` → `Recommendation`（经模型网关，脚本化模型下按剧本输出）
- `POST /studio/drafts/{id}/apply` body `{step, value}` → `DraftView`
- `PUT /studio/drafts/{id}/yaml` body `{yaml}` → `DraftView`（YAML 编辑模式；有 schema 问题时仍保存，issues 里列出）
- `DELETE /studio/drafts/{id}`
- `GET /studio/drafts/{id}/export` → `application/zip` 附件（文件名 `<资产名>-project.zip`）：草稿导出为代码工程（专业代码通道，ADR 0014），文件集与 `eab init` 的产物同构，`eab-project.yaml` 清单里带 `draftId` 与资产引用，推回平台（`pnpm eab push`）时据此找回同一条草稿。支持 Agent、AgentExtension、Skill 草稿；评测集草稿非空时一并导出到 `evals/`。

## 测试（私有环境）

- `POST /studio/drafts/{id}/test` body `{message, asUser, sessionId?}` → SSE 运行事件（与 `POST /runs` 相同：run_started、routing、step、tool_call、tool_result、constraint、confirmation_required、text_delta、final、error）；请求头 `Accept: application/json` 时同步返回 `{runId, traceId, sessionId, status, text, output, pending, error, events}`。以 `asUser` 的身份运行草稿，只在演示模式下开放，写审计。
- `POST /studio/test-runs/{runId}/confirmation` body `{approved}` → 同上，继续推送事件
- `GET /traces/{traceId}` → `{traceId, runId, agent, spans: Span[]}`，发起测试的开发者可以查看测试运行的追踪

```ts
interface Span {
  spanId: string; parentSpanId: string | null; name: string
  kind: 'run'|'routing'|'assemble'|'pipeline'|'model'|'policy'|'tool'|'skill'|'workflow'|'agent'|'guard'|'hook'|'confirmation'|'memory'
  startTime: string; endTime: string | null; status: 'ok'|'error'|'suspended'; statusMessage: string | null
  attributes: Record<string, unknown>   // 例如 eab.constraint.rule、eab.policy.decision、gen_ai.usage.input_tokens
  promptSegments: { id: string; kind: string; layer: string; source: string; text: string | null; label: string | null; redacted: boolean }[] | null
}
```

约束触发的 span 名为“约束 · PUR-R-007 触发”，kind 为 guard，属性 `eab.constraint.rule`、`eab.constraint.remedy`。租户视角下标品指令的片段 `redacted: true`，`text` 为占位文字。

## 评测

- `POST /studio/drafts/{id}/evals/generate` body `{testUser}` → `DraftView`（从已选语义包的 verified_tasks 生成评测集初稿，写入 evalYaml）
- `PUT /studio/drafts/{id}/evals` body `{yaml}` → `DraftView`
- `POST /studio/drafts/{id}/evals/run` → `EvalRunView`（同步，逐个用例运行草稿）

```ts
interface EvalRunView {
  id: string; status: 'passed' | 'failed'; total: number; passed: number; minPassRate: number; passRate: number
  cases: { id: string; user: string; question: string; passed: boolean; text: string; runId: string; traceId: string
           assertions: { type: string; description: string; passed: boolean; detail: string }[] }[]
}
```

## 静态检查与发布

- `POST /studio/drafts/{id}/lint` → `{ ok: boolean, issues: {rule, severity: 'error'|'warning', message, path}[] }`。规则：no-hardcoded-endpoint、tool-write-level、extension-point-declared、reference-resolvable、reference-scope、routing-overlap（场景与能力目录里已有场景过于相近）、assembly（试组装的问题）。
- `POST /studio/drafts/{id}/publish` body `{changeNote?}` → `{ref, version, deployment}`：写入注册中心并签名发布（Agent 与评测集），部署到 dev。已发布过同一内容时直接返回原版本；内容有变化时自动升补丁版本。静态检查有 error 时拒绝（409 `LINT_FAILED`，`error.details` 为 issues）。
- `GET /studio/deployments?ref=ns/name` → `{ environments: {dev?: Deployment, test?: Deployment, prod?: Deployment}, history: Deployment[] }`，`Deployment = {environment, version, deployedAt, deployedBy, note}`
- `POST /studio/deployments/promote` body `{ref, version, environment: 'test'|'prod'}` → `{deployment, gates: {name, passed, detail}[]}`。晋级 test 要求该版本已在 dev；晋级 prod 要求已在 test、静态检查没有 error、同一内容的评测通过。门禁不过时 409 `GATE_BLOCKED`，`error.details` 为 gates。
- `POST /studio/deployments/rollback` body `{ref, environment}` → `{deployment}`

租户新建的 Agent 晋级到 prod 后才进入统一入口的能力目录；标品资产不受影响。

## 连接中心

- `GET /studio/connections` → `{alias, type, ref, version, bindings: {private?, dev?, test?, prod?: {url, auth: {type, credentialRef?}}}, usedBy: string[], editable: boolean}[]`。`editable` 表示当前用户能否修改（租户管理员为 true）。`usedBy` 列出直接引用这个别名的工具来源和远程 Agent，以及挂载了这些工具来源的 Agent。
- `PUT /studio/connections/{alias}/bindings/{environment}` body `{url, authType, credentialRef?}` → 更新后的连接。`authType` 取 schema 允许的值：none、client-credentials、token-exchange、platform-service-token；`credentialRef` 只能是 `env:` 或 `vault:` 开头的引用，不能是凭据本身。每次修改写入 Connection 资产的新补丁版本并发布，写 `connection.binding` 审计；只有租户管理员可以改。

## 扩展标品 Agent（P6）

设计取舍见 ADR 0011。扩展草稿和新建 Agent 共用工作区草稿（kind 为 AgentExtension），`GET /studio/drafts/{id}`、`PUT /studio/drafts/{id}/yaml` 照常可用。

- `GET /studio/vendor-agents` → `VendorAgentSummary[]`：本租户可见的标品 Agent（最新发布版本），不含指令原文。
- `GET /studio/vendor-agents/{namespace}/{name}` → `{asset, summary: VendorAgentSummary, effective?: EffectiveView, issues}`。`asset` 是租户视角裁剪后的资产，`effective` 是叠加本租户已发布扩展后的有效定义。
- `POST /studio/vendor-agents/{namespace}/{name}/copy` → `{draft: DraftView, notice}`：复制为租户 Agent 的草稿（`tenant.<租户>.agents/<name>-copy`），指令是标品的公开版指令，`notice` 提示副本不再随标品升级。之后按新建 Agent 的流程测试、发布。
- `POST /studio/extensions` body `{target}`（标品 Agent 的 `namespace/name`）→ `DraftView`。本租户已有该标品的已发布扩展时从它开始编辑。
- `POST /studio/drafts/{id}/extension` body `{section, value}` → `DraftView`：保存一个扩展点分页，`section` 与 `value`：

| section | value |
|---|---|
| instructions | `append: string`、`constraints?: string[]`（规则编号或文字） |
| tools | `add: string[]`（工具来源引用） |
| skills | `add: string[]` |
| knowledge | `add: string[]`（知识源或语义包引用） |
| hooks | `{beforeToolCall?, afterToolCall?, onResult?, onFailure?: {workflow? \| skill?, when?}}` |
| approvals | `add: {tool, policy: 'require-confirmation' \| 'require-approval', description?}[]` |
| model | `profile: string`（只能选标品允许的档位） |

- `GET /studio/drafts/{id}/effective` → `{ok, view?: EffectiveView, issues, extension}`：以租户视角组装“标品叠加本扩展草稿”。
- `POST /studio/drafts/{id}/extension/test` body `{message, asUser, sessionId?}` → 与草稿测试相同的 SSE 运行事件（或 JSON），被测对象是标品叠加扩展草稿；确认用 `POST /studio/test-runs/{runId}/confirmation`。
- `POST /studio/drafts/{id}/extension/evals/generate` body `{testUser?}`（默认 wangfang）→ `DraftView`，按扩展内容生成扩展评测集初稿。
- `PUT /studio/drafts/{id}/extension/evals` body `{yaml}` → `DraftView`
- `POST /studio/drafts/{id}/extension/evals/run` → `{id, status: 'passed'|'failed', suites: {suite, visibility, minPassRate, total, passed, ok, cases}[]}`：同时运行标品公开评测集和扩展评测集，确认点一律拒绝。
- `POST /studio/drafts/{id}/extension/lint` → `{ok, issues: {rule, severity, message, path?}[]}`，规则同 Agent 的静态检查，另有试组装的问题（rule 为 assembly）。
- `POST /studio/drafts/{id}/extension/publish` body `{changeNote?}` → `{ref, version, unchanged, evalRun}`。发布即对本租户生效，没有环境晋级。依次检查：扩展评测集存在（409 `NO_EVALS`）、静态检查（409 `LINT_FAILED`）、MCP 审批（409 `MCP_NOT_APPROVED`）、同一内容的评测通过，没有就当场运行（409 `EVALS_FAILED`，`error.details` 为各评测集的结果）。

```ts
interface VendorAgentSummary {
  ref: string; name: string; description: string; version: string; writeLevel: string
  extensionPoints: { type: string; description: string; allowedProfiles?: string[]; events?: string[] }[] | null
  tools: Record<string, unknown>[] | null
  evals: string[] | null          // 公开评测集引用
  extensions: { ref: string; version?: string; draftId?: string; status: 'published' | 'draft' }[]
}

interface EffectiveView {
  name: string; displayName: string
  segments: { id: string; kind: string; layer: 'platform'|'vendor'|'tenant'; source: string; field: string
              text: string | { $redacted: true; label: string; digest: string }; label: string | null; confidential: boolean }[]
  tools: { name: string; type: string; layer: string; source: string; description: string | null; writeLevel: string | null }[]
  approvals: { tool: string; policy: string; when: string | null; layer: string; source: string }[]
  hooks: { event: string; layer: string; source: string; workflow: string | null; skill: string | null; when: string | null; failurePolicy: string }[]
  model: { profile: string; temperature: number | null; layer: string } | null
  provenance: { agent: string; extensions: string[] } | null
}
```

片段的 `kind` 有 platform-rule、vendor-instructions、vendor-context、vendor-constraint、semantic-instruction、tenant-instructions、tenant-constraint。租户视角下标品指令的 `text` 是 `{$redacted, label, digest}`，界面锁住显示。

## 工具中心（P6）

- `GET /studio/tools` → `{sources: ToolSourceView[], skills: SkillView[]}`
- `GET /studio/tools/bo-operations` → `{name, description, method, path, writeLevel, imported}[]`：读取 DemoERP 的 `/bo/v1/openapi.json`，`imported` 表示本租户的工具来源里已有。
- `POST /studio/tools/bo-import` body `{name, displayName, operations: string[]}` → `{ref, version, tools}`：生成 `tenant.<租户>.tools/<name>` 的 bo-api 工具来源（连接别名 DEMO_ERP），已存在时升次版本。
- `GET /studio/tools/mcp-tools?connection=<别名>` → `{name, description, inputSchema}[]`：用连接别名对应的 MCP Server 列出工具（private 环境的绑定）。
- `POST /studio/tools/mcp-servers` body `{name, displayName, connection, tools: {name, writeLevel}[]}` → `{ref, version, approval}`：生成 mcp 工具来源，同时提交审批（approval 为 pending）。
- `GET /studio/mcp-approvals` → `{id, source, status: 'pending'|'approved'|'rejected', requestedBy, requestedAt, decidedBy, decidedAt, note}[]`
- `POST /studio/mcp-approvals/{id}/decision` body `{approved, note?}` → 更新后的审批记录。只有租户管理员可以审批，写 `mcp.approval.decide` 审计。未审批通过的租户 MCP 工具来源不能用在 prod：Agent 晋级 prod 的门禁里有“MCP Server 已审批”一项，扩展发布时同样检查。

```ts
interface ToolSourceView {
  ref: string; version: string; name: string; layer: string; type: 'bo-api' | 'mcp' | 'http'; connection: string
  approval?: 'pending' | 'approved' | 'rejected' | 'unregistered'   // 只有 mcp 有
  usedBy: string[]                 // 引用这个工具来源的 Agent、扩展、技能
  tools: { name: string; description: string; writeLevel: string; category: string; usedBy: string[] }[]
}
interface SkillView { ref: string; version: string; name: string; layer: string; writeLevel: string; steps: number; usedBy: string[] }
```

## 技能编辑器（P6）

- `POST /studio/skills` body `{name, displayName?, from?}` → `DraftView`（kind 为 Skill，命名空间 `tenant.<租户>.skills`）。`from` 给出时复制已有技能，标签 `copiedFrom` 记录来源。
- `PUT /studio/drafts/{id}/skill-steps` body `{steps}` → `DraftView`：替换步骤列表，其余字段在 YAML 编辑模式里改。
- `POST /studio/drafts/{id}/skill-test` body `{input, asUser?}`（默认 zhangmin）→ `{status: 'ok'|'failed'|'confirmation', output, error, pending, steps, runId, traceId}`。以测试用户的身份直接执行技能，不经过模型；`steps` 是每一步的 `{id, name, type, status, input, output, error, durationMs}`，输入输出截断到 2000 字。
- `POST /studio/drafts/{id}/skill-publish` → `{ref, version, unchanged}`：写入注册中心并签名发布，内容变化时升补丁版本。

## 扩展需求与意图生成（P9）

统一入口“提交扩展需求”的 `POST /dev-requests` 增加 `supplement`（提出人的补充说明，最长 2000 字，存进 `spec.supplements`）；服务端按需求文本在本租户可见的语义包里检索相关的业务对象与规则（各取前 3 条），存进 `spec.context`。响应 `{ref, status, semanticObjects, rules, message}`，提出人由此知道哪些口径被带给了开发者。

需求处理的闭环在 Studio，接口都要求租户开发者或租户管理员：

- `GET /studio/requests/{namespace}/{name}` → `RequestView`
- `POST /studio/requests/{namespace}/{name}/plan` → `RequestPlan`（生成六步方案卡；首次生成时需求状态从 `submitted` 变为 `in-progress`）
- `PUT /studio/requests/{namespace}/{name}/plan` body `{plan}` → `RequestPlan`（保存修改后的方案卡）
- `POST /studio/requests/{namespace}/{name}/adopt` → `{drafts: {id, kind, ref, name}[]}`（把方案里的资产草稿落成 Studio 工作区草稿，评测集草稿挂到扩展草稿上；重复采纳返回同一批草稿）
- `POST /studio/requests/{namespace}/{name}/deliver` body `{summary?}` → `{status}`（发布完成后把需求标记为 `delivered`，并向提出人发通知，请他重新提问）
- `POST /studio/requests/{namespace}/{name}/reject` body `{reason?}` → `{status}`（拒绝需求并通知提出人）

```ts
interface RequestView {
  ref: string; originalText: string; requester: string; requesterName: string
  status: 'submitted' | 'in-progress' | 'delivered' | 'rejected'
  createdAt: string; updatedAt: string
  supplements: string[]            // 提出人的补充说明
  context: { assistant: string | null, semanticObjects: string[], rules: string[] }
  plan: RequestPlan | null
}

interface RequestPlan {
  decidedBy: 'model' | 'retrieval'   // 模型生成，或模型不可用时的检索兜底
  generatedAt: string
  clarifications: { question: string, options: string[] }[]     // 卡1 意图澄清，最多 3 条
  fitGap: {                                                       // 卡2 意图分析与 fit-gap
    verdict: 'satisfied' | 'extend' | 'new'                       // 标品已满足 / 扩展标品即可 / 需要新建
    score: number                                                 // 匹配度 0~1
    matched: { ref: string, reason: string }[]                    // 匹配到的已有能力
    reason: string                                                // 三档结论的依据
  }
  summary: { user: string, scenario: string, metrics: string[], boundaries: string }  // 卡3 需求摘要
  assets: { action: 'reuse' | 'create', kind: string, ref: string, note: string }[]   // 卡4 技术规格
  drafts: { kind: 'Skill' | 'AgentExtension', yaml: string }[]    // 卡5 资产草稿，YAML 里带 metadata
  evalSuite: { name: string, yaml: string } | null                // 卡6 评测集草稿
}
```

## 接入外部 Agent（P7）

外部 Agent 经 A2A 协议接入，设计见 ADR 0012。资产里只写连接别名，地址在连接中心按环境绑定；只能访问本机回环地址或已登记的连接地址。

- `POST /studio/external/inspect` body `{cardUrl}` → 拉取并解读 Agent Card（名称、技能、能力、认证方式），给出接入配置的建议
- `POST /studio/external/test` body `{cardUrl, message}` → 连通性测试：发一条消息，返回回复，以及 A2A 请求与响应的原文（令牌显示为 `Bearer ***`）
- `POST /studio/external/publish` body `{cardUrl, displayName, …}` → 把接入配置发布为 `tenant.<租户>.remote` 下的 RemoteAgent 资产，发布后进入能力目录
- `GET /studio/external` → 本租户已接入的外部 Agent，以及平台对外暴露的 A2A Agent（岗位助手和 Agent 需要声明 `{type: agent}` 触发器才对外暴露）

## 专业代码通道（P9）

设计取舍见 ADR 0014。专业开发者以 YAML 文件为中心工作：`eab` 命令行把资产工程拉到本地、放进 Git、再推回平台，草稿随时可以从界面导出为同构的代码工程（`GET /studio/drafts/{id}/export`）；Studio MCP Server 让 Claude Code 这类编码 Agent 查到业务上下文并直接校验、评测、发布。界面与文件是同一份数据的两种视图，工程清单 `eab-project.yaml` 里的 `draftId` 保证来回切换找回的是同一条草稿。

### eab 命令行

`pnpm eab <子命令>`。公共参数：`--url` 平台地址（默认读会话，再默认 `http://127.0.0.1:4000`）、`--session` 会话文件（默认 `~/.config/eab/cli-session.json`）、`--dir` 工程目录（默认当前目录）。登录令牌 8 小时有效，过期后重新 login。退出码 0 成功、1 失败、2 用法不对。

| 子命令 | 行为 | 串起来的接口 |
|---|---|---|
| `login --user <用户> [--print-token]` | 登录并保存会话；`--print-token` 输出裸令牌，供 MCP 客户端的 Authorization 头 | `POST /identity/v1/login` |
| `init --template agent\|extension\|a2a-python\|a2a-ts --name <名称> [--target <标品Agent>]` | 本地解包模板建工程，不访问平台 | 无 |
| `pull <namespace/name>[@版本]` 或 `--draft <草稿编号>` | 把资产或草稿拉成本地工程；资产走租户视角（标品指令为占位） | `GET /registry/assets/{ns}/{name}`、`GET /studio/drafts/{id}` |
| `push` | 逐资产找回（或新建）同一条草稿并整份替换 YAML；评测集推到主草稿的 evalYaml；draftId 回写清单 | `GET /studio/home`、`POST /studio/drafts`、`POST /studio/extensions`、`POST /studio/skills`、`PUT /studio/drafts/{id}/yaml`、`PUT /studio/drafts/{id}/evals`（扩展走 `/extension/evals`） |
| `validate` | 平台侧批量校验工程里的资产；工程内资产相互引用（扩展引用还没发布的评测集与技能）时在叠加层里一起解析，不写入 | `POST /registry/validate-batch` body `{assets: string[]}`，一次最多 20 份，返回逐份的 `{valid, issues, lint}` |
| `eval [--generate] [--user <测试用户>]` | 先 push，再按草稿类型生成（可选）与运行评测；失败退出 1 | `POST /studio/drafts/{id}/evals/generate`、`POST /studio/drafts/{id}/evals/run`、`POST /studio/drafts/{id}/extension/evals/run` |
| `deploy [--note <说明>] [--promote test\|prod]` | 先 push 再发布；Agent 可接着晋级；a2a 工程先探测本地 Agent，经接入通道登记为远程 Agent | `POST /studio/drafts/{id}/publish`、`/extension/publish`、`/skill-publish`、`POST /studio/deployments/promote`、`POST /studio/external/inspect`、`POST /studio/external/publish` |
| `logs [--agent <namespace/name>] [--limit 20] [--trace <追踪编号>]` | 运行记录与追踪树 | `GET /runs`、`GET /traces/{traceId}` |

### Studio MCP Server

端点 `/studio-mcp`，与 `/mcp` 同栈：Streamable HTTP 无状态传输（ADR 0002），每次调用独立鉴权，Bearer 用户令牌，角色必须是租户开发者或租户管理员；角色不符或令牌缺失返回 isError 的工具结果而不是 HTTP 错误。工具的返回值是 JSON 序列化后的文本。评测与部署类工具按用户限流（每分钟 30 次）。

| 工具 | 参数 | 行为 |
|---|---|---|
| `semantic_describe_object` | `object`（必填，业务对象，如 `erp.pur.Supplier` 或 供应商） | 语义包查询，与 `/mcp` 端点相同，按当前用户的权限执行并写审计 |
| `semantic_get_metric` | `name`（必填，指标名或显示名） | 同上 |
| `semantic_find_rules` | `object`、`keyword`（都可选，关键词或规则编号） | 同上 |
| `semantic_search` | `query`（必填）、`limit`（1~20，可选） | 同上 |
| `studio_list_capabilities` | 无 | 能力目录：流程、Agent、语义包、知识、技能、工具与模型档位 |
| `studio_list_vendor_agents` | 无 | 可扩展的标品 Agent 清单与各自的扩展点 |
| `studio_get_vendor_agent` | `namespace`、`name`（必填） | 一个标品 Agent 的详情与扩展点 |
| `studio_list_drafts` | `limit`（1~100，可选，默认 30） | 本租户的草稿列表（id、类型、资产引用、意图与步骤） |
| `studio_get_draft` | `draftId`（必填） | 草稿详情：YAML、评测集与静态检查结果 |
| `studio_validate_asset` | `yaml`（必填）、`evalYaml`（可选，随扩展一起校验） | 校验资产 YAML（schema 与静态检查），不写入平台 |
| `studio_generate_evals` | `draftId`（必填）、`testUser`（可选，默认 zhangmin） | 为草稿生成评测集初稿 |
| `studio_run_evals` | `draftId`（必填） | 运行草稿的评测集（评测里确认点一律自动拒绝） |
| `studio_deploy_draft` | `draftId`（必填）、`note`（可选） | 发布草稿：Agent 发布到 dev；扩展与技能发布后对本租户即时生效 |
| `studio_promote` | `ref`、`version`、`environment`（必填，test 或 prod） | 把租户 Agent 晋级到 test 或 prod，门禁不过时报错并列出门禁 |

## 演示控制台（P10）

演示控制台是界面右上角的隐藏菜单（主提示词 17 节），供演示走查：重置、跳到各故事起点、一键生成完成态、触发业务事件、发布标品新版本、切换演示身份、集成账号对照。演示状态的三个接口只在演示模式下开放（未开启时 404），且只对租户管理员开放（403），按用户限流每分钟 10 次。

- `POST /demo/v1/reset` → `{erpReset: true, delegation: "DLG-0001"}`。与 `pnpm seed:reset` 等价：先让 DemoERP 按今天重新生成业务数据，再清空平台的运行期数据（运行、追踪、审计、业务事件、委托、通知、需求与治理数据），撤回演示过程中发布的租户资产并重建演示委托。DemoERP 不可达时返回 502（`ERP_UNREACHABLE`）或 `ERP_RESET_FAILED`，平台数据不动。
- `POST /demo/v1/story-start` body `{story}` → `{story, published: string[]}`。跳到某个故事的起点，`story` 只能是 A 到 F，否则 400。A 到 E 为纯重置；F 在重置之上发布完成态快照（`tenants/<租户>` 下的扩展、技能与评测集），快照是该租户完成态的唯一权威，发布前先清空该租户已发布资产，避免与演示中途发布的扩展同名冲突。发布失败时 500 `SEED_FAILED`，`error.details` 列出失败文件与原因。
- `POST /demo/v1/complete` → `{published: string[], history: {runs, metricPoints}}`。完成态在故事 F 起点之上再叠加 30 天模拟历史（数据带模拟标记，重置时一并清掉），治理中心与架构视图有趋势数据可看。

其余操作复用既有接口：触发业务事件用 `POST /demo/v1/events`（演示模式下任意租户用户）；模拟发布标品新版本用 `POST /hub/demo/upgrade`（要求租户管理员）；集成账号对照用 `GET /hub/demo/integration-compare?user=<用户>`（演示模式下任意租户用户）；切换身份是纯前端行为，复用身份切换器的演示用户列表。

架构视图（顶部第四个导航项）用 `GET /runs` 选运行（租户管理员可见本租户全部运行）、`GET /traces/{traceId}` 取 span 时间线做回放，都是既有接口，没有新增。

DemoERP 侧的重置端点 `POST /demo/v1/reset`（DemoERP 端口上，不经前端代理）只接受平台内部调用：请求头 `x-eab-internal-token` 匹配时按当天重新生成种子数据并返回 202，其余返回 401。平台先重置 DemoERP、成功后才动自己的数据。

# Studio 接口

Studio（开发态）调用的平台接口。前端经同源的 `/api` 访问，下面的路径省略 `/api` 前缀。响应都是统一信封 `{success, data, error}`，错误时 `error` 带 `code` 和 `message`。身份只来自 Bearer 用户令牌，Studio 接口要求租户开发者（`tenant-developer`）或租户管理员（`tenant-admin`），只能操作本租户的资产。

设计取舍见 ADR 0010：草稿存在可修改的工作区里，测试和评测时以叠加方式交给运行时（私有环境）；发布时写入注册中心成为签名版本，再按 dev → test → prod 晋级。

## 数据结构

```ts
interface DraftView {
  draft: {
    id: string; intent: string; step: number; kind: 'Agent'
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

- `GET /studio/home` → `{ drafts: DraftView['draft'][], agents: {ref, name, version, environments: {dev?: string, test?: string, prod?: string}}[], devRequests: {ref, originalText, requester, status, createdAt}[] }`
- `GET /studio/catalog` → `{processes, agents, semanticPacks, knowledge, skills, tools, channels, profiles}`

## 草稿与向导

- `POST /studio/drafts` body `{intent}` → `DraftView`
- `GET /studio/drafts/{id}` → `DraftView`
- `POST /studio/drafts/{id}/recommend` body `{step}` → `Recommendation`（经模型网关，脚本化模型下按剧本输出）
- `POST /studio/drafts/{id}/apply` body `{step, value}` → `DraftView`
- `PUT /studio/drafts/{id}/yaml` body `{yaml}` → `DraftView`（YAML 编辑模式；有 schema 问题时仍保存，issues 里列出）
- `DELETE /studio/drafts/{id}`

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

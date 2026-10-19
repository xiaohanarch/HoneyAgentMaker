# 专业代码通道实录：从扩展需求到上线

这是 P9 专业代码通道的完整实录（设计见 ADR 0014，接口契约见 `docs/studio-api.md` 的"专业代码通道"一节）。场景沿用故事 E：张敏在统一入口问标品采购订单处理 Agent 办不到的事，Agent 如实说明缺什么，张敏提交扩展需求并补上"金额超过 10 万的必须人工审批"；平台按需求文本检索语义，把业务对象 `erp.pur.SupplierAlternative` 和规则 PUR-R-008 附带给开发者。本篇从开发者陈工接到这个需求开始：在一个新的 Claude Code 会话里，经 Studio MCP Server 查业务上下文，用 `eab` 命令行把三份资产从本地文件推到上线，最后张敏重新提问拿到结果。所有命令输出都是实跑采集的。

与低代码向导的关系：Studio 界面与代码工程是同一份数据的两种视图。工程清单 `eab-project.yaml` 里的 draftId 在 push 后回写，来回切换始终找回同一条草稿；界面里的草稿也能随时"导出为代码工程"，产物与 `eab init` 同构。

## 准备

平台在跑（`pnpm demo`），开发者用本租户的开发者账号登录一次，令牌 8 小时有效：

```text
$ pnpm eab login --user chengong --print-token
已登录 陈工（chengong，http://127.0.0.1:4000），会话在 C:\Users\HUAWEI\.config\eab\cli-session.json
eyJraWQiOiJlYWItaWRlbn…（令牌输出，供 MCP 客户端使用；会话文件在仓库外，不进 Git）
```

把 Studio MCP Server 配进编码 Agent。Claude Code 的 `.mcp.json`：

```json
{
  "mcpServers": {
    "eab-studio": {
      "type": "http",
      "url": "http://127.0.0.1:4000/studio-mcp",
      "headers": {
        "Authorization": "Bearer <pnpm eab login --print-token 的输出>"
      }
    }
  }
}
```

`pnpm eab` 是平台仓库的 pnpm 脚本：在平台仓库里运行时给命令加 `--dir <工程目录>`；在工程目录里运行时用 `pnpm --dir <平台仓库> eab …`，`--dir` 可以省（实测两种方式等价，下文统一用后者）。也可以设一次别名 `alias eab='pnpm --dir <平台仓库> eab'`，之后 `eab validate` 就是普通命令。

## 在编码会话里查业务上下文

新开一个 Claude Code 会话，把需求原话和补充说明交给它。要写扩展，先得知道三件事：大额审批的规则到底怎么表述、备选供应商有哪些字段、目标 Agent 开放了哪些扩展点。这些都在 Studio MCP 的工具里，语义查询按陈工的权限执行并写审计。四个关键调用与真实返回：

规则用 `semantic_find_rules` 查关键词"大额请购"：

```json
{"total":1,"items":[{"id":"PUR-R-008","ref":"erp.pur.rules.requisition_amount_confirm",
  "severity":"block","description":"大额请购须人工确认",
  "remedy":"金额超过 10 万元的请购单草稿提交前必须向用户展示供应商、数量与金额并取得确认",
  "semanticPack":"vendor.pur/procurement@1.2.0"}]}
```

拿到规则编号 PUR-R-008。它是可检查的语义规则，扩展里引用编号即可在运行时得到守卫；确认点写进 approvals，条件引用工具入参的金额字段。

业务对象用 `semantic_describe_object` 查 `erp.pur.SupplierAlternative`：

```json
{"ref":"erp.pur.SupplierAlternative","label":"备选供应商",
 "description":"物料的认证备选供应商，带协议单价、现货数量与备货周期，按采购组织过滤",
 "fields":[
   {"name":"materialId","type":"string","label":"物料"},
   {"name":"supplierId","type":"string","label":"供应商"},
   {"name":"unitPrice","type":"number","label":"协议单价"},
   {"name":"stockQty","type":"number","label":"现货数量","description":"现货数量，判断能否立即补货的依据"},
   {"name":"leadTimeDays","type":"number","label":"备货周期天数"}],
 "relationships":[
   {"from":"erp.pur.SupplierAlternative","to":"erp.pur.Supplier","verb":"备选于","cardinality":"many-to-one"},
   {"from":"erp.pur.PurchaseRequisition","to":"erp.pur.SupplierAlternative","verb":"依据",
    "notes":"请购单只能发给该物料的认证备选供应商，单价取协议价"}],
 "permissionDimension":"采购组织","provenance":{"semanticPack":"vendor.pur/procurement@1.2.0","metadata":"erp.pur.SupplierAlternative@v1"}}
```

字段清单直接决定技能怎么写：现货够不够看 `stockQty`，金额按缺口数量乘 `unitPrice` 算。

目标 Agent 用 `studio_list_vendor_agents` 找到采购订单处理 Agent（`vendor.pur/purchase-order-clerk`），再用 `studio_get_vendor_agent` 看详情：它开放 instructions.append、tools.add、skills.add、approvals.add 等扩展点，写级别上限 L2，已有 BO 工具是订单查询与交期变更，没有备选供应商查询和请购单创建。标品指令原文对租户是占位块（`{"$redacted":true,"label":"标品指令（不可见，运行时注入）"}`），公开版指令说明它缺少能力时会如实告知并给出提交扩展需求的入口。

## 建工程并写资产

```text
$ pnpm --dir <平台仓库> eab init --template extension --target vendor.pur/purchase-order-clerk --name purchase-order-clerk-ext --dir story-e-ext
已创建工程 …\story-e-ext（模板 extension）
下一步：cd …\story-e-ext，编辑 assets/ 下的 YAML，然后 pnpm eab validate

story-e-ext/
├── eab-project.yaml                        # 工程清单：模板、平台地址、资产索引
├── README.md
├── .gitignore
├── assets/purchase-order-clerk-ext.yaml    # 扩展资产
├── evals/purchase-order-clerk-ext-smoke.yaml
└── skills/purchase-order-clerk-ext-skill.yaml
```

模板骨架里的命名空间是 `tenant.<改我>.` 占位，推平台前必须换成真实租户（init 不访问平台，所以不知道租户编号；不改的话 validate 会逐处报出来）。把三份资产写进对应文件，文件名只是工程内的位置，资产身份由 YAML 里的 metadata 决定。

扩展资产 `assets/purchase-order-clerk-ext.yaml`，target 带版本范围，追加的指令、工具、技能、约束、确认点都落在扩展点上：

```yaml
apiVersion: eab.dev/v1
kind: AgentExtension
metadata:
  name: purchase-order-clerk-ext
  namespace: tenant.t001.pur
  version: 1.0.0
  displayName: 采购订单处理 · 星海制造扩展
  description: 订单交付有问题时从认证备选供应商补货；增加备选供应商补货技能，金额超过 10 万元的请购单草稿须人工确认
  owner: chengong
spec:
  target: vendor.pur/purchase-order-clerk@^1.2
  instructions:
    append: |
      本公司规定：金额超过 10 万元的请购单草稿，提交前必须向用户展示供应商、数量与金额并取得人工确认。
      采购订单交付出现问题、需要从备选供应商补货时，调用 alternative-supplier-lookup 技能并传入订单号 poId：
      技能会查询订单缺口和现货足够的认证备选供应商，有现货时按协议价创建请购单草稿（提交前会请用户确认），
      没有现货时建议对物料发起询价流程。不要直接调用 purchase_requisition_draft_create，
      请购数量、协议价和金额由技能按订单缺口与备选记录计算。
  tools:
    add:
      - api:
          source: vendor.erp.tools/demo-erp-bo
          tools: [purchase_requisition_draft_create]
  skills:
    add: [tenant.t001.skills/alternative-supplier-lookup]
  constraints:
    append: [PUR-R-008]
  approvals:
    add:
      - tool: purchase_requisition_draft_create
        policy: require-confirmation
        when: 'input.amount > 100000'
        description: 金额超过 10 万元的请购单草稿须人工确认（本公司大额请购规定）
  evals:
    suites: [tenant.t001.evals/purchase-clerk-ext-smoke]
```

技能 `skills/purchase-order-clerk-ext-skill.yaml`（`alternative-supplier-lookup`）把补货写成确定性的步骤树：查订单缺口，有缺口就查现货足够的认证备选，有备选按协议价创建请购单草稿，金额在技能外的确认点把关；没有现货时建议询价：

```yaml
apiVersion: eab.dev/v1
kind: Skill
metadata:
  name: alternative-supplier-lookup
  namespace: tenant.t001.skills
  version: 1.0.0
  displayName: 备选供应商补货
  description: 按采购订单的未交付缺口查询现货足够的认证备选供应商，有现货时按协议价创建请购单草稿，没有时建议走询价流程
  owner: chengong
spec:
  writeLevel: L2
  agentOnly: false
  inputSchema:
    type: object
    required: [poId]
    properties:
      poId: { type: string, description: 采购订单号，例如 PO-2026-0117 }
  outputSchema:
    type: object
    properties:
      requisitionCreated: { type: boolean, description: 是否创建了请购单草稿 }
      requisitionId: { type: string, description: 请购单草稿号 }
      amount: { type: number, description: 请购金额 }
      suggestion: { type: string, description: 没有创建草稿时的建议 }
  steps:
    - id: order
      type: tool
      name: 查询订单与缺口
      tool: purchase_order_get
      source: vendor.erp.tools/demo-erp-bo
      with: { poId: input.poId }
      output: order
    - id: has-gap
      type: condition
      name: 有没有未交付的缺口
      if: 'order.item.lines.exists(l, l.remainingQuantity > 0)'
      then:
        - id: shortage
          type: transform
          name: 取第一个未交付完的订单行
          expression: 'order.item.lines.filter(l, l.remainingQuantity > 0)[0]'
        - id: alternatives
          type: tool
          name: 查询现货足够的认证备选
          tool: supplier_alternative_list
          source: vendor.erp.tools/demo-erp-bo
          with: { materialId: shortage.materialId, stockQtyGte: shortage.quantity }
          output: candidates
        - id: has-stock
          type: condition
          name: 有没有现货足够的备选
          if: 'candidates.items.size() > 0'
          then:
            - id: draft
              type: tool
              name: 按协议价创建请购单草稿，金额超过 10 万元须人工确认
              tool: purchase_requisition_draft_create
              source: vendor.erp.tools/demo-erp-bo
              with:
                supplierId: 'candidates.items[0].supplierId'
                materialId: 'shortage.materialId'
                quantity: 'shortage.quantity'
                unitPrice: 'candidates.items[0].unitPrice'
                amount: 'shortage.quantity * candidates.items[0].unitPrice'
                sourcePoId: 'order.item.id'
                note: "'订单 ' + order.item.id + ' 交付存在缺口，按备选供应商 ' + candidates.items[0].supplierName + ' 的协议价补货'"
              output: draft
            - id: summary
              type: transform
              name: 汇总请购结果
              expression: "{'requisitionCreated': true, 'requisitionId': draft.item.id, 'materialId': draft.item.materialId, 'materialName': shortage.materialName, 'unit': shortage.unit, 'quantity': draft.item.quantity, 'supplierId': draft.item.supplierId, 'supplierName': candidates.items[0].supplierName, 'unitPrice': draft.item.unitPrice, 'amount': draft.item.amount, 'currency': draft.item.currency, 'status': draft.item.status, 'sourcePoId': draft.item.sourcePoId, 'originalSupplier': order.item.supplierName}"
          else:
            - id: rfq
              type: transform
              name: 没有现货足够的备选，建议询价
              expression: "{'requisitionCreated': false, 'materialId': shortage.materialId, 'materialName': shortage.materialName, 'quantity': shortage.quantity, 'sourcePoId': order.item.id, 'suggestion': '没有现货足够的认证备选供应商，建议对物料 ' + shortage.materialId + ' 发起询价流程'}"
      else:
        - id: no-gap
          type: transform
          name: 订单没有缺口
          expression: "{'requisitionCreated': false, 'sourcePoId': order.item.id, 'suggestion': '订单 ' + order.item.id + ' 已全部交付，没有需要补货的缺口'}"
```

评测集 `evals/purchase-order-clerk-ext-smoke.yaml`，断言走技能且回复说明请购单草稿的处理结果：

```yaml
apiVersion: eab.dev/v1
kind: EvalSuite
metadata:
  name: purchase-clerk-ext-smoke
  namespace: tenant.t001.evals
  version: 1.0.0
  displayName: 采购订单处理扩展 · 冒烟集
  owner: chengong
spec:
  target: tenant.t001.pur/purchase-order-clerk-ext
  visibility: internal
  tier: smoke
  minPassRate: 1
  cases:
    - id: alternative-requisition
      user: zhangmin
      question: 百润精密这批货到不了，帮我从备选供应商里找有现货的下单
      assertions:
        - { type: calledTool, tool: alternative-supplier-lookup, description: 走备选供应商补货技能 }
        - { type: contains, text: 请购单草稿, description: 回复说明请购单草稿的处理结果；评测里确认点被拒绝时说明原因 }
```

## 上线四步

validate 在平台侧批量校验，工程内还没发布的技能和评测集在叠加层里一起解析，不写入：

```text
$ pnpm --dir <平台仓库> eab validate
✓ assets/purchase-order-clerk-ext.yaml
✓ evals/purchase-order-clerk-ext-smoke.yaml
✓ skills/purchase-order-clerk-ext-skill.yaml
```

push 不直接写注册中心，而是逐资产找回（或新建）同一条 Studio 草稿再整份替换 YAML；评测集不是独立资产，推到主草稿的 evalYaml 槽。draftId 回写进工程清单：

```text
$ pnpm --dir <平台仓库> eab push
  新建 assets/purchase-order-clerk-ext.yaml → 草稿 drf-319b9e50（tenant.t001.pur/purchase-order-clerk-ext）
  新建 skills/purchase-order-clerk-ext-skill.yaml → 草稿 drf-f250ea78（tenant.t001.skills/alternative-supplier-lookup）
  更新 evals/purchase-order-clerk-ext-smoke.yaml → 草稿 drf-319b9e50 的评测集
```

```yaml
# eab-project.yaml（push 后）
template: extension
platform: http://127.0.0.1:4000
target: vendor.pur/purchase-order-clerk
assets:
- file: assets/purchase-order-clerk-ext.yaml
  kind: AgentExtension
  ref: tenant.t001.pur/purchase-order-clerk-ext
  draftId: drf-319b9e50
- file: evals/purchase-order-clerk-ext-smoke.yaml
  kind: EvalSuite
  draftId: drf-319b9e50
- file: skills/purchase-order-clerk-ext-skill.yaml
  kind: Skill
  ref: tenant.t001.skills/alternative-supplier-lookup
  draftId: drf-f250ea78
a2a: false
```

eval 先 push 再跑。扩展的评测与发布要求技能已发布，所以技能先发布（内容不变时沿用已发布版本），然后同时运行标品公开评测集与扩展评测集，评测里确认点一律拒绝：

```text
$ pnpm --dir <平台仓库> eab eval
  先发布技能（扩展的评测与发布要求技能已发布，内容不变时沿用已发布版本）
  ✓ 技能已发布 tenant.t001.skills/alternative-supplier-lookup@1.0.0
  评测记录 evr-2918b700
✓ tenant.t001.evals/purchase-clerk-ext-smoke  1/1，最低通过率 1.0
  ✓ alternative-requisition（zhangmin） 百润精密这批货到不了，帮我从备选供应商里找有现货的下单
```

deploy 发布扩展，发布即对本租户生效，不经环境晋级：

```text
$ pnpm --dir <平台仓库> eab deploy --note "备选供应商补货与大额请购人工确认"
✓ 已发布 tenant.t001.pur/purchase-order-clerk-ext@1.0.0（租户扩展发布即生效）
```

运行记录随时可查（MCP 语义调用也在这里留审计）：

```text
$ pnpm --dir <平台仓库> eab logs --limit 3
✓ 2026-10-19 03:20:15…  vendor.pur/purchase-order-clerk  百润精密这批货到不了，帮我从备选供应商里找有现货的下单  追踪 d5bc1234…
✓ 2026-10-19 03:19:45…  vendor.pur/purchase-order-clerk  百润精密这批货到不了，帮我从备选供应商里找有现货的下单  追踪 49a5b68c…
✓ 2026-10-19 03:10:10…  platform/mcp-endpoint  平台工具调用 semantic.describe_object  追踪 56da1802…
看某次的详细追踪：pnpm eab logs --trace <追踪编号>
```

## 张敏重新提问

张敏在统一入口重新提问，路由到采购订单处理 Agent，扩展生效后走补货技能：查到 PO-2026-0117 的交付缺口与现货足够的认证备选 S-0009，按协议价算出金额 112000 元，超过 10 万元，扩展里的确认点先弹卡片：

```json
{"type": "confirmation_required", "agent": "vendor.pur/purchase-order-clerk",
 "data": {"title": "确认执行 purchase_requisition_draft_create",
   "preview": {"tool": "purchase_requisition_draft_create", "writeLevel": "L2",
     "arguments": {"amount": 112000, "materialId": "M-0007",
       "note": "订单 PO-2026-0117 交付存在缺口，按备选供应商 启达电子 的协议价补货",
       "quantity": 2000, "sourcePoId": "PO-2026-0117", "supplierId": "S-0009", "unitPrice": 56},
     "reason": "purchase_requisition_draft_create 是需确认的写操作（L2）；purchase_requisition_draft_create 声明了人工确认"}}}
```

张敏确认后（`POST /runs/{runId}/confirmation`），拿到答复：

```text
已按备选供应商 启达电子（S-0009）的协议价创建请购单草稿 PR-2026-0001：精密级连接器（M-0007）× 2000.0件，
单价 56.0 元，金额 112000.0 CNY。

原订单 PO-2026-0117（百润精密）交付存在缺口，本次按认证备选补货；按本公司规定，金额超过 10 万元的
请购单草稿已向你展示供应商、数量与金额并经确认。草稿当前状态为 draft，线下审批后可转采购订单。
```

结构化输出里 `capabilityGap` 已变为 false。回放这条链路的完整过程可以用 `pnpm eab logs --trace <追踪编号>`，租户视角下标品指令片段是不可见的占位块。

## 回到文件

在 Studio 界面打开这条草稿，点"导出为代码工程"，或直接调 `GET /studio/drafts/{id}/export`，拿到与 init 同构的工程（`purchase-order-clerk-ext-project.zip`）：

```text
assets/purchase-order-clerk-ext.yaml
evals/purchase-clerk-ext-smoke.yaml
eab-project.yaml        # 清单里带着 draftId
README.md
.gitignore
```

解包后修改再 `eab push`，找回的还是同一条草稿，内容以文件为准。一个已知行为：发布会把版本号写回草稿并按平台的 YAML 序列化格式重写一遍，所以发布后导出的文件与最初手写的文件在格式上可能有差异（列表缩进、引号），语义相同；`EabCliEndToEndTest` 里有导出、解包、再 push、内容语义一致的回环断言。把工程放进 Git 后，`git diff` 能看到每次变更的内容。

## 演示恢复

故事 E 的缺口演示依赖"扩展尚未发布"的初始状态。pro-code 演示发布过这三份资产后，运行 `pnpm seed:reset` 恢复：平台会清空运行期数据并显式撤回本演示发布的扩展、技能与评测集（这些命名空间不在启动入库范围，不撤回就会残留），张敏的提问回到缺口回答，整个故事可以重新讲一遍。

## 速查

`eab` 子命令（公共参数 `--url`、`--session`、`--dir`；退出码 0 成功、1 失败、2 用法不对）：

| 子命令 | 用途 |
|---|---|
| `login --user <用户> [--print-token]` | 登录保存会话；`--print-token` 输出裸令牌供 MCP 客户端 |
| `init --template agent\|extension\|a2a-python\|a2a-ts --name <名称> [--target <标品Agent>]` | 本地解包模板建工程，不访问平台 |
| `pull <namespace/name>[@版本]` 或 `--draft <草稿编号>` | 把资产或草稿拉成本地工程 |
| `push` | 逐资产找回（或新建）同一条草稿并整份替换 YAML，draftId 回写清单 |
| `validate` | 平台侧批量校验，工程内引用在叠加层里解析，不写入 |
| `eval [--generate] [--user <测试用户>]` | 先 push 再生成（可选）与运行评测 |
| `deploy [--note <说明>] [--promote test\|prod]` | 先 push 再发布；Agent 可接着晋级；a2a 工程走接入通道 |
| `logs [--agent <namespace/name>] [--limit 20] [--trace <追踪编号>]` | 运行记录与追踪树 |

Studio MCP 的 14 个工具（`semantic_describe_object`、`semantic_get_metric`、`semantic_find_rules`、`semantic_search`、`studio_list_capabilities`、`studio_list_vendor_agents`、`studio_get_vendor_agent`、`studio_list_drafts`、`studio_get_draft`、`studio_validate_asset`、`studio_generate_evals`、`studio_run_evals`、`studio_deploy_draft`、`studio_promote`）的参数与行为见 `docs/studio-api.md`。

## 常见问题

**令牌过期。** 登录令牌 8 小时有效，过期后 CLI 与 MCP 都返回 401，重新 `pnpm eab login --user <用户>` 并更新 `.mcp.json` 里的令牌。

**在仓库外运行 eab。** `eab` 是平台仓库的 pnpm 脚本。在工程目录里用 `pnpm --dir <平台仓库> eab …`（`--dir` 可省，工程目录自动成为默认目录），或设别名。直接 `pnpm eab` 只在平台仓库内可用。

**模板骨架校验失败。** init 的骨架带 `tenant.<改我>.` 占位，validate 会逐处报"命名空间不合法"，把租户编号、显示名、指令换成真实内容即可。

**业务用户被拒。** Studio MCP 与 Studio 接口只对租户开发者与租户管理员开放，其他角色收到 isError 工具结果或 403，说明原因。CLI 用对应账号重新登录。

**平台没在运行。** 连接失败时 CLI 提示先 `pnpm demo`。

**发布被 409 拦下。** 门禁错误带 code 与明细：`NO_EVALS` 先生成评测集；`LINT_FAILED` 按 issues 修静态检查；`EVALS_FAILED` 按评测结果修资产或评测；`MCP_NOT_APPROVED` 等租户管理员审批 MCP Server。`GATE_BLOCKED`（晋级）逐条列门禁。修完重新 `eab push` 再 `eab deploy`。

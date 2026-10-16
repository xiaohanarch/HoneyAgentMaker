# 演示剧本

脚本化模型（默认的模型提供方）读取这个目录下的 `*.yaml`，按“Agent 加用户话术”匹配剧本，产出确定性的工具调用和回复，断网、没有 API Key 时演示也能完整跑通。设计见 `docs/decisions/0008-agent-runtime.md`。

## 格式

```yaml
scripts:
  - id: pur-supplier-performance            # 小写字母、数字和连字符，全目录唯一
    description: 一句话说明
    agent: vendor.pur/supplier-performance-analyst
    match:
      all: [表现]                            # 全部出现才匹配，可省略
      any: [表现, 绩效]                      # 任意一个出现即可，可省略
      pattern: '(?<supplier>百润精密|恒通物流)' # 正则，命名分组写入 vars，可省略
      user: zhangmin                         # 只对某个用户生效，可省略
    vars: { supplier: 百润精密 }             # 变量默认值，命名分组会覆盖
    steps:
      - thought: 先确认供应商 {{vars.supplier}}  # 思考步骤摘要，展示给用户
        calls:
          - { tool: supplier-lookup, args: { supplierName: '{{vars.supplier}}' } }
      - reply: |
          {{vars.supplier}} 有 {{tools.purchase_order_list.total}} 张逾期订单
        output:
          maxOverdueDays: '=tools.purchase_order_list.items[0].overdueDays'
```

每一步要么是 `calls`（一批工具调用），要么是 `reply`（最终回复）。模型按本轮已经完成的工具调用批次数走到对应的一步；步数用完时停在最后一步。

## 模板与表达式

- 字符串按 mustache 渲染，不做 HTML 转义。缺少的变量渲染为空字符串。
- 以 `=` 开头的值按 CEL 求值，用于工具参数和结构化结果，例如 `'=size(tools.x.items.filter(i, i.status == "failed"))'`。
- 渲染为空字符串的参数视为没有提供，便于写可选参数。
- 可用的变量：
  - `vars`：剧本变量与正则命名分组；
  - `tools`：本轮的工具结果，工具名里的点和连字符换成下划线，例如 `tools.supplier_lookup`；同名工具取最后一次；
  - `user.id`、`today`（YYYY-MM-DD）、`month`（YYYY-MM）；
  - `output`：只在 `reply` 里可用，是同一步的结构化结果，先求值再渲染回复。
- 列表可以用下标和长度：`{{tools.x.items.0.id}}`、`{{tools.x.items.size}}`；分隔符可以用 `{{^-last}}、{{/-last}}`。

## 约定

- 回复里的单号、数量、金额都从工具结果取，不要写死。输出守卫会核对回复里的单号和金额，找不到的标注“未经核实”。
- 新增演示话术时，同时加到对应 Agent 的 `routing.examples`，`DemoScriptsTest` 会检查每个标品 Agent 的第一条示例话术都能匹配到剧本。
- 剧本没有覆盖的话术，脚本化模型回退到规则回复，列出该 Agent 的示例话术，并标注“脚本化模型”。

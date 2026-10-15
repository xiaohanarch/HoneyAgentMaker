# ADR 0003 钩子与确认点条件使用 CEL

状态：已采纳（P1，2026-10-15）

## 背景

资产里有几处需要写条件表达式：扩展钩子的 `when`（主提示词 5.3 节示例 `result.severity in ['high','critical']`）、确认点的 `when`（故事 E 的“金额超过 10 万须审批”）、工作流的条件分支和数据转换、评测集的不变量断言。

这些表达式由租户开发者编写，在平台进程里求值，因此有三个要求：

1. 不能执行任意代码，不能用 `eval` 或 `new Function`。
2. 能在发布前做静态检查，而不是等到运行时才发现写错。
3. 故事 F 要求：标品升级把输出字段改名后，依赖旧字段的租户钩子要被确定性地发现，不能静默地一直求值为 false。

## 决策

使用 Common Expression Language（CEL），实现库选 `@marcbachmann/cel-js` 8.0（MIT 许可，零依赖）。

- 钩子条件按事件准备类型环境。`onResult` 的 `result` 按标品 Agent 声明的 `spec.output.schema` 注册字段类型；`onFailure` 提供 `error`；`beforeToolCall`、`afterToolCall` 提供 `tool`、`input`、`output`。确认点条件提供 `input` 和 `user`。
- 组装时用 `Environment.check` 做类型检查。条件引用了不存在的字段或者结果不是布尔值时，标品钩子报错，组装失败；租户钩子停用并给出警告，主流程不受影响（主提示词 5.4 节第 4 条），发布门禁和升级回归会把这条警告作为失败项。
- 输出字段类型 `int` 在 CEL 环境里按 `double` 注册，运行时不需要把数字转成 BigInt，比较运算照常可用。

## 理由

- CEL 本身就是为策略和配置里的条件表达式设计的：无副作用、没有循环、求值时间有上限，在 Kubernetes 准入策略、Google IAM 条件里都有大规模使用，架构师比较熟悉。
- 主提示词示例里的写法本身就是合法的 CEL，不需要改写资产。
- 这个库提供带类型声明的环境和 `check` 方法，可以基于输出结构做静态检查。故事 F 依赖这一点。

被放弃的方案：JSONata、jexl 等库没有基于 schema 的静态检查；自写求值器要自己维护语法和类型系统，不值得。

## 影响

- 标品 Agent 应当声明 `spec.output.schema`，它同时是租户钩子的契约。标品升级修改输出字段属于对租户的破坏性变更，升级回归会显示受影响的扩展。
- 运行时（P3）用同一套环境对条件求值，组装器的 `runHooks` 通过 runner 的 `shouldRun` 回调接入求值。

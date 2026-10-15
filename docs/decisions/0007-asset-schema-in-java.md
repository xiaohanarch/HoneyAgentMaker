# ADR 0007 资产 schema 在 Java 里的实现方式

状态：已采纳（J1，2026-10-15）

## 背景

TS 版用 zod 定义资产 schema，一份定义同时做四件事：校验作者写的 YAML 并给出带字段路径的中文报错、填充默认值、推导出 TypeScript 类型、导出 JSON Schema 给编辑器做补全。后端改用 Java（ADR 0006）后，需要在 Java 里找到对应的做法，而且报错的质量不能下降：报错要带行号和字段路径，一次报出全部问题，拼错的字段名能被发现。

考虑过两种现成方案：

- Jakarta Bean Validation 加 Jackson 反序列化。字段级的约束（必填、正则）写成注解很自然，但几类规则表达起来很别扭：“每个工具条目只能写一种来源”、“步骤按类型要求不同的字段”、“字段可以写单个对象也可以写数组”、“扩展里的工具条目可以是引用字符串也可以是对象”。Jackson 遇到第一个类型错误或未知字段就停止，问题不能一次报全。
- 只维护 JSON Schema 文件，用 networknt 校验。自定义规则（引用格式、语义化版本、互斥字段）在 JSON Schema 里写不清楚，报错信息也很难做成中文。

## 决策

在 `eab-spec` 里写一个小的 schema 库（`dev.eab.spec.schema`，约 500 行），写法贴近 zod：`object().field(...)`、`string().min(1)`、`array(...)`、`union(...)`、`refine(...)`。每个 kind 的 schema 定义在 `dev.eab.spec.kinds` 下，逐条对照 TS 版翻译。

一份 schema 定义同时用于：

- 校验 JSON 树，收集全部问题，问题带字段路径（`spec.tools[2].mcp.source`）和中文说明；
- 填充默认值，得到规范化的内容，签名和内容哈希都基于它；
- 导出 JSON Schema，生成到仓库根目录的 `schemas/`，由 `pnpm spec:json-schema` 更新，测试检查生成物与定义同步。

校验通过后，规范化的 JSON 再用 Jackson 映射成 Java record（`dev.eab.spec.model`），业务代码用强类型访问。记录类型开启“遇到未知字段报错”，schema 与记录类型不一致时测试会失败。

YAML 的行号来自 Jackson YAML 解析器的读取位置：先按 token 走一遍文档，记下每个值的 JSON Pointer 和行列号，问题出现在缺失字段上时，定位到它所在的父对象。

## 影响

- schema 和记录类型是两份代码，但都在 `eab-spec` 里，前后端、命令行、Studio MCP Server 仍以它为唯一来源；修改字段时两处一起改，测试会检查二者一致。
- 前端需要的 JSON Schema 从 Java 导出，TS 侧不再维护 zod schema；`packages/spec` 在 J2 删除。
- CEL 改用官方 Java 版（`dev.cel:cel`）。钩子条件里的 `result` 按标品 Agent 的输出结构注册成结构类型，引用不存在的字段在组装时报出，这一点与 TS 版一致。官方 Java 版默认不开启 `has`、`exists` 等标准宏，需要显式开启；混合 int 与 double 的比较需要打开对应的选项。

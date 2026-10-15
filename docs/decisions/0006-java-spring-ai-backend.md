# ADR 0006 后端改用 Java、Spring Boot 与 Spring AI

状态：已采纳（2026-10-15，P3 进行中）。替代 ADR 0001；ADR 0002 中服务端使用的 SDK 改为 Java 版，见下文。

## 背景

主提示词第 14 节规定后端用 TypeScript，P0 到 P2 按此实现，后端是两个 Fastify 服务。P3 开始实现 Agent 运行时的时候，公司对 Agent 框架做了选型，候选是 Python 加 LangGraph（含 Deep Agents）和 Java 加 Spring AI。比较的结论是：

- 企业 ERP 的 Agent 平台，大部分工作量在身份透传、四处鉴权、写操作级别与确认、多租户隔离、审计和追踪裁剪上，这些与 Agent 框架无关，属于企业后端工程。公司团队以 Java 为主，ERP 本身也是 Java 体系。
- Spring AI 由 Spring 团队维护，2.0 已正式发布，按里程碑版和正式版的节奏迭代，适合需要长期维护的平台。Deep Agents 仍在 0.x，发版频繁，并且面向长时间的自主任务，与 ERP 中边界明确、需要确认的工具调用不太吻合。
- 两种语言之间可以用 MCP 和 A2A 互通，Python 生态的长处可以作为外部 Agent 接入。

公司决定以本项目作为 Spring AI 的第一次试验，整个后端改用 Java。

## 决策

后端（平台主服务与 DemoERP）改为 Java 21 加 Spring Boot 4.1，Agent 运行时基于 Spring AI 2.0，用 Maven 多模块工程组织，放在 `backend/` 目录。前端（React）、资产 YAML、演示剧本、端到端测试保持不变。

选用的版本（2026-10-15 从 Maven Central 查得）：

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | 21（Temurin，主机用 Ubuntu 的 OpenJDK） | 运行环境，启用虚拟线程 |
| Spring Boot | 4.1.1 | Web、JDBC、测试 |
| Spring AI | 2.0.1 | 模型调用、工具调用、Advisor 处理管道、MCP 客户端与服务端 |
| MCP Java SDK | 2.0.1 | 由 Spring AI 的 MCP starter 引入；Spring 相关的传输已移进 Spring AI |
| A2A Java SDK | 1.4.0.Final（groupId `org.a2aproject.sdk`） | P7 的 A2A 客户端与服务端 |
| CEL Java | 0.14.0（`dev.cel:cel`） | 策略规则、钩子与确认条件 |
| Nimbus JOSE JWT | 10.10 | 身份令牌（EdDSA） |
| SQLite JDBC | 3.53.4.0 | 演示数据库 |
| Flyway | 13.10.0 | 数据库迁移 |
| springdoc-openapi | 3.1.1 | OpenAPI 描述，路径仍为 `/openapi.json` |
| Maven | 3.9.16（Maven Wrapper 3.3.4） | 构建 |
| Spotless + palantir-java-format | 3.10.4 / 2.102.0 | 代码格式 |
| JaCoCo | 0.8.15 | 覆盖率 |

迁移方式：

- 对外 HTTP 契约不变：路径、`{success, data, error}` 响应信封、端口 4000 和 4100、令牌格式、环境变量名都沿用 TS 版。前端、`scripts/acceptance-p2.mjs` 和 Playwright 测试不需要修改，直接作为迁移的回归测试。
- 分三步：J0 建 Java 工程骨架；J1 迁移资产层（对应 P1）；J2 迁移身份、策略和 DemoERP（对应 P2），`acceptance-p2.mjs` 原样通过后删除 TS 后端。之后在 Java 上实现 P3 及以后的阶段。
- J2 完成之前，`pnpm demo` 默认仍启动 TS 后端，`pnpm demo:java`（等价于 `pnpm demo --backend=java`）启动 Java 后端。
- 数据访问用 Spring 的 `JdbcClient` 加仓储接口，不用 JPA，SQL 写在代码里，便于阅读。表结构沿用 TS 版。

Agent 运行时在 Spring AI 上的做法：

- 模型调用用 `ChatClient` 和 `ChatModel`。脚本化模型实现为自定义的 `ChatModel`，Anthropic 与 OpenAI 兼容协议用 Spring AI 官方的模型模块，按环境变量启用。
- 关闭 Spring AI 的内部工具执行，工具调用由运行时自己执行，每次调用前经过鉴权点 3 和监督层，需要确认时可以挂起。
- 模型处理管道（模板、知识检索注入、脱敏与还原、输入输出过滤）用 Advisor 实现。
- Agent 循环、挂起与恢复、技能执行器和工作流引擎仍然自己实现，保持源码可读（主提示词 8.1.1 节）。

## 影响

- 开发和运行需要 JDK 21。`pnpm install && pnpm demo` 仍是唯一入口，Java 部分由脚本调用 Maven Wrapper 构建，不需要另装 Maven。
- `pnpm test` 同时运行 Vitest 和 `mvnw verify`，`pnpm lint` 同时运行 ESLint 和 Spotless 检查。
- 主提示词中“平台提供 TypeScript SDK 让代码 Agent 声明为平台资产”改为 Java SDK。外部 Agent 样例保持 Python（LangGraph）和 TypeScript，用来演示跨语言接入。
- 资产的 schema 以 Java 模块 `eab-spec` 为唯一来源，Studio 需要的 JSON Schema 由它导出。
- 演示主机安装 OpenJDK 21，Maven 镜像在运行用户的 `~/.m2/settings.xml` 中配置，不进仓库。
- P3 的 TypeScript 半成品保留在 `feat/p3-runtime` 分支，作为行为参照，不合入。

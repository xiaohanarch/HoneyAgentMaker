# SAP Joule Studio 调研摘要（截至 2026-10）

本文整理 SAP Joule Studio 的产品形态、对象模型、运行架构和 Sapphire 2026 之后的变化，并对照 MetaERP 的 ERP Agent Builder 给出可借鉴点。资料来源见文末，其中 SAP 官方材料（news.sap.com、architecture.learning.sap.com、developers.sap.com、IVJ 演示）可信度最高，合作伙伴和媒体文章只作为补充。

## 1. 产品在 SAP 体系里的位置

SAP 的 AI 产品分成四块，彼此分工清楚：

| 组件 | 角色 | 对应到我们 |
|---|---|---|
| Joule / Joule Work | 统一入口。用户只面对一个助手，Joule 负责理解意图、路由到技能或 Agent。Sapphire 2026 推出的 Joule Work 是新的工作台式入口，Joule Assistants（按岗位划分的助手）在这里协调多个 Joule Agents | 统一入口 + 编排层 |
| Joule Studio | 开发态工具。构建 Agent、技能、对原生 Agent 的扩展、n8n 工作流和小应用，部署后自动注册到 Joule | ERP Agent Builder |
| Joule Studio Runtime | SAP 托管的运行时，负责扩缩容、日志、审计、长期记忆（HANA Cloud），隔离执行由 NVIDIA OpenShell 提供沙箱 | Agent 运行时 |
| SAP AI Agent Hub | 治理中心。盘点企业内所有 Agent、LLM、MCP Server（不限厂商），做审批、访问控制、会话与工具调用观测 | Agent 注册、审计、评测 |

底座是 SAP Business AI Platform，它把 Knowledge Graph（业务语义）、Business Data Cloud（数据产品）、Domain Models（SAP 自有领域模型）、Generative AI Hub（模型网关 + 编排 + grounding）连在一起。

## 2. IVJ 演示里的建模流程

你给的 IVJ 演示用一个采购经理创建"价格谈判助手"的例子，把 Agent Builder 的配置项完整走了一遍。按顺序是：

1. 打开 Joule Studio 首页
2. 用一句话描述 Agent 的目的（帮采购经理制定供应商谈判策略）
3. 起名，Joule 自动补全基本信息，并生成头像和横幅图
4. 关联业务流程（Joule 推荐挂到 Source-to-Pay，用户确认）
5. 连接其他 Agent（演示里接入了 Microsoft Copilot）
6. 添加数据产品，并补充外部数据，作为业务上下文
7. 由 Joule 推荐并装配技能（发票管理、供应商搜索等）
8. 定义触发方式（默认是从 Joule 直接调用）
9. 设置沟通渠道（Microsoft Teams）
10. 编写指令和约束（例如交付逾期超过 90 天的供应商不推荐发起谈判）
11. 选择推理模型（o1-mini）
12. 用测试提示词验证（查某供应商最近一次询价并给出谈判策略）
13. 部署

这个流程有两个特点值得复刻。一是每一步都有 AI 先给推荐，用户只做确认和微调；二是配置项覆盖了一个 Agent 的完整定义，包括身份、业务归属、协作关系、数据、能力、触发、渠道、规则、模型。

## 3. 对象模型

综合 CodeJam 教程、参考架构和 Sapphire 2026 资料，Joule Studio 管理的对象有这些：

| 对象 | 说明 |
|---|---|
| Project | 设计态项目。技能和 Agent 先在项目里测试，再部署到共享环境，最后发到生产 Joule |
| Skill | 确定性的单一任务单元，输入结构化、逻辑固定，适合查数据、触发事务。可以设成只允许被 Agent 调用 |
| Agent | 由 LLM 驱动，带指令、工具、技能、文档 grounding、子 Agent，能规划和多步执行 |
| Tool / Action | 对后端 API 的封装，也可以通过 MCP Server 批量导入 |
| Document Grounding | 文档检索增强，给 Agent 补业务知识 |
| Sub-agent | Agent 可以把另一个 Agent 当作工具调用 |
| Agent Extension | 对 SAP 交付的原生 Agent 做扩展，可以加技能、加指令、加数据字段，也可以挂钩子（参考架构的例子是 AP 发票 Agent 匹配失败后触发 n8n 工作流）。核心逻辑不允许改 |
| n8n Workflow | 可视化的多步骤自动化，Sapphire 2026 起内嵌在平台里 |
| Application | CAP 后端 + React 前端的小应用 |

## 4. 与统一入口的接入方式

Joule 用 Scenario Catalog 做路由。每个 Agent 能力注册为一个 Scenario，带描述；执行单元叫 Dialog Function。Joule 的规划引擎把用户的话和 Scenario 描述做匹配，决定调用内置技能、做检索，还是委派给某个 Agent。

接入分两条路：

- 低代码 Agent：在 Joule Studio 里部署时，Scenario 和 Dialog Function 自动生成并注册，开发者不碰协议。
- 专业代码 Agent（BYOA，Bring Your Own Agent）：开发者用 LangGraph、CrewAI、AutoGen、LlamaIndex、Pydantic AI 等框架写 Agent，实现为 A2A Server。在 Joule 里建一个 Scenario，加一个 `agent-request` 类型的 Dialog Function 指向它。支持同步调用（60 秒超时）、异步长任务（push 通知回调）、多轮对话（透传 contextId / taskId）。Joule 与 Agent 之间用 IAS App2App 信任做认证。SAP 在 GitHub 上提供了 A2A Agent Toolkit 作为样板。

协议上，A2A 是 SAP 主推的跨厂商互操作标准，早期只支持 Joule 调外部 Agent（出站）。Sapphire 2026 宣布改为双向，外部 Agent（如 Copilot Studio、Google 体系的 Agent）可以经 Agent Gateway 发现并调用 Joule Agent，正式可用时间按 SAP 的路线图在 2026 下半年。MCP 主要用于工具和数据接入，SAP Domain Models 通过远程 MCP Server 提供给专业代码开发流程，AI Agent Hub 负责只放行经过验证的 MCP Server 进生产。

## 5. Sapphire 2026（2026 年 5 月）之后的主要变化

SAP 承认 1.0 版本偏低代码、只能用扩展点和工具，满足不了复杂场景，2.0 主要补这几块：

- 意图驱动开发（Intent-Based Development）。分六个阶段：意图、需求（生成 PRD）、技术规格、代码生成、测试（单元测试 + AI 评估）、部署。用户说一句话，平台产出需求文档、规格、脚手架、评测集和可预览版本，并保留从需求到实现的追溯关系。
- 低代码和专业代码两条流程产出同一种可部署产物。专业代码流程用 Joule Studio CLI（`jl`），配合 Claude Code、Cursor、OpenCode 等编码 Agent，通过 MCP 把 IBD 技能加载进编码 Agent。
- 托管运行时。不再需要客户自己配 BTP 子账户、Cloud Connector、算力。Agent 运行在隔离沙箱里，可配策略和护栏；长期记忆存 HANA Cloud。
- 业务上下文。生成方案时会用 Knowledge Graph 找 API 和做 fit-gap，用 LeanIX 了解客户的系统全景（含第三方系统）。
- 身份。每个 Agent 在运行时默认拿到一个独立身份，可通过 SAP Cloud Identity Services 代理 Okta、Entra 等身份源。
- 治理。AI Agent Hub 提供目标完成率、工具调用正确率、根因追踪、人工介入频率等指标；支持审批门控、子 Agent 编排、满足 SOX 的日志。
- 生态。n8n 内嵌做可视化多 Agent 编排；与 Vercel 合作支持 Next.js 等自定义前端，UI5/Fiori 仍是标准界面。
- 商业上，设计态到 2026 年底免费给客户和伙伴用，托管运行时计划 2026 Q3 GA。

## 6. SAP 官方视频补充

以下几条来自 SAP 官方 YouTube 视频的字幕，补充了文字资料里没有的细节。

### 6.1 Joule Assistants 与 Joule Agents 的分工（Explainer）

Assistant 按岗位划分（采购寻源助手、制造助手、开票助手），负责理解业务上下文、决定调哪些 Agent、按什么顺序调。Agent 负责执行，跨 SAP 和第三方系统完成多步任务，也可以和外部 Agent、工具协作。

视频里的链路是：需求激增导致供应短缺，采购总监找寻源助手，助手调"寻源事件 Agent"在 Ariba 发 RFP，供应商回标后调"标书分析 Agent"评估推荐；问题解决后通知供应链经理，制造助手调"生产计划 Agent"释放被卡住的生产订单；财务这边，开票助手调"开票异常 Agent"找出异常费用和税务问题，调"过账 Agent"找出过账错误、用白话解释、给出修正建议，修正要人工批准。

这说明 SAP 的统一入口实际是两级编排：入口路由到岗位助手，助手再编排一组 Agent。

### 6.2 Autonomous Suite 与扩展（Sapphire 2026 主题演讲）

- 首批发布 224 个 Agent、51 个 Assistant，按领域组织成自治域（Autonomous Finance、Spend、Supply Chain、HCM、CX）。
- Assistant 有三种触发方式：人触发、系统触发加人工把关、完全自主运行（在信任建立之后）。每个 Assistant 带一组预期 KPI，结果在 AI Agent Hub 里跟踪，考核看业务结果而不是部署了多少 Agent。
- 扩展是第一设计原则。任何标品 Agent 都可以通过 Joule Studio 加工具、加工作流步骤、加代码，也可以往任何 Assistant 里加全新的自定义 Agent。
- 标品 Agent 按 SOX 审计兼容、ISO 认证的开发流程构建。
- Company Memory：基于 Signavio 的企业上下文图谱，吸收流程模型、制度文档、Teams/Slack 讨论、邮件审批链，抽取成"process atom"（结构化的小块流程知识，告诉 Agent 该看什么、该做什么、不该做什么）。Agent 处理例外时会把处理方式回写进来，人可以审核和修改规则，所有 Agent 立即生效。
- Joule Work 内部被称为第十版 Joule：从 RAG 式助手升级为云端 Agent harness，带计算机和文件访问、更好的 MCP/A2A 支持、即时可视化。Spaces 是按任务动态生成的工作区，人和 Agent 在里面协作。
- 所有 Agent 和 Assistant 都开放 A2A，已选了其他 Agent 平台的客户可以直接调用。

### 6.3 Joule Studio 演示（Build AI Agents and Apps in Minutes）

视频把 Joule Studio 定位为 agent harness：模型提供智能，Studio 提供其余一切（业务流程、企业架构、领域知识、最佳实践）。演示用一句话需求构建"关税冲击应对"方案，完整流程是：

1. 意图分析技能先判断该建 Agent、工作流还是应用，同时通过 MCP 查 LeanIX 的系统全景和 Agent Hub，用 Domain Models 和 Knowledge Graph 校验
2. 生成 idea board（读真实的流程文档和系统全景），推荐三层方案：一个 AI Agent、一组 n8n 工作流、若干扩展
3. 生成 PRD，含对 KPI 的影响估算，可用于干系人审批
4. 生成技术规格，列出所有要构建的制品及映射关系
5. 生成 Agent（Python，原生支持 A2A），配文档 grounding（合同、关税表、供应商绩效），把第三方 MCP Server 加为工具
6. 可切到专业代码视图，用 VS Code、Claude、Cursor 和任意框架继续改
7. 生成四个 n8n 工作流，把 Agent 的分析结果分发给采购、合规、供应链、财务四个团队。模式是 Agent 负责推理，工作流负责响应和执行（带重试、异常、审批）
8. 生成一个小应用（数据模型 + 服务 + UI），可在 Studio 里预览，可推到 Git
9. AI 生成测试用例、验证、审查结果
10. 部署到托管运行时，部署后在 Studio 里看失败 trace、运行完成率、延迟

### 6.4 Business AI Platform 演示（SAP Connect 2026）

这段演示展示了"业务用户提需求、开发者交付扩展"的闭环，很适合复刻：

1. HR 业务伙伴在 Joule Work 里处理并购后的员工入职，HR 助手已经主动准备好计划
2. 发现笔记本电脑到货不足，Joule 推荐换供应商，但 HR 助手没有这个能力，于是推荐扩展这个助手
3. 业务用户把推荐转成开发需求，Knowledge Graph 自动把截止日期、供应商、审批规则附在需求上；她补了一条"加急订单必须人工批准"
4. 开发者在 Joule Studio 收到通知，看到完整的业务上下文和意图，一键生成扩展。扩展继承原 Agent 的治理、授权和上下文
5. 开发者审查 n8n 工作流（有备选供应商就建请购单，没有就转给寻源助手招标），跑评测场景，部署
6. 在 Agent Hub 查看扩展后 Agent 的治理记录
7. 业务用户收到上线通知，扩展找到有货的备选供应商，她批准后由采购助手下单

## 7. 官方参考架构与样例仓库补充

这一节来自 SAP Architecture Center 的页面原文（站点源码公开在 github.com/SAP/architecture-center）、SAP Learning 课程、developers.sap.com 教程，以及 SAP-samples 下的样例仓库。

### 7.1 Joule 的四种能力模式与请求流程

Joule 能做四类事：事务型（用自然语言触发增删改查，比如审批采购订单）、导航型（直接跳到对应的应用页面）、信息型（基于 SAP 帮助文档和客户文档回答）、分析型（回答指标和趋势类问题）。

一次请求的处理过程：编排器同时参考三类输入，分别是 Scenario Catalog（所有场景、函数、技能的元数据）、Knowledge Catalog（SAP 知识加客户知识，走企业级 RAG）、用户上下文（当前所在应用、已开通的应用、角色和权限、对话历史），拼成增强后的请求交给大模型。大模型要么直接回答，要么决定调用某个函数，由函数去调后端。输出经过安全、隐私、负责任 AI 的过滤后返回。

### 7.2 三种运行时

| 运行时 | 承载的东西 | 特点 |
|---|---|---|
| Joule Execution Runtime | Joule 场景和函数 | 确定性，适合简单功能 |
| Managed Agent Runtime | 内容型 Agent：提示词加声明式工具 | Agent Builder 做出来的就是这一类 |
| Custom Agent Runtime | 代码型 Agent：提示词加自定义代码工具 | 专业代码路线 |

三者共用一个编排入口。North Star 架构进一步提出：低代码和专业代码产出同一个规范制品，以代码作为规范表示，可以从低代码升级到专业代码而不用重构。

### 7.3 Agent Builder 的编辑器字段

根据 developers.sap.com 的教程，Agent 编辑器有这几块：Expertise（能力概述）、Instructions（行为规则）、Additional Context（语气和风格）、Model Settings（模型选择）、Agent Execution Steps（预处理和后处理步骤）、Tools（可加 Joule Skill、Documents、MCP Server、Calculator、另一个 Agent）。关键决策默认开启人工介入。

测试时选择私有环境和连接目标，Timeline 里逐节点展开，点技能名可以看输入输出。私有环境测完，再部署到共享环境。文档知识库在 Control Tower 里配置（绑定 AI Core 和对象存储），在 Agent 里以 Documents 工具挂上。

### 7.4 能力包的制品格式

SAP 样例仓库展示了 Joule 能力包的真实格式（设计时制品，DTA），对理解"Agent 怎么注册进统一入口"很有帮助：

```
da.sapdas.yaml                 数字助理部署描述，列出包含哪些能力包（本地、已发布版本、里程碑版本）
<cap>/capability.sapdas.yaml   能力元数据（namespace、name、version、description）+ system_aliases（别名映射到连接目标）
<cap>/capability_context.yaml  跨轮次变量声明，例如 contextId、taskId
<cap>/scenarios/*.yaml         场景：自然语言描述加示例句（路由依据）+ 目标函数 + 参数
<cap>/functions/*.yaml         函数：parameters + action_groups（可带条件）+ actions + result
```

函数里的动作类型包括 `status-update`（执行中提示）、`agent-request`（A2A 调用远程 Agent）、`set-variables`、`message`，表达式写在 `<? ... ?>` 里。制品里只写连接别名，真实地址和认证由各环境的 Destination 决定，所以同一个包可以跨环境、跨租户部署。

CLI 工具链：`compile`（编译成 .daar 归档）、`link`、`deploy`、`update`（增量更新能力包或挂 MCP Server）、`test`（Gherkin 写的对话测试，可设最低通过率）、`lint`（检查场景描述是否唯一、是否硬编码地址等）。

### 7.5 BYOA 的协议细节

- Joule 作为 A2A 客户端调用 `message/send`，A2A 版本 0.3.0，目前只传文本。
- 响应是 Task，按 `status.state`（working、input-required、completed）分支：需要追问时把追问显示给用户，完成时显示 artifact。多轮靠 contextId 和 taskId 写入跨轮变量。
- 异步：Joule 在请求里带回调地址，Agent 先返回 working，完成后换取令牌把结果推回。
- 身份：开发期可以不认证；正式用 IAS App2App；需要用户身份透传时，经代理应用做令牌交换，Agent 调后端时再换后端令牌，Agent 不持有长期后端凭据。漏配一个参数会静默退化成技术用户，这是一个值得在我们设计里主动防范的坑。
- 入站（外部调用 Joule Agent）经 Agent Gateway：SAP 托管域名，A2A 0.3.0，调用方需要给出能力和场景标识，同步返回"已受理"，结果异步回调。
- 对外互通 SAP 优先用 A2A，MCP 主要在内部用，让 Agent 访问业务能力和 Knowledge Graph。Integration Suite 的 MCP Gateway 可以把现有 API 和集成流包装成受治理的 MCP 工具。

### 7.6 Agent 身份与治理

- Agent 和人放在同一个身份目录里，各有全局 ID。Agent 的身份由应用注册、持久角色、限定范围的授权三部分组成。
- Agent Gateway 部署在客户环境但由 SAP 托管，所有 Agent 到 Agent、Agent 到工具的流量都经过它。三层策略执行点：网关入口（调用方能不能调这个 Agent）、Agent 交接前、应用侧的 API 与工具调用（计划 2026 下半年）。原则是越早拒绝越好。
- 代表用户执行时，有效权限是用户权限和 Agent 权限的交集；自主执行时用独立技术身份和独立审计轨迹。用户可以把自己权限的一个子集授予 Agent，供无人值守执行。
- 新 Agent 在 Agent Hub 登记后，自动开通身份，并带着 Agent Card 注册。

### 7.7 North Star 架构里的几条设计主张

- Solution 是部署单元，例如一个采购方案包含请购应用、审批工作流和多个 Agent，作为整体部署，共享生命周期。
- 不改 Agent 代码就能扩展的四种方式：通过 MCP 加工具、加技能、挂前后置钩子、注入指令。
- 托管运行时自带一套 harness：安全、可观测、租户隔离、沙箱、持久记忆。原话的意思是模型负责推理，harness 负责管控。
- Knowledge Graph 分四层：API 与服务元数据、业务语义、数据产品元数据、客户扩展（自定义字段、自定义 API、租户配置）。
- 三层防御：基础层（零信任、提示注入检测、可验证的 Agent 凭证）、监督层（用确定性的控制 Agent 做输入校验、输出过滤、异常检测，高风险决策转人工）、自动化层（安全运营类 Agent）。
- 持久化决策轨迹：当时知道什么、考虑了哪些选项、选了什么、结果如何。人的每次修正都变成结构化的决策记录。
- 评估分三层：技术指标（目标完成率、工具调用效率）、流程 KPI、财务与运营结果，用多目标避免只优化单一指标。

### 7.8 合作伙伴和媒体的批评

这些批评对我们避坑有价值：

- 低代码这条路治理很薄，只有版本、发布和按用户的访问控制（sovanta）。SAP 先做低代码、再另开专业代码、最后才用 Studio 2.0 合并，走了弯路。
- 预置 Agent 一度只能在 Joule 里由用户手动调用，事件触发和 API 调用来得很晚。
- Joule 过去的使用率低于预期，难点在角色、职责和信任关系的重新安排（valantic）。
- "200 多个 Agent"这类数量口径被拿来和 Salesforce Agentforce 类比，被认为难以兑现；应该用业务指标说话。
- 主数据质量和文档抽取是 Agent 可靠性的瓶颈。
- 老系统在 Agent 调用量上来后吞吐扛不住（SAP 发言人自己的比喻是法拉利开土路）。

## 8. 对 MetaERP ERP Agent Builder 的启发

对照白皮书（第 6 章业务能力开放、第 7 章接入认证鉴权、第 8/9 章租户扩展与 Agent 开发平台），Joule 的做法和我们的设计可以这样对应：

| Joule 的做法 | MetaERP 白皮书里的对应设计 |
|---|---|
| 一个入口，两级编排（入口 → 岗位助手 → Agent），Scenario Catalog 路由 | 五层两翼的用户体验层 + Agent 编排层；能力目录与 Agent 卡片作为路由依据 |
| 标品 Agent 由 SAP 托管，客户看不到原始提示词；扩展是独立制品，运行时叠加（追加指令、加工具、挂钩子，没有删除和改写） | 标品包（厂商命名空间、签名发布、租户只读）+ 租户扩展包（租户命名空间、只声明挂在哪个扩展点）；运行时按"平台安全规则 → 标品指令 → 租户追加指令"组装，工具取并集不提供删除 |
| 真想改核心就自建一个 Agent，不再享受标品升级 | "复制标品另建"规则：复制后归租户所有，不随标品升级 |
| 扩展继承原 Agent 的治理、授权和上下文 | 双重身份（人 + Agent）有效权限取交集；四处鉴权落点（入口、Agent 交接、工具/MCP 网关、BO API） |
| BYOA：任何框架写的 Agent，实现 A2A 就能挂进统一入口；入站经 Agent Gateway | 四类接入方统一走 A2A/MCP，Agent 卡片登记；用户身份全链路透传 |
| 工具背后是带权限检查的 API，"用户在应用里做不了的事 Joule 也做不了" | 原则"AI 不扩权""硬约束写在 API 和工具里"；BO API 是 Agent 调用业务的标准通道 |
| 人工批准、审批门控、SOX 日志 | 写操作四级（只读、出建议、需人工确认的写、可自主执行的写）；付款入账类最多到需人工确认；SoD |
| Knowledge Graph + Domain Models + Company Memory | ERP 语义资产（objects、relationships、metrics、rules、agent_instructions、verified_tasks、permissions），语义查询工具以 MCP 网关工具的形式提供 |
| 意图驱动开发：意图 → PRD → 规格 → 实现 → 测试与评测 → 部署 | 白皮书第 9 章的标准流程：需求、规格、实现、测试与评测、发布、注册；开发态通过 MCP 向编码 Agent 供给语义资产、能力目录、扩展点目录 |
| AI 生成测试，Agent Hub 跟踪目标完成率、工具调用正确率、人工介入频率；标品升级前回归 | 评测门禁；verified_tasks 同时作为调用示例和评测用例；标品升级时用租户扩展自动跑回归 |
| Assistant 带 KPI，按业务结果考核 | 每个场景有基线和验收标准；架构假设 H1 至 H7 可证伪 |
| 能力包里只写连接别名，真实地址和认证按环境绑定 | 制品与环境配置分离，同一个包可在租户的 dev、test、prod 间晋级 |
| 三种运行时共用一个编排入口；低代码与专业代码产出同一规范制品 | 声明式资产 + 可选代码，低代码可导出到 IDE 继续改，不做两套体系 |
| 监督层用确定性的控制 Agent 做输出过滤和异常检测 | 写操作前的确定性规则校验（金额阈值、业务规则、SoD），提示词之外的第二道防线 |
| 决策轨迹与 Company Memory，人工修正回流成规则 | 经验沉淀为语义资产：用户纠正产生候选规则，审核后进入语义包 |

### 需要避开的坑

1. 不要先做低代码再补专业代码，一开始就用同一种制品格式。
2. 事件触发、API 触发、被其他 Agent 调用，从第一版就要有，不能只支持对话。
3. 身份透传要"失败即拒绝"，不能在配置不全时悄悄退化成技术账号。
4. 治理中心不能等到最后才做，没有注册、准入、审计，企业不敢上规模。
5. 不以 Agent 数量为目标，以场景的业务指标为目标。
6. 存量系统的吞吐和主数据质量要在规划时一起评估。

## 资料来源

- [IVJ 演示：Creating Intelligent Joule Agents with Joule Studio](https://ivj-vx.cfapps.eu10.hana.ondemand.com/public/journey/185f3aa9-d9a0-4e35-9a89-b05e1332df52/intro)
- [YouTube：How Joule Agents and Joule Assistants Work | Explainer](https://www.youtube.com/watch?v=N2LwNTduFa8)
- [YouTube：Joule Studio: Build AI Agents and Apps in Minutes | Demo](https://www.youtube.com/watch?v=r0KiK90ty34)
- [YouTube：SAP Autonomous Suite and Joule Work | SAP Sapphire Orlando 2026](https://www.youtube.com/watch?v=SCMcYxtb-Wc)
- [YouTube：SAP Business AI Platform and Autonomous Enterprise | SAP Sapphire Orlando 2026](https://www.youtube.com/watch?v=1uK9k457TWE)
- [YouTube：SAP Business AI Platform: Build and Govern AI Agents | SAP Connect 2026](https://www.youtube.com/watch?v=6rtdMi-rv7g)
- [SAP News：New Joule Studio for enterprise-scale agentic development（2026-05）](https://news.sap.com/2026/05/new-joule-studio-enterprise-scale-agentic-development/)
- [SAP Architecture Center：Integrating AI Agents with Joule](https://architecture.learning.sap.com/docs/ref-arch/ae6821)
- [SAP Architecture Center：Integrating and Extending Joule](https://architecture.learning.sap.com/docs/ref-arch/464deb)
- [SAP Architecture Center：AI Agent Identity & Governance](https://architecture.learning.sap.com/docs/ref-arch/140bdb)
- [SAP AI-native North Star Architecture: Executive Summary](https://architecture.learning.sap.com/docs/ai-native-north-star-architecture/executive-summary)
- [SAP Architecture Center：Bring Your Own Agent](https://architecture.learning.sap.com/docs/ref-arch/7b6426)
- [SAP Architecture Center：Extend Joule with Joule Studio in SAP Build](https://architecture.learning.sap.com/docs/ref-arch/ff07b1)
- [SAP Architecture Center 源码仓库](https://github.com/SAP/architecture-center)
- [SAP Learning：Leveraging Custom Joule Agents](https://learning.sap.com/courses/introducing-joule-studio-in-sap-build/leveraging-custom-joule-agents-for-complex-multi-step-business-workflows)
- [SAP Learning：Launching Your First Design in Joule Studio](https://learning.sap.com/courses/introducing-joule-studio-in-sap-build/launching-your-first-design-in-joule-studio)
- [SAP Learning：Exploring SAP Knowledge Graph](https://learning.sap.com/courses/boosting-ai-driven-business-transformation-with-joule-agents/exploring-sap-knowledge-graph)
- [SAP Learning：Exploring Ready-to-Use and Custom Agents](https://learning.sap.com/courses/boosting-ai-driven-business-transformation-with-joule-agents/exploring-out-of-the-box-agents-and-custom-joule-agents)
- [SAP Developers：joulestudio-agent-create 教程](https://developers.sap.com/tutorials/joulestudio-agent-create.html)
- [SAP Developers：joulestudio-document-grounding 教程](https://developers.sap.com/tutorials/joulestudio-document-grounding.html)
- [SAP Developers：Orchestration with Grounding](https://developers.sap.com/tutorials/ai-core-orchestration-grounding.html)
- [SAP Developers：Agent Lab](https://developers.sap.com/agent-lab.html)
- [GitHub：SAP-samples/joule-a2a-agent-toolkit](https://github.com/SAP-samples/joule-a2a-agent-toolkit)
- [GitHub：SAP-samples/btp-joule-a2a-pro-code-agent](https://github.com/SAP-samples/btp-joule-a2a-pro-code-agent)
- [GitHub：SAP/custom-agentic-cookbook](https://github.com/SAP/custom-agentic-cookbook)
- [GitHub：SAP/ai-sdk-js](https://github.com/SAP/ai-sdk-js)、[SAP/ai-sdk-java](https://github.com/SAP/ai-sdk-java)
- [SAP News：New Agentic Capabilities on SAP BTP（2025-11）](https://news.sap.com/2025/11/new-agentic-capabilities-sap-btp-supercharge-developers/)
- [SAP News：Joule Agents（2025-02）](https://news.sap.com/?p=231449)
- [SAPinsider：Joule Is Now the Front Door](https://sapinsider.org/articles/joule-is-now-the-front-door-to-your-business-what-sap-sapphire-2026-means-for-customers-vendors-and-partners/)
- [Nanonets/TrailHQ：SAP Sapphire 2026 Complete Breakdown](https://nanonets.com/blog/sap-sapphire-2026-the-complete-breakdown/)
- [valantic：SAP Sapphire 2026 Autonomous Enterprise](https://www.valantic.com/en/blog/sap-sapphire-2026-autonomous-enterprise/)
- [McCoy：BDC & Business AI Platform after Sapphire 2026](https://mccoy-partners.com/en/updates/sap-business-data-cloud-and-business-ai-platform-what-sapphire-2026-means-for-ent)
- [VentureBeat：Why SAP says agents need knowledge graphs and governance](https://venturebeat.com/orchestration/why-sap-says-enterprise-ai-agents-need-knowledge-graphs-and-governance)
- [sovanta：The SAP Approach to Agents](https://sovanta.com/en/the-sap-approach-to-agents/)
- [SAP Architecture Center：Joule Studio](https://architecture.learning.sap.com/docs/ref-arch/f5a3ef)
- [SAP Architecture Center：Building AI Agents with Joule Studio](https://architecture.learning.sap.com/docs/ref-arch/219c07)
- [SAP Architecture Center：Build Low-Code and Pro-Code AI Agents on SAP BTP](https://architecture.learning.sap.com/docs/golden-path/ai-golden-path/build-and-deliver/build-ai-agents)
- [SAP Developers：Joule Studio CodeJam 概览](https://developers.sap.com/tutorials/joule-studio-codejam-0-overview.html)
- [SAP Developers：用 Agent Extensions 扩展 AP Invoice Agent](https://developers.sap.com/tutorials/joule-studio-agent-extension/)
- [SAP：Joule Studio Agent Builder 产品页](https://www.sap.com/denmark/products/artificial-intelligence/joule-studio-agent-builder.html)
- [The New Stack：SAP Joule Studio managed agents](https://thenewstack.io/sap-joule-studio-managed-agents/)
- [s-peers：SAP Sapphire 2026 术语表](https://s-peers.com/en/wiki/sap-sapphire-2026-glossar/)
- [WebProNews：Joule Studio 2.0 overhaul at Sapphire 2026](https://www.webpronews.com/sap-admits-joule-studio-flop-rushes-2-0-overhaul-with-pro-code-power-and-agent-governance-at-sapphire-2026/)
- [DEBCOR：Joule Studio vs Custom A2A Agents](https://www.debcor.com/insights/joule-studio-vs-custom-a2a-agents)
- [SAVIC：Joule Studio 2.0 Managed Agent Builder](https://www.savictech.com/insights/sap-joule-studio-2-managed-agent-builder-citizen-developer-2026/)

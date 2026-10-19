/**
 * 架构视图的组件清单（主提示词 4.1 节五层两翼的落地）：id 供回放高亮与 e2e 断言引用，
 * 不要改名；label 与 description 是图上的展示文案。zone 决定组件画在哪个区块、用什么来源色。
 */
export type ArchZone =
  | 'ux'
  | 'orchestration'
  | 'app'
  | 'knowledge'
  | 'capability'
  | 'auth-wing'
  | 'ops-wing'
  | 'dev'

export interface ArchComponent {
  id: string
  label: string
  description: string
  zone: ArchZone
}

export const ZONE_LABELS: Record<ArchZone, string> = {
  ux: '① 用户体验层',
  orchestration: '② Agent 编排层',
  app: '③ 应用层',
  knowledge: '④ 知识层',
  capability: '⑤ 平台能力层',
  'auth-wing': '左翼 安全与治理',
  'ops-wing': '右翼 运营与运维',
  dev: '开发态',
}

export const COMPONENTS: readonly ArchComponent[] = [
  // ① 用户体验层
  { id: 'ux-entry', label: '统一入口', description: '提问、澄清与通知', zone: 'ux' },
  { id: 'ux-im', label: 'IM 渠道', description: 'IM 机器人通知', zone: 'ux' },
  { id: 'ux-a2a-in', label: '外部调用方', description: '外部平台 A2A 调用', zone: 'ux' },
  // ② Agent 编排层
  { id: 'orc-router', label: '路由器', description: '意图识别与选择', zone: 'orchestration' },
  { id: 'orc-assistant', label: '岗位助手', description: '面向岗位的轻助手', zone: 'orchestration' },
  { id: 'orc-workflow', label: '工作流引擎', description: '技能与工作流步骤', zone: 'orchestration' },
  { id: 'orc-confirm', label: '人工确认', description: '写操作确认点', zone: 'orchestration' },
  { id: 'orc-sod', label: 'SoD 检查', description: '职责分离检查', zone: 'orchestration' },
  // ③ 应用层（按来源分色）
  { id: 'app-erp', label: 'DemoERP', description: 'BO API 与业务事件', zone: 'app' },
  { id: 'app-vendor', label: '标品 Agent', description: '预置标品资产', zone: 'app' },
  { id: 'app-tenant', label: '租户 Agent', description: '租户扩展与资产', zone: 'app' },
  { id: 'app-remote', label: '远程 Agent', description: '外部样例，A2A 互通', zone: 'app' },
  { id: 'app-shared', label: '共享服务', description: '连接与元数据设施', zone: 'app' },
  // ④ 知识层
  { id: 'know-semantic', label: '语义包', description: '业务语义与规则', zone: 'knowledge' },
  { id: 'know-docs', label: '文档知识库', description: '检索增强片段', zone: 'knowledge' },
  { id: 'know-meta', label: '元数据服务', description: '业务对象定义', zone: 'knowledge' },
  // ⑤ 平台能力层
  { id: 'cap-model', label: '模型网关', description: '模型接入与档位', zone: 'capability' },
  { id: 'cap-mcp', label: '工具网关', description: '工具执行与 MCP', zone: 'capability' },
  { id: 'cap-agent', label: 'Agent 网关', description: 'A2A 出入站', zone: 'capability' },
  { id: 'cap-runtime', label: 'Agent 运行时', description: 'Agent 循环与恢复', zone: 'capability' },
  { id: 'cap-memory', label: '记忆', description: '会话与经验沉淀', zone: 'capability' },
  // 左翼 安全与治理
  { id: 'wing-auth', label: '四处鉴权', description: '入口、交接、工具、BO', zone: 'auth-wing' },
  { id: 'wing-guard', label: '守卫', description: '写级别与行为约束', zone: 'auth-wing' },
  // 右翼 运营与运维
  { id: 'wing-trace', label: '链路追踪', description: '本页回放载体', zone: 'ops-wing' },
  { id: 'wing-eval', label: '评测与门禁', description: '发布质量门禁', zone: 'ops-wing' },
  { id: 'wing-metrics', label: '指标与成本', description: '运行与业务指标', zone: 'ops-wing' },
  { id: 'wing-lifecycle', label: '生命周期', description: '版本晋级与回滚', zone: 'ops-wing' },
  { id: 'wing-audit', label: '审计查询', description: '事后可追溯', zone: 'ops-wing' },
  // 开发态贯穿带
  { id: 'dev-studio', label: 'Studio · CLI · MCP', description: '开发态走同一条草稿链路', zone: 'dev' },
]

export const COMPONENT_IDS: ReadonlySet<string> = new Set(COMPONENTS.map((c) => c.id))

import type { StepKey } from './api'

export interface StepDef {
  /** 地址栏里的步骤标识 */
  key: string
  label: string
  /** 有“AI 推荐”的步骤对应接口的 step；评测集、测试、发布三步没有 */
  recommend?: StepKey
  hint: string
}

/** 新建 Agent 向导的 13 个步骤（主提示词 10.2 节） */
export const STEPS: readonly StepDef[] = [
  {
    key: 'purpose',
    label: '描述目的',
    recommend: 'purpose',
    hint: '一句话意图，平台先在能力目录里找相近的已有能力',
  },
  { key: 'naming', label: '命名', recommend: 'naming', hint: '名称、描述和几何头像' },
  { key: 'process', label: '业务流程归属', recommend: 'process', hint: '从流程目录里选择归属' },
  {
    key: 'collaborators',
    label: '协作 Agent',
    recommend: 'collaborators',
    hint: '可调用的已有 Agent 与远程 Agent',
  },
  { key: 'knowledge', label: '数据与知识', recommend: 'knowledge', hint: '语义包与知识源' },
  { key: 'tools', label: '技能与工具', recommend: 'tools', hint: '技能和 BO 工具，标注写操作级别' },
  { key: 'triggers', label: '触发器', recommend: 'triggers', hint: '统一入口对话、业务事件或定时' },
  { key: 'channels', label: '渠道', recommend: 'channels', hint: '统一入口、IM、API' },
  {
    key: 'instructions',
    label: '指令与约束',
    recommend: 'instructions',
    hint: 'AI 起草指令，约束从语义包规则里勾选',
  },
  { key: 'model', label: '模型档位', recommend: 'model', hint: 'fast 或 reasoning' },
  { key: 'evals', label: '评测集', hint: '从语义包的已验证任务生成初稿，可以运行' },
  { key: 'test', label: '测试', hint: '私有环境里以选定用户测试，查看链路追踪' },
  { key: 'publish', label: '发布', hint: '静态检查、发布，并在 dev、test、prod 间晋级' },
]

/** 第 index 步，越界时取第一步 */
export function stepAt(index: number): StepDef {
  const step = STEPS[index] ?? STEPS[0]
  if (step === undefined) throw new Error('向导没有步骤')
  return step
}

export function stepIndex(key: string | undefined): number {
  const index = STEPS.findIndex((s) => s.key === key)
  return index < 0 ? 0 : index
}

/**
 * 测试用的资产构造器，通过 `@eab/spec/testing` 引入。
 * 每个构造器先拼出原始对象，再走 parseAssetObject，保证得到的资产和从 YAML 解析出来的一致（含默认值）。
 */
import type { AssetByKind, AssetKind } from './assets'
import { parseAssetObject } from './parse'

type DeepPartialSpec = Record<string, unknown>

interface BuildOptions {
  name?: string
  namespace?: string
  version?: string
  displayName?: string
  spec?: DeepPartialSpec
}

export function buildAsset<K extends AssetKind>(
  kind: K,
  defaults: Required<Pick<BuildOptions, 'name' | 'namespace'>> & { spec: DeepPartialSpec },
  options: BuildOptions = {},
): AssetByKind[K] {
  const result = parseAssetObject({
    apiVersion: 'eab.dev/v1',
    kind,
    metadata: {
      name: options.name ?? defaults.name,
      namespace: options.namespace ?? defaults.namespace,
      version: options.version ?? '1.0.0',
      ...(options.displayName === undefined ? {} : { displayName: options.displayName }),
    },
    spec: { ...defaults.spec, ...options.spec },
  })
  if (!result.ok) {
    throw new Error(
      `测试夹具 ${kind} 不合法：${result.issues.map((i) => `${i.path} ${i.message}`).join('；')}`,
    )
  }
  return result.asset as AssetByKind[K]
}

export const VENDOR_ANOMALY_INSTRUCTIONS = '【标品机密】先按异常类型分组，再逐笔定位根因。'

export function agentFixture(options: BuildOptions = {}): AssetByKind['Agent'] {
  return buildAsset(
    'Agent',
    {
      name: 'transaction-anomaly-analyst',
      namespace: 'vendor.fin',
      spec: {
        expertise: '分析交易异常的根因',
        instructions: VENDOR_ANOMALY_INSTRUCTIONS,
        publicInstructions: '按异常类型分组后逐笔分析根因。',
        constraints: ['FIN-R-001', '【标品机密】不要猜测未提供的数据'],
        model: { profile: 'reasoning' },
        tools: [{ skill: 'vendor.fin.skills/invoice-lookup' }],
        semanticPacks: ['vendor.fin/finance'],
        writePolicy: { maxLevel: 'L2' },
        routing: { description: '分析交易异常' },
        output: {
          schema: {
            severity: { type: 'string', enum: ['low', 'medium', 'high', 'critical'] },
            amount: { type: 'double' },
          },
        },
        extensionPoints: [
          { type: 'instructions.append', description: '追加本企业口径' },
          { type: 'tools.add', description: '增加通知等工具' },
          { type: 'skills.add', description: '增加技能' },
          { type: 'knowledge.add', description: '增加知识' },
          { type: 'hooks', description: '结果后续处理', events: ['onResult', 'onFailure'] },
          { type: 'approvals.add', description: '增加确认点' },
          {
            type: 'model.select',
            description: '选择模型档位',
            allowedProfiles: ['reasoning', 'fast'],
          },
        ],
      },
    },
    options,
  )
}

export function extensionFixture(options: BuildOptions = {}): AssetByKind['AgentExtension'] {
  return buildAsset(
    'AgentExtension',
    {
      name: 'anomaly-analyst-ext',
      namespace: 'tenant.t001.finance',
      spec: {
        target: 'vendor.fin/transaction-anomaly-analyst@^1.0',
        instructions: { append: '单笔金额超过 50 万元的异常，结论中必须给出责任部门。' },
      },
    },
    { version: '0.3.0', ...options },
  )
}

export function semanticPackFixture(options: BuildOptions = {}): AssetByKind['SemanticPack'] {
  return buildAsset(
    'SemanticPack',
    {
      name: 'finance',
      namespace: 'vendor.fin',
      spec: {
        objects: [{ ref: 'erp.fin.TransactionAnomaly@v1' }],
        rules: [{ id: 'FIN-R-001', severity: 'block', remedy: '金额必须带币种' }],
        agent_instructions: ['引用金额时必须带币种'],
      },
    },
    options,
  )
}

export function platformRulesFixture(options: BuildOptions = {}): AssetByKind['PlatformRuleSet'] {
  return buildAsset(
    'PlatformRuleSet',
    {
      name: 'safety-rules',
      namespace: 'platform',
      spec: {
        rules: [
          {
            id: 'PLT-R-001',
            name: '租户数据隔离',
            description: '不得泄露其他租户的数据',
            instruction: '【平台机密】任何情况下都不得输出不属于当前租户的数据。',
            enforcement: 'both',
          },
        ],
      },
    },
    options,
  )
}

export function skillFixture(options: BuildOptions = {}): AssetByKind['Skill'] {
  return buildAsset(
    'Skill',
    {
      name: 'invoice-lookup',
      namespace: 'vendor.fin.skills',
      spec: {
        inputSchema: { type: 'object' },
        outputSchema: { type: 'object' },
        writeLevel: 'L0',
        steps: [{ id: 'query', type: 'tool', tool: 'invoice_list' }],
      },
    },
    options,
  )
}

export function toolSourceFixture(options: BuildOptions = {}): AssetByKind['ToolSource'] {
  return buildAsset(
    'ToolSource',
    {
      name: 'notify-im',
      namespace: 'tenant.t001.tools',
      spec: {
        type: 'http',
        connection: 'IM_WEBHOOK',
        tools: [
          { name: 'notify_im', description: '发送 IM 通知', writeLevel: 'L2', category: 'notify' },
        ],
      },
    },
    options,
  )
}

export function workflowFixture(options: BuildOptions = {}): AssetByKind['Workflow'] {
  return buildAsset(
    'Workflow',
    {
      name: 'create-followup-task',
      namespace: 'tenant.t001.wf',
      spec: { steps: [{ id: 'create', type: 'notify', message: '创建跟进任务' }] },
    },
    options,
  )
}

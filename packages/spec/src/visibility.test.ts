import { describe, expect, it } from 'vitest'
import {
  agentFixture,
  extensionFixture,
  platformRulesFixture,
  VENDOR_ANOMALY_INSTRUCTIONS,
} from './testing'
import { buildAsset } from './testing'
import {
  canReadAsset,
  canWriteNamespace,
  digestOf,
  formatViewer,
  isRedacted,
  parseViewer,
  redactAsset,
  type Viewer,
} from './visibility'

const TENANT: Viewer = { type: 'tenant', tenantId: 't001' }
const OTHER_TENANT: Viewer = { type: 'tenant', tenantId: 't002' }
const VENDOR: Viewer = { type: 'vendor' }
const PLATFORM: Viewer = { type: 'platform' }

describe('parseViewer', () => {
  it.each(['platform', 'vendor', 'tenant:t001'])('往返解析 %s', (value) => {
    expect(formatViewer(parseViewer(value))).toBe(value)
  })
  it('拒绝无法识别的身份', () => {
    expect(() => parseViewer('admin')).toThrow(/无法识别/)
  })
})

describe('canWriteNamespace', () => {
  it('只有本层身份能写本层命名空间', () => {
    expect(canWriteNamespace(VENDOR, 'vendor.fin')).toBe(true)
    expect(canWriteNamespace(TENANT, 'vendor.fin')).toBe(false)
    expect(canWriteNamespace(TENANT, 'platform')).toBe(false)
    expect(canWriteNamespace(TENANT, 'tenant.t001.finance')).toBe(true)
    expect(canWriteNamespace(OTHER_TENANT, 'tenant.t001.finance')).toBe(false)
    expect(canWriteNamespace(PLATFORM, 'platform')).toBe(true)
    expect(canWriteNamespace(PLATFORM, 'vendor.fin')).toBe(false)
  })
})

describe('canReadAsset', () => {
  const internalSuite = buildAsset('EvalSuite', {
    name: 'anomaly-internal',
    namespace: 'vendor.fin.evals',
    spec: {
      target: 'vendor.fin/transaction-anomaly-analyst',
      cases: [
        {
          id: 'c1',
          user: 'wangfang',
          question: 'q',
          assertions: [{ type: 'contains', text: 'x' }],
        },
      ],
    },
  })

  it('租户之间互相不可见，厂商看不到租户资产', () => {
    const ext = extensionFixture()
    expect(canReadAsset(TENANT, ext)).toBe(true)
    expect(canReadAsset(OTHER_TENANT, ext)).toBe(false)
    expect(canReadAsset(VENDOR, ext)).toBe(false)
    expect(canReadAsset(PLATFORM, ext)).toBe(true)
  })

  it('租户看不到标品的内部评测集', () => {
    expect(canReadAsset(TENANT, internalSuite)).toBe(false)
    expect(canReadAsset(VENDOR, internalSuite)).toBe(true)
  })
})

describe('redactAsset', () => {
  it('租户视角下标品指令、语气说明、自由文本约束被替换为占位，规则编号保留', () => {
    const agent = agentFixture({ spec: { additionalContext: '语气专业' } })
    const view = redactAsset(agent, TENANT)

    expect(isRedacted(view.spec.instructions)).toBe(true)
    expect(isRedacted(view.spec.additionalContext)).toBe(true)
    const constraints = view.spec.constraints as unknown[]
    expect(constraints[0]).toBe('FIN-R-001')
    expect(isRedacted(constraints[1])).toBe(true)
    expect(JSON.stringify(view)).not.toContain(VENDOR_ANOMALY_INSTRUCTIONS)
    expect(JSON.stringify(view)).not.toContain('【标品机密】')
    // 公开版指令、扩展点、工具清单对租户可见
    expect(view.spec.publicInstructions).toBeDefined()
    expect(view.spec.extensionPoints).toHaveLength(7)
  })

  it('占位的 digest 随原文变化，用于显示“已变更”', () => {
    const v1 = redactAsset(agentFixture(), TENANT)
    const v2 = redactAsset(agentFixture({ spec: { instructions: '新版指令' } }), TENANT)
    expect(v1.spec.instructions).not.toEqual(v2.spec.instructions)
    expect(digestOf('a')).toMatch(/^[0-9a-f]{8}$/)
  })

  it('平台规则对租户只保留名称和说明', () => {
    const view = redactAsset(platformRulesFixture(), TENANT)
    const rule = (view.spec.rules as Record<string, unknown>[])[0]
    expect(rule?.name).toBe('租户数据隔离')
    expect(isRedacted(rule?.instruction)).toBe(true)
    expect(JSON.stringify(view)).not.toContain('【平台机密】')
  })

  it('厂商、平台视角和租户看自己的资产时不裁剪', () => {
    const agent = agentFixture()
    expect(redactAsset(agent, VENDOR)).toBe(agent)
    expect(redactAsset(agent, PLATFORM)).toBe(agent)
    const ext = extensionFixture()
    expect(redactAsset(ext, TENANT)).toBe(ext)
  })
})

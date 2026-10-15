import { describe, expect, it } from 'vitest'
import { InvalidAssetRefError, formatAssetRef, isPinnedAssetRef, parseAssetRef } from './refs'
import { InMemoryAssetResolver, resolveAsset, resolveAssetOfKind, resolveVersion } from './resolver'
import { agentFixture, semanticPackFixture } from './testing'

describe('parseAssetRef', () => {
  it('解析带版本范围的引用', () => {
    expect(parseAssetRef('vendor.fin/transaction-anomaly-analyst@^1.0')).toEqual({
      namespace: 'vendor.fin',
      name: 'transaction-anomaly-analyst',
      range: '^1.0',
    })
    expect(formatAssetRef(parseAssetRef('tenant.t001.kb/playbook'))).toBe('tenant.t001.kb/playbook')
  })

  it.each([
    'no-slash',
    'vendor.fin/',
    'acme.fin/x',
    'vendor.fin/Bad_Name',
    'vendor.fin/x@not-a-range!',
  ])('拒绝非法引用 %s', (ref) => {
    expect(() => parseAssetRef(ref)).toThrow(InvalidAssetRefError)
  })

  it('区分锁定版本和版本范围', () => {
    expect(isPinnedAssetRef('vendor.pur/procurement@1.0.0')).toBe(true)
    expect(isPinnedAssetRef('vendor.pur/procurement@^1.0')).toBe(false)
    expect(isPinnedAssetRef('vendor.pur/procurement')).toBe(false)
  })
})

describe('resolver', () => {
  const resolver = new InMemoryAssetResolver([
    { asset: agentFixture({ version: '1.2.0' }), status: 'published' },
    { asset: agentFixture({ version: '1.3.0' }), status: 'draft' },
    { asset: agentFixture({ version: '2.0.0' }), status: 'published' },
    { asset: semanticPackFixture(), status: 'published' },
  ])

  it('默认只在已发布版本里取满足范围的最高版本', () => {
    const ref = parseAssetRef('vendor.fin/transaction-anomaly-analyst@^1.0')
    expect(resolveVersion(resolver, ref)).toBe('1.2.0')
    expect(resolveVersion(resolver, ref, { statuses: ['published', 'draft'] })).toBe('1.3.0')
  })

  it('不带范围时取最新的已发布版本', () => {
    expect(resolveAsset(resolver, 'vendor.fin/transaction-anomaly-analyst')?.metadata.version).toBe(
      '2.0.0',
    )
  })

  it('没有满足的版本或者 kind 不符时返回 undefined', () => {
    expect(resolveAsset(resolver, 'vendor.fin/transaction-anomaly-analyst@^3')).toBeUndefined()
    expect(resolveAssetOfKind(resolver, 'vendor.fin/finance', 'Agent')).toBeUndefined()
    expect(resolveAssetOfKind(resolver, 'vendor.fin/finance', 'SemanticPack')).toBeDefined()
  })

  it('版本列表按从新到旧排序', () => {
    expect(
      resolver.listVersions('vendor.fin', 'transaction-anomaly-analyst').map((v) => v.version),
    ).toEqual(['2.0.0', '1.3.0', '1.2.0'])
    expect(resolver.listVersions('vendor.fin', 'missing')).toEqual([])
  })
})

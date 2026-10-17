import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { DecisionBadge, SourceBadge, ToneBadge, WriteLevelBadge } from './badges'
import { ASSET_SOURCES, SOURCE_STYLES, resolveAssetSource } from './sources'

describe('resolveAssetSource', () => {
  it('按命名空间判断三层来源', () => {
    expect(resolveAssetSource({ namespace: 'vendor.fin' })).toBe('vendor')
    expect(resolveAssetSource({ namespace: 'tenant.t001.finance' })).toBe('tenant')
    expect(resolveAssetSource({ namespace: 'platform' })).toBe('platform')
  })

  it('远程 Agent 即使登记在租户命名空间，也标为外部来源', () => {
    expect(resolveAssetSource({ namespace: 'tenant.t001.remote', kind: 'RemoteAgent' })).toBe(
      'external',
    )
  })
})

describe('SOURCE_STYLES', () => {
  it('每类来源有不同的标签、图标和颜色', () => {
    const styles = ASSET_SOURCES.map((s) => SOURCE_STYLES[s])
    expect(new Set(styles.map((s) => s.label)).size).toBe(ASSET_SOURCES.length)
    expect(new Set(styles.map((s) => s.icon)).size).toBe(ASSET_SOURCES.length)
    expect(new Set(styles.map((s) => s.textClass)).size).toBe(ASSET_SOURCES.length)
  })
})

describe('badges', () => {
  it('来源标签显示中文名称', () => {
    expect(renderToStaticMarkup(<SourceBadge source="vendor" />)).toContain('标品')
  })

  it('写级别标签同时显示级别和含义', () => {
    const html = renderToStaticMarkup(<WriteLevelBadge level="L2" />)
    expect(html).toContain('L2')
    expect(html).toContain('需确认的写')
  })

  it('裁决标签可以带上鉴权点名称', () => {
    const html = renderToStaticMarkup(<DecisionBadge outcome="deny" checkpoint="鉴权点 3" />)
    expect(html).toContain('鉴权点 3')
    expect(html).toContain('拒绝')
  })

  it('通用色调标签渲染传入的文本，色调映射到对应的颜色令牌', () => {
    const html = renderToStaticMarkup(<ToneBadge tone="pending">待处理</ToneBadge>)
    expect(html).toContain('待处理')
    expect(html).toContain('bg-pending-soft')
  })
})

import { describe, expect, it } from 'vitest'
import { InvalidNamespaceError, layerOfNamespace, tenantIdOfNamespace } from './layers'

describe('layerOfNamespace', () => {
  it.each([
    ['platform', 'platform'],
    ['vendor.pur', 'vendor'],
    ['vendor.fin.close', 'vendor'],
    ['tenant.t001', 'tenant'],
    ['tenant.t001.finance', 'tenant'],
  ] as const)('%s 属于 %s 层', (namespace, layer) => {
    expect(layerOfNamespace(namespace)).toBe(layer)
  })

  it.each(['vendor', 'tenant', 'platform.x', 'acme.pur', 'Vendor.pur', 'vendor..pur', ''])(
    '拒绝不合法的命名空间 "%s"',
    (namespace) => {
      expect(() => layerOfNamespace(namespace)).toThrow(InvalidNamespaceError)
    },
  )
})

describe('tenantIdOfNamespace', () => {
  it('从租户命名空间取出租户编号', () => {
    expect(tenantIdOfNamespace('tenant.t001.finance')).toBe('t001')
  })

  it('非租户命名空间返回 undefined', () => {
    expect(tenantIdOfNamespace('vendor.pur')).toBeUndefined()
    expect(tenantIdOfNamespace('platform')).toBeUndefined()
  })
})

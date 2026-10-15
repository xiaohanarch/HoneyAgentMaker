/**
 * 生成一个租户的演示数据（主提示词 6.2 节）。同一个种子、同一天生成的结果完全相同。
 * 规模：约 40 个供应商、200 张采购订单、500 条交付记录、100 种物料、30 条交易异常。
 * t001 的故事数据：
 * - 供应商“百润精密”属于华东，采购订单 PO-2026-0117 计划交期在 120 天前且未交付；
 * - 华东 26 个供应商，华南 14 个；
 * - 本月有几条金额超过 50 万元的高风险交易异常（故事 B）；
 * - 资产核算本期有 3 个结账检查项未通过。
 */
import type * as schema from '../db/schema'
import {
  ANOMALY_TYPES,
  CITIES_EAST,
  CITIES_NORTH,
  CITIES_SOUTH,
  CLOSE_CHECKS_ASSET_ACCOUNTING,
  MATERIAL_SPECS,
  MATERIAL_TYPES,
  PAYMENT_TERMS,
  PRICE_TERMS,
  SUPPLIER_MIDDLE,
  SUPPLIER_PREFIX_T001,
  SUPPLIER_PREFIX_T002,
  SUPPLIER_SUFFIX,
  UNITS,
} from './names'
import { Random, addDays, isoDate, pad } from './random'

type Row<T extends { $inferInsert: unknown }> = T['$inferInsert']

export interface TenantData {
  suppliers: Row<typeof schema.suppliers>[]
  rfqs: Row<typeof schema.rfqs>[]
  quotations: Row<typeof schema.quotations>[]
  purchaseOrders: Row<typeof schema.purchaseOrders>[]
  poLines: Row<typeof schema.poLines>[]
  deliveries: Row<typeof schema.deliveries>[]
  materials: Row<typeof schema.materials>[]
  warehouses: Row<typeof schema.warehouses>[]
  inventoryBalances: Row<typeof schema.inventoryBalances>[]
  stockMovements: Row<typeof schema.stockMovements>[]
  apInvoices: Row<typeof schema.apInvoices>[]
  paymentPlans: Row<typeof schema.paymentPlans>[]
  transactionAnomalies: Row<typeof schema.transactionAnomalies>[]
  accountingPeriods: Row<typeof schema.accountingPeriods>[]
  closeCheckItems: Row<typeof schema.closeCheckItems>[]
}

interface TenantProfile {
  tenantId: string
  seed: number
  prefixes: readonly string[]
  /** 采购组织 → 供应商数量、城市、对应的公司主体 */
  orgs: readonly { id: string; suppliers: number; cities: readonly string[]; company: string }[]
  warehouses: readonly { name: string; company: string }[]
  story: boolean
}

export const STORY = {
  supplierId: 'S-0001',
  supplierName: '百润精密',
  poId: 'PO-2026-0117',
  poOverdueDays: 120,
  materialId: 'M-0007',
  latestRfqId: 'RFQ-2026-0058',
} as const

const PROFILES: Readonly<Record<string, TenantProfile>> = {
  t001: {
    tenantId: 't001',
    seed: 20261001,
    prefixes: SUPPLIER_PREFIX_T001,
    orgs: [
      { id: 'P-EAST', suppliers: 26, cities: CITIES_EAST, company: 'C100' },
      { id: 'P-SOUTH', suppliers: 14, cities: CITIES_SOUTH, company: 'C200' },
    ],
    warehouses: [
      { name: '本部一号仓', company: 'C100' },
      { name: '本部二号仓', company: 'C100' },
      { name: '华南仓', company: 'C200' },
    ],
    story: true,
  },
  t002: {
    tenantId: 't002',
    seed: 20261002,
    prefixes: SUPPLIER_PREFIX_T002,
    orgs: [
      { id: 'P-EAST', suppliers: 22, cities: CITIES_EAST, company: 'C100' },
      { id: 'P-NORTH', suppliers: 18, cities: CITIES_NORTH, company: 'C100' },
    ],
    warehouses: [{ name: '总部仓', company: 'C100' }],
    story: false,
  },
}

export function generateTenant(tenantId: string, today: Date): TenantData {
  const profile = PROFILES[tenantId]
  if (profile === undefined) throw new Error(`没有租户 ${tenantId} 的种子配置`)
  const rnd = new Random(profile.seed)
  const day = (offset: number): string => isoDate(addDays(today, offset))
  const t = { tenantId }

  // ── 物料与仓库 ──────────────────────────────────────────────────────
  const materials = Array.from({ length: 100 }, (_, i) => ({
    ...t,
    id: `M-${pad(i + 1, 4)}`,
    name: `${rnd.pick(MATERIAL_SPECS)}${rnd.pick(MATERIAL_TYPES)}`,
    category: rnd.pick(MATERIAL_TYPES),
    unit: rnd.pick(UNITS),
    safetyStock: rnd.int(5, 50) * 10,
  }))
  if (profile.story) {
    materials[6] = {
      ...t,
      id: STORY.materialId,
      name: '精密级连接器',
      category: '连接器',
      unit: '件',
      safetyStock: 800,
    }
  }
  const warehouses = profile.warehouses.map((w, i) => ({
    ...t,
    id: `W-${pad(i + 1, 2)}`,
    name: w.name,
    companyId: w.company,
  }))

  // ── 供应商 ─────────────────────────────────────────────────────────
  const usedNames = new Set<string>()
  const supplierName = (): string => {
    for (;;) {
      const name = `${rnd.pick(profile.prefixes)}${rnd.pick(SUPPLIER_MIDDLE)}${rnd.pick(SUPPLIER_SUFFIX)}`
      if (!usedNames.has(name) && name !== STORY.supplierName) {
        usedNames.add(name)
        return name
      }
    }
  }
  const suppliers = profile.orgs
    .flatMap((org) =>
      Array.from({ length: org.suppliers }, () => ({
        ...t,
        id: '',
        name: supplierName(),
        rating: rnd.pick(['A', 'B', 'B', 'C'] as const),
        purchasingOrg: org.id,
        city: rnd.pick(org.cities),
        priceTerms: rnd.pick(PRICE_TERMS),
        warrantyMonths: rnd.pick([6, 12, 18, 24]),
        leadTimeDays: rnd.int(7, 45),
        paymentTerms: rnd.pick(PAYMENT_TERMS),
      })),
    )
    .map((s, i) => ({ ...s, id: `S-${pad(i + 1, 4)}` }))
  if (profile.story) {
    suppliers[0] = {
      ...t,
      id: STORY.supplierId,
      name: STORY.supplierName,
      rating: 'B',
      purchasingOrg: 'P-EAST',
      city: '苏州',
      priceTerms: '年度框架价，季度调价',
      warrantyMonths: 12,
      leadTimeDays: 21,
      paymentTerms: '月结 60 天',
    }
  }
  const companyOf = (org: string): string =>
    profile.orgs.find((o) => o.id === org)?.company ?? 'C100'

  // ── 采购订单、订单行、交付 ──────────────────────────────────────────
  const purchaseOrders: TenantData['purchaseOrders'] = []
  const poLines: TenantData['poLines'] = []
  const deliveries: TenantData['deliveries'] = []
  for (let i = 1; i <= 200; i += 1) {
    const id = `PO-2026-${pad(i, 4)}`
    const isStory = profile.story && id === STORY.poId
    // 百润精密每 9 张订单出现一次，交付表现偏差
    const supplier = isStory || (profile.story && i % 9 === 0) ? suppliers[0]! : rnd.pick(suppliers)
    const createdOffset = isStory ? -160 : -rnd.int(10, 210)
    const lineCount = isStory ? 1 : rnd.int(1, 3)
    let total = 0
    let delivered = 0
    for (let n = 1; n <= lineCount; n += 1) {
      const material = isStory ? materials[6]! : rnd.pick(materials)
      const quantity = isStory ? 2000 : rnd.int(10, 200) * 10
      const unitPrice = isStory ? 45.8 : rnd.amount(5, 800)
      const plannedOffset = isStory ? -STORY.poOverdueDays : createdOffset + rnd.int(15, 60)
      const lineId = `${id}-${n}`
      total += quantity * unitPrice
      poLines.push({
        ...t,
        id: lineId,
        poId: id,
        lineNo: n,
        materialId: material.id,
        quantity,
        unitPrice,
        plannedDeliveryDate: day(plannedOffset),
      })

      const due = plannedOffset < 0
      const late = supplier.id === STORY.supplierId ? 0.5 : 0.2
      if (isStory || !due || rnd.chance(late * 0.6)) continue // 未交付
      delivered += 1
      const parts = rnd.chance(0.6) ? 2 : 1
      for (let p = 1; p <= parts; p += 1) {
        const actualOffset = Math.min(
          -1,
          plannedOffset + (rnd.chance(late) ? rnd.int(3, 40) : rnd.int(-5, 2)),
        )
        deliveries.push({
          ...t,
          id: `DL-${pad(deliveries.length + 1, 5)}`,
          poId: id,
          poLineId: lineId,
          plannedDate: day(plannedOffset),
          actualDate: day(actualOffset),
          quantity:
            parts === 1
              ? quantity
              : p === 1
                ? Math.round(quantity * 0.6)
                : quantity - Math.round(quantity * 0.6),
        })
      }
    }
    purchaseOrders.push({
      ...t,
      id,
      supplierId: supplier.id,
      purchasingOrg: supplier.purchasingOrg,
      companyId: companyOf(supplier.purchasingOrg),
      createdAt: day(createdOffset),
      status:
        delivered === 0 ? 'open' : delivered === lineCount ? 'delivered' : 'partially_delivered',
      currency: 'CNY',
      totalAmount: Math.round(total * 100) / 100,
    })
  }

  // ── 询价与报价 ──────────────────────────────────────────────────────
  const rfqs: TenantData['rfqs'] = []
  const quotations: TenantData['quotations'] = []
  for (let i = 1; i <= 60; i += 1) {
    const id = `RFQ-2026-${pad(i, 4)}`
    const isStory = profile.story && id === STORY.latestRfqId
    const supplier = isStory ? suppliers[0]! : rnd.pick(suppliers)
    const target = isStory ? 42 : rnd.amount(10, 500)
    rfqs.push({
      ...t,
      id,
      supplierId: supplier.id,
      purchasingOrg: supplier.purchasingOrg,
      materialId: isStory ? STORY.materialId : rnd.pick(materials).id,
      // 百润精密最近一次询价在 12 天前，排在它的其他询价都更早
      issuedAt: day(isStory ? -12 : -rnd.int(20, 300)),
      status: isStory ? 'quoted' : rnd.pick(['open', 'quoted', 'closed'] as const),
      targetPrice: target,
      currency: 'CNY',
    })
    const prices = isStory
      ? [46.5, 45.8, 47.2]
      : Array.from({ length: rnd.int(1, 3) }, () => rnd.amount(target * 0.9, target * 1.2))
    prices.forEach((unitPrice, n) =>
      quotations.push({
        ...t,
        id: `${id}-Q${n + 1}`,
        rfqId: id,
        lineNo: n + 1,
        quantity: rnd.int(5, 50) * 100,
        unitPrice,
      }),
    )
  }

  // ── 库存 ───────────────────────────────────────────────────────────
  const inventoryBalances: TenantData['inventoryBalances'] = []
  for (const material of materials) {
    for (const warehouse of warehouses.filter(() => rnd.chance(0.6))) {
      const isStory = profile.story && material.id === STORY.materialId && warehouse.id === 'W-01'
      const quantity = isStory ? 120 : rnd.int(0, 120) * 10
      const ageDays = isStory ? 30 : rnd.int(5, 420)
      inventoryBalances.push({
        ...t,
        id: `IB-${pad(inventoryBalances.length + 1, 4)}`,
        materialId: material.id,
        warehouseId: warehouse.id,
        companyId: warehouse.companyId,
        quantity,
        value: Math.round(quantity * rnd.amount(3, 300) * 100) / 100,
        ageDays,
        slowMoving: ageDays > 180 ? 'slow' : ageDays > 90 ? 'watch' : 'none',
      })
    }
  }
  if (
    profile.story &&
    !inventoryBalances.some((b) => b.materialId === STORY.materialId && b.warehouseId === 'W-01')
  ) {
    inventoryBalances.push({
      ...t,
      id: 'IB-STORY',
      materialId: STORY.materialId,
      warehouseId: 'W-01',
      companyId: 'C100',
      quantity: 120,
      value: 5496,
      ageDays: 30,
      slowMoving: 'none',
    })
  }
  const stockMovements = Array.from({ length: 400 }, (_, i) => {
    const warehouse = rnd.pick(warehouses)
    return {
      ...t,
      id: `SM-${pad(i + 1, 5)}`,
      materialId: rnd.pick(materials).id,
      warehouseId: warehouse.id,
      companyId: warehouse.companyId,
      direction: rnd.pick(['in', 'out'] as const),
      quantity: rnd.int(1, 50) * 10,
      date: day(-rnd.int(0, 90)),
    }
  })

  // ── 财务 ───────────────────────────────────────────────────────────
  const invoicedPos = purchaseOrders.filter((po) => po.status !== 'open').slice(0, 150)
  const apInvoices = invoicedPos.map((po, i) => ({
    ...t,
    id: `INV-2026-${pad(i + 1, 4)}`,
    supplierId: po.supplierId,
    poId: po.id,
    companyId: po.companyId,
    invoiceDate: day(-rnd.int(1, 120)),
    amount: po.totalAmount,
    currency: 'CNY',
    matchStatus: rnd.pick([
      'matched',
      'matched',
      'matched',
      'price_diff',
      'qty_diff',
      'unmatched',
    ] as const),
  }))
  const paymentPlans = apInvoices.map((inv, i) => ({
    ...t,
    id: `PP-2026-${pad(i + 1, 4)}`,
    invoiceId: inv.id,
    companyId: inv.companyId,
    dueDate: day(rnd.int(-30, 60)),
    amount: inv.amount,
    currency: inv.currency,
    status: rnd.pick(['planned', 'planned', 'paid', 'on_hold'] as const),
  }))

  const monthStart = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), 1))
  const daysIntoMonth = Math.max(
    0,
    Math.round((today.getTime() - monthStart.getTime()) / 86_400_000),
  )
  const storyAnomalies: Record<
    number,
    { type: string; severity: 'high' | 'critical'; amount: number; description: string }
  > = {
    3: {
      type: 'duplicate_payment',
      severity: 'critical',
      amount: 1_280_000,
      description: '同一供应商、同一金额在 3 天内付款两次',
    },
    11: {
      type: 'split_invoice',
      severity: 'high',
      amount: 620_000,
      description: '同一订单下 4 张发票金额均略低于 20 万元审批阈值',
    },
    21: {
      type: 'off_hours',
      severity: 'high',
      amount: 86_500,
      description: '凌晨 2 点过账的大额付款凭证',
    },
    26: {
      type: 'reversal',
      severity: 'high',
      amount: 158_000,
      description: '上月计提、本月冲销的跨期冲销凭证',
    },
  }
  const transactionAnomalies = Array.from({ length: 30 }, (_, i) => {
    const n = i + 1
    const story = profile.story ? storyAnomalies[n] : undefined
    const kind = rnd.pick(ANOMALY_TYPES)
    return {
      ...t,
      id: `ANO-2026-${pad(n, 4)}`,
      companyId: story !== undefined ? 'C100' : rnd.pick(warehouses).companyId,
      anomalyType: story?.type ?? kind.type,
      severity: story?.severity ?? rnd.pick(['low', 'low', 'medium', 'medium', 'high'] as const),
      amount: story?.amount ?? rnd.amount(2_000, 300_000),
      currency: 'CNY',
      detectedAt: day(story !== undefined ? -rnd.int(0, daysIntoMonth) : -rnd.int(0, 90)),
      status:
        story !== undefined ? ('open' as const) : rnd.pick(['open', 'open', 'closed'] as const),
      description: story?.description ?? `${kind.label}：规则引擎在过账后识别`,
      invoiceIds: JSON.stringify(apInvoices.length === 0 ? [] : [rnd.pick(apInvoices).id]),
    }
  })

  const period = isoDate(today).slice(0, 7)
  const previous = isoDate(addDays(monthStart, -1)).slice(0, 7)
  const companies = [...new Set(warehouses.map((w) => w.companyId))]
  const accountingPeriods = companies.flatMap((companyId) => [
    { ...t, id: previous, companyId, status: 'closed' as const },
    { ...t, id: period, companyId, status: 'closing' as const },
  ])
  const closeCheckItems = companies.flatMap((companyId) =>
    CLOSE_CHECKS_ASSET_ACCOUNTING.map((check, i) => {
      const failed = profile.story && companyId === 'C100' && check.fail !== null
      return {
        ...t,
        id: `CC-${companyId}-${period}-${pad(i + 1, 2)}`,
        periodId: period,
        companyId,
        product: 'asset-accounting',
        name: check.name,
        status: failed
          ? ('failed' as const)
          : i === CLOSE_CHECKS_ASSET_ACCOUNTING.length - 1
            ? ('not_started' as const)
            : ('passed' as const),
        owner: check.owner,
        blocker: failed ? check.fail : null,
      }
    }),
  )

  return {
    suppliers,
    rfqs,
    quotations,
    purchaseOrders,
    poLines,
    deliveries,
    materials,
    warehouses,
    inventoryBalances,
    stockMovements,
    apInvoices,
    paymentPlans,
    transactionAnomalies,
    accountingPeriods,
    closeCheckItems,
  }
}

/**
 * 演示用的在途运单（虚构数据）：按采购订单号或运单号查询物流节点。
 */
export interface Shipment {
  shipmentId: string
  purchaseOrder: string
  carrier: string
  status: 'in_transit' | 'delayed' | 'delivered'
  eta: string
  events: { at: string; location: string; note: string }[]
}

export const SHIPMENTS: readonly Shipment[] = [
  {
    shipmentId: 'SH-2026-0311',
    purchaseOrder: 'PO-2026-0117',
    carrier: '恒通物流',
    status: 'delayed',
    eta: '2026-11-08',
    events: [
      { at: '2026-10-09T08:00:00+08:00', location: '苏州仓', note: '已揽收' },
      { at: '2026-10-11T15:20:00+08:00', location: '无锡中转', note: '中转延误，等待改派车辆' },
    ],
  },
  {
    shipmentId: 'SH-2026-0342',
    purchaseOrder: 'PO-2026-0125',
    carrier: '恒通物流',
    status: 'in_transit',
    eta: '2026-10-20',
    events: [{ at: '2026-10-14T10:00:00+08:00', location: '宁波港', note: '已离港' }],
  },
]

export function track(id: string): Shipment | undefined {
  const key = id.trim().toUpperCase()
  return SHIPMENTS.find((s) => s.shipmentId === key || s.purchaseOrder === key)
}

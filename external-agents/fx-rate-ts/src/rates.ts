/**
 * 演示用的参考汇率（虚构数据，不代表任何真实报价）。1 单位外币折合多少人民币。
 */
export const RATES_TO_CNY: Readonly<Record<string, number>> = {
  USD: 7.12,
  EUR: 7.74,
  JPY: 0.0476,
  HKD: 0.912,
  CNY: 1,
}

const NAMES: readonly [string, string][] = [
  ['美元', 'USD'],
  ['欧元', 'EUR'],
  ['日元', 'JPY'],
  ['港币', 'HKD'],
  ['港元', 'HKD'],
  ['人民币', 'CNY'],
  ['USD', 'USD'],
  ['EUR', 'EUR'],
  ['JPY', 'JPY'],
  ['HKD', 'HKD'],
  ['CNY', 'CNY'],
]

export interface Quote {
  from: string
  to: string
  rate: number
  amount: number
  converted: number
}

/** 按出现顺序找出话术里的币种 */
function currencies(text: string): string[] {
  const found: { index: number; code: string }[] = []
  for (const [name, code] of NAMES) {
    const index = text.toUpperCase().indexOf(name.toUpperCase())
    if (index >= 0 && !found.some((f) => f.code === code)) found.push({ index, code })
  }
  return found.sort((a, b) => a.index - b.index).map((f) => f.code)
}

/** 从话术里解析金额和币种对，例如“1000 美元折合多少人民币”“欧元兑人民币汇率”；解析不出时返回 undefined */
export function quote(text: string): Quote | undefined {
  const codes = currencies(text)
  const from = codes[0]
  if (from === undefined) return undefined
  const to = codes.find((c) => c !== from) ?? 'CNY'
  if (from === to) return undefined
  const amountMatch = /(\d+(?:\.\d+)?)/.exec(text.replace(/,/g, ''))
  const amount = amountMatch?.[1] === undefined ? 1 : Number(amountMatch[1])
  const fromRate = RATES_TO_CNY[from]
  const toRate = RATES_TO_CNY[to]
  if (fromRate === undefined || toRate === undefined) return undefined
  const rate = fromRate / toRate
  return { from, to, rate, amount, converted: Math.round(amount * rate * 100) / 100 }
}

export function describe(q: Quote): string {
  const rate = q.rate >= 0.1 ? q.rate.toFixed(4) : q.rate.toFixed(6)
  return `参考汇率：1 ${q.from} = ${rate} ${q.to}；${q.amount} ${q.from} 约合 ${q.converted} ${q.to}（演示数据，非实时报价）`
}

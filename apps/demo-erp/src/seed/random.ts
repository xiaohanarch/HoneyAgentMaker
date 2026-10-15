/** 固定种子的伪随机数（mulberry32），同一个种子每次生成相同的演示数据 */
export class Random {
  #state: number

  constructor(seed: number) {
    this.#state = seed >>> 0
  }

  next(): number {
    this.#state = (this.#state + 0x6d2b79f5) >>> 0
    let t = this.#state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }

  /** [min, max] 之间的整数 */
  int(min: number, max: number): number {
    return min + Math.floor(this.next() * (max - min + 1))
  }

  pick<T>(items: readonly T[]): T {
    const item = items[this.int(0, items.length - 1)]
    if (item === undefined) throw new Error('不能从空列表里取值')
    return item
  }

  chance(probability: number): boolean {
    return this.next() < probability
  }

  /** 保留两位小数的金额 */
  amount(min: number, max: number): number {
    return Math.round((min + this.next() * (max - min)) * 100) / 100
  }
}

/** 日期按 YYYY-MM-DD 存储与比较 */
export function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(date: Date, days: number): Date {
  return new Date(date.getTime() + days * 86_400_000)
}

export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000)
}

export function pad(n: number, width: number): string {
  return String(n).padStart(width, '0')
}

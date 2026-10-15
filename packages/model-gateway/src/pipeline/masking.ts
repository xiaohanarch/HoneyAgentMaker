/**
 * 敏感数据脱敏（主提示词 8.2 节）：身份证号、银行账号、手机号在发给模型前替换成 [[MASK_n]]，
 * 模型返回后在文本和工具参数里还原。同一个值在一次运行里始终对应同一个占位符，
 * 模型可以照常引用它（例如把占位符作为工具参数），还原后工具收到的是真实值。
 */
interface MaskRule {
  type: 'id-card' | 'bank-account' | 'mobile'
  pattern: RegExp
}

/** 顺序有意义：18 位身份证号先于 16 至 19 位银行账号匹配，11 位手机号最后 */
const RULES: readonly MaskRule[] = [
  { type: 'id-card', pattern: /(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])/g },
  {
    type: 'bank-account',
    pattern: /(?<![0-9A-Za-z])\d{4}(?:[ -]?\d{4}){2,3}(?:[ -]?\d{1,3})?(?![0-9A-Za-z])/g,
  },
  { type: 'mobile', pattern: /(?<![0-9A-Za-z])1[3-9]\d{9}(?![0-9A-Za-z])/g },
]

const PLACEHOLDER = /\[\[MASK_(\d+)\]\]/g

export interface MaskEntry {
  placeholder: string
  type: MaskRule['type']
  value: string
}

export class Masker {
  readonly #byValue = new Map<string, MaskEntry>()
  readonly #byPlaceholder = new Map<string, MaskEntry>()

  /** 已登记的映射，可以序列化后随运行状态保存 */
  get entries(): MaskEntry[] {
    return [...this.#byPlaceholder.values()]
  }

  static from(entries: readonly MaskEntry[]): Masker {
    const masker = new Masker()
    for (const entry of entries) masker.#register(entry)
    return masker
  }

  #register(entry: MaskEntry): void {
    this.#byValue.set(entry.value, entry)
    this.#byPlaceholder.set(entry.placeholder, entry)
  }

  mask(text: string): { text: string; count: number } {
    let count = 0
    let result = text
    for (const rule of RULES) {
      result = result.replace(rule.pattern, (value) => {
        count += 1
        const existing = this.#byValue.get(value)
        if (existing !== undefined) return existing.placeholder
        const entry: MaskEntry = {
          placeholder: `[[MASK_${this.#byPlaceholder.size + 1}]]`,
          type: rule.type,
          value,
        }
        this.#register(entry)
        return entry.placeholder
      })
    }
    return { text: result, count }
  }

  unmask(text: string): string {
    return text.replace(
      PLACEHOLDER,
      (placeholder) => this.#byPlaceholder.get(placeholder)?.value ?? placeholder,
    )
  }

  /** 递归还原工具参数里的占位符 */
  unmaskValue(value: unknown): unknown {
    if (typeof value === 'string') return this.unmask(value)
    if (Array.isArray(value)) return value.map((v) => this.unmaskValue(v))
    if (value !== null && typeof value === 'object') {
      return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, this.unmaskValue(v)]))
    }
    return value
  }
}

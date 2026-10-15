/**
 * 输入输出内容过滤（主提示词 8.2 节）。这是提示词之外的确定性检查，规则保持简单、可读：
 * 输入侧拦截典型的提示注入特征；输出侧拦截复述系统指令的内容。
 */
const INJECTION_PATTERNS: readonly { id: string; pattern: RegExp }[] = [
  {
    id: 'ignore-instructions',
    pattern:
      /忽略(?:掉)?(?:你)?(?:之前|以上|前面|上面|所有)(?:的)?(?:所有)?(?:指令|提示|规则|设定)/,
  },
  {
    id: 'ignore-instructions-en',
    pattern:
      /ignore\s+(?:all\s+)?(?:the\s+)?(?:previous|prior|above)\s+(?:instructions|prompts?|rules)/i,
  },
  {
    id: 'reveal-prompt',
    pattern:
      /(?:输出|打印|告诉我|显示|复述|泄露)(?:一下)?(?:你的)?(?:完整的?)?(?:系统提示词|系统指令|原始指令|system\s*prompt)/i,
  },
  {
    id: 'reveal-prompt-en',
    pattern: /(?:reveal|print|show|repeat)\s+(?:your\s+)?(?:system\s+prompt|instructions)/i,
  },
  {
    id: 'jailbreak',
    pattern: /(?:你现在是|扮演).{0,20}(?:不受|没有|无视).{0,6}(?:限制|约束|规则)/,
  },
]

export interface FilterVerdict {
  blocked: boolean
  ruleId?: string
}

export function checkInput(text: string): FilterVerdict {
  const hit = INJECTION_PATTERNS.find((p) => p.pattern.test(text))
  return hit === undefined ? { blocked: false } : { blocked: true, ruleId: hit.id }
}

export const INPUT_BLOCKED_REPLY =
  '这个请求包含试图改变或获取系统指令的内容，已被内容过滤拦截。请直接描述你要处理的业务问题。'

/** 系统提示词里的片段标记，出现在输出里说明模型在复述系统提示词 */
const SEGMENT_MARKER =
  /<!--\s*(?:platform-rule|vendor-instructions|vendor-context|vendor-constraint)[^>]*-->/g

/** 判断“复述”的最短行长：太短的行（如“1.”）在正常回答里也会出现 */
const MIN_ECHO_LENGTH = 16

export interface ConfidentialText {
  id: string
  text: string
}

export interface OutputFilterResult {
  text: string
  /** 被复述的保密片段编号 */
  leakedSegments: string[]
  markersRemoved: number
}

/**
 * 输出过滤：去掉片段标记；回复里逐字出现保密片段（标品指令、平台规则原文）中足够长的行时，
 * 把这些行遮蔽并报告片段编号，由监督层产生告警（ADR 0004）。
 */
export function filterOutput(
  text: string,
  confidential: readonly ConfidentialText[],
): OutputFilterResult {
  let markersRemoved = 0
  let result = text.replace(SEGMENT_MARKER, () => {
    markersRemoved += 1
    return ''
  })
  const leaked = new Set<string>()
  for (const segment of confidential) {
    for (const line of segment.text.split('\n').map((l) => l.trim())) {
      if (line.length < MIN_ECHO_LENGTH || !result.includes(line)) continue
      leaked.add(segment.id)
      result = result.split(line).join('［已遮蔽：内部指令］')
    }
  }
  return { text: result, leakedSegments: [...leaked], markersRemoved }
}

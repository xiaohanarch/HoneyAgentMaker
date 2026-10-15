import {
  PLATFORM_RULE_LABEL,
  VENDOR_INSTRUCTION_LABEL,
  redact,
  type Redacted,
  type RedactOptions,
  type Viewer,
} from '@eab/spec'
import type { EffectiveDefinition, Segment } from './types'

export type SegmentView = Omit<Segment, 'text'> & { text: string | Redacted }

export type EffectiveDefinitionView = Omit<EffectiveDefinition, 'segments'> & {
  segments: SegmentView[]
}

/**
 * 按查看者裁剪有效定义（主提示词 5.4 节第 7 条、10.4 节）。
 * 租户视角下，标品指令和平台规则原文替换为不可展开的占位块，平台规则只保留规则名；
 * 来源标记、语义包指令、租户自己的内容照常显示。
 */
export function viewEffectiveDefinition(
  definition: EffectiveDefinition,
  viewer: Viewer,
  options: RedactOptions = {},
): EffectiveDefinitionView {
  if (viewer.type !== 'tenant') return definition
  return {
    ...definition,
    segments: definition.segments.map((segment) =>
      segment.confidential
        ? {
            ...segment,
            text: redact(
              segment.text,
              segment.kind === 'platform-rule' ? PLATFORM_RULE_LABEL : VENDOR_INSTRUCTION_LABEL,
              options,
            ),
          }
        : segment,
    ),
  }
}

/** 把有效定义的指令片段拼成发给模型的系统提示词，每段带来源标记 */
export function renderInstructions(definition: EffectiveDefinition): string {
  return definition.segments
    .map((s) => `<!-- ${s.kind} ${s.source} ${s.field} -->\n${s.text.trim()}`)
    .join('\n\n')
}

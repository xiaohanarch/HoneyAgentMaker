import {
  PLATFORM_RULE_LABEL,
  VENDOR_INSTRUCTION_LABEL,
  redact,
  type Redacted,
  type RedactOptions,
  type Viewer,
} from '@eab/spec'
import type { PromptSegment, SpanRecord } from './types'

export type PromptSegmentView = Omit<PromptSegment, 'text'> & { text: string | Redacted }
export type SpanView = Omit<SpanRecord, 'promptSegments'> & { promptSegments?: PromptSegmentView[] }

/**
 * 能否查看这条追踪：平台运维可以看全部；租户只能看本租户的运行；
 * 厂商研发看不到任何租户的运行数据（主提示词 5.2 节）。
 */
export function canViewTrace(viewer: Viewer, spans: readonly SpanRecord[]): boolean {
  if (viewer.type === 'platform') return true
  if (viewer.type === 'vendor') return false
  return spans.length > 0 && spans.every((s) => s.tenantId === viewer.tenantId)
}

/**
 * 按查看者裁剪追踪（ADR 0004）：租户视角下，模型调用 span 里的保密片段换成不可展开的占位块，
 * 平台规则只保留规则名。片段之外的属性在写入时就不含指令原文。
 */
export function viewTrace(
  spans: readonly SpanRecord[],
  viewer: Viewer,
  options: RedactOptions = {},
): SpanView[] {
  if (viewer.type !== 'tenant') return [...spans]
  return spans.map((span) =>
    span.promptSegments === undefined
      ? span
      : {
          ...span,
          promptSegments: span.promptSegments.map((segment) =>
            segment.confidential
              ? {
                  ...segment,
                  text: redact(
                    segment.text,
                    segment.kind === 'platform-rule'
                      ? PLATFORM_RULE_LABEL
                      : VENDOR_INSTRUCTION_LABEL,
                    options,
                  ),
                }
              : segment,
          ),
        },
  )
}

export interface SpanNode<S extends SpanView = SpanView> {
  span: S
  children: SpanNode<S>[]
}

/** 把扁平的 span 列表组织成树，同级按开始时间排序 */
export function buildTree<S extends SpanView>(spans: readonly S[]): SpanNode<S>[] {
  const nodes = new Map(spans.map((span) => [span.spanId, { span, children: [] as SpanNode<S>[] }]))
  const roots: SpanNode<S>[] = []
  for (const node of nodes.values()) {
    const parent = node.span.parentSpanId === null ? undefined : nodes.get(node.span.parentSpanId)
    if (parent === undefined) roots.push(node)
    else parent.children.push(node)
  }
  const sort = (list: SpanNode<S>[]): SpanNode<S>[] =>
    list
      .sort((a, b) => a.span.startTime.localeCompare(b.span.startTime))
      .map((n) => ({ ...n, children: sort(n.children) }))
  return sort(roots)
}

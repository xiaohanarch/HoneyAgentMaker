/**
 * pnpm assets:assemble <namespace/name> [--tenant t001] [--as tenant|vendor|platform] [--json]
 * 打印有效定义，用于自测组装结果（主提示词 5.4 节）。租户视角下标品指令和平台规则原文显示为占位块。
 */
import { parseArgs, styleText } from 'node:util'
import type { EffectiveDefinitionView, SegmentView } from '@eab/assembler'
import { isRedacted, type AssetLayer, type Viewer } from '@eab/spec'
import { assembleFromRegistry } from '../registry/effective'
import { loadRepositoryRegistry } from './repo-registry'

type Color = Parameters<typeof styleText>[0]

/** 与界面一致的来源配色：标品靛紫、租户青绿、平台石板灰 */
const LAYER_STYLE: Readonly<Record<AssetLayer, { label: string; color: Color }>> = {
  platform: { label: '平台', color: 'gray' },
  vendor: { label: '标品', color: 'magenta' },
  tenant: { label: '租户', color: 'cyan' },
}

const SEGMENT_TITLE: Readonly<Record<SegmentView['kind'], string>> = {
  'platform-rule': '平台安全规则',
  'vendor-instructions': '标品指令',
  'vendor-context': '标品语气与风格',
  'vendor-constraint': '标品约束',
  'semantic-instruction': '语义包指令',
  'tenant-instructions': '租户追加指令',
  'tenant-constraint': '租户追加约束',
}

const tag = (layer: AssetLayer): string =>
  styleText(LAYER_STYLE[layer].color, `[${LAYER_STYLE[layer].label}]`)

const heading = (text: string): string =>
  styleText('bold', `\n── ${text} ${'─'.repeat(Math.max(0, 40 - text.length * 2))}`)

function indent(text: string): string {
  return text
    .trim()
    .split('\n')
    .map((line) => `    ${line}`)
    .join('\n')
}

function renderSegment(segment: SegmentView): string {
  const title = `${SEGMENT_TITLE[segment.kind]}${segment.label === undefined ? '' : ` ${segment.label}`}`
  const meta = styleText('dim', `${segment.source}  ${segment.field}`)
  const body = isRedacted(segment.text)
    ? styleText('dim', `    🔒 ${segment.text.label}  #${segment.text.digest}`)
    : segment.layer === 'tenant'
      ? styleText(['cyan', 'bold'], indent(segment.text))
      : indent(segment.text)
  return `${tag(segment.layer)} ${title}  ${meta}\n${body}`
}

function render(definition: EffectiveDefinitionView, viewer: Viewer): string {
  const p = definition.provenance
  const lines = [
    styleText('bold', `有效定义 · ${definition.displayName}`),
    `Agent      ${p.agent}`,
    `身份       ${p.identity}`,
    `查看者     ${viewer.type === 'tenant' ? `tenant:${viewer.tenantId}` : viewer.type}`,
    ...(p.tenantId === undefined ? [] : [`租户       ${p.tenantId}`]),
    `扩展       ${p.extensions.length === 0 ? '无' : p.extensions.join('、')}`,
    ...(p.resolvedTarget === undefined
      ? []
      : [`版本解析   ${p.resolvedTarget.ranges.join(' ∩ ')} → ${p.resolvedTarget.version}`]),
    `模型档位   ${definition.model.profile}（${LAYER_STYLE[definition.model.layer].label}决定）`,
    `写级别上限 ${definition.writePolicy.maxLevel}`,
    heading('指令（按组装顺序）'),
    ...definition.segments.map(renderSegment),
    heading('工具'),
    ...definition.tools.map(
      (t) =>
        `${tag(t.layer)} ${t.name.padEnd(36)} ${(t.writeLevel ?? '—').padEnd(3)} ${styleText('dim', t.source)}`,
    ),
    heading('人工确认'),
    ...(definition.approvals.length === 0
      ? ['（无）']
      : definition.approvals.map(
          (a) =>
            `${tag(a.layer)} ${a.tool}  ${a.policy}${a.when === undefined ? '' : `  当 ${a.when}`}`,
        )),
    heading('钩子'),
    ...(definition.hooks.length === 0
      ? ['（无）']
      : definition.hooks.map(
          (h) =>
            `${tag(h.layer)} ${h.event}  ${h.workflow ?? h.skill}${h.when === undefined ? '' : `  当 ${h.when}`}  失败时${h.failurePolicy === 'isolate' ? '隔离' : '中止'}`,
        )),
  ]
  return lines.join('\n')
}

async function main(): Promise<number> {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      tenant: { type: 'string' },
      as: { type: 'string', default: 'tenant' },
      json: { type: 'boolean', default: false },
    },
  })
  const agent = positionals[0]
  if (agent === undefined) {
    console.error(
      '用法：pnpm assets:assemble <namespace/name> [--tenant t001] [--as tenant|vendor|platform] [--json]',
    )
    return 2
  }
  const tenantId = values.tenant
  const viewer: Viewer =
    values.as === 'tenant'
      ? { type: 'tenant', tenantId: tenantId ?? 't001' }
      : values.as === 'vendor' || values.as === 'platform'
        ? { type: values.as }
        : (() => {
            throw new Error(`--as 只能是 tenant、vendor 或 platform，收到 ${values.as}`)
          })()

  const { services, seed } = await loadRepositoryRegistry()
  for (const failure of seed.failed)
    console.error(styleText('red', `资产加载失败 ${failure.file}：${failure.message}`))

  const outcome = assembleFromRegistry(services.registry, viewer, {
    agent,
    ...(tenantId === undefined ? {} : { tenantId }),
  })
  services.db.$client.close()

  if (!outcome.ok) {
    for (const issue of outcome.issues)
      console.error(styleText('red', `[${issue.code}] ${issue.message}`))
    return 1
  }
  console.info(values.json ? JSON.stringify(outcome.view, null, 2) : render(outcome.view, viewer))
  for (const issue of outcome.issues)
    console.info(styleText('yellow', `警告 [${issue.code}] ${issue.message}`))
  return 0
}

process.exitCode = await main()

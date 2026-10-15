import type { EffectiveDefinition } from '@eab/assembler'
import { isBoOperation, type BoOperation } from '@eab/demo-directory'
import { resolveAsset, type AssetResolver, type Step } from '@eab/spec'

/**
 * Agent 声明的 BO 操作，作为 Agent 令牌的 scope（主提示词 7.1 节“Agent 声明的权限范围”）。
 * 来源有两类：直接挂载的 bo-api 工具，以及所挂技能里调用 bo-api 工具来源的步骤。
 */
export function declaredBoOperations(
  definition: EffectiveDefinition,
  resolver: AssetResolver,
): BoOperation[] {
  const ops = new Set<BoOperation>()
  // 有效定义里的来源带精确版本（namespace/name@1.1.0），可以直接作为版本范围解析
  const isBoSource = (ref: string): boolean => {
    const source = resolveAsset(resolver, ref, { statuses: ['published', 'testing', 'draft'] })
    return source?.kind === 'ToolSource' && source.spec.type === 'bo-api'
  }

  for (const tool of definition.tools) {
    if (tool.type === 'api' && isBoSource(tool.source) && isBoOperation(tool.name))
      ops.add(tool.name)
    if (tool.type === 'skill') {
      const skill = resolveAsset(resolver, tool.source, {
        statuses: ['published', 'testing', 'draft'],
      })
      if (skill?.kind !== 'Skill') continue
      for (const step of flatten(skill.spec.steps)) {
        if (step.type === 'tool' && step.tool !== undefined && step.source !== undefined) {
          if (isBoSource(step.source) && isBoOperation(step.tool)) ops.add(step.tool)
        }
      }
    }
  }
  return [...ops]
}

function flatten(steps: readonly Step[]): Step[] {
  return steps.flatMap((s) => [
    s,
    ...flatten(s.then ?? []),
    ...flatten(s.else ?? []),
    ...(s.branches ?? []).flatMap((b) => flatten(b)),
  ])
}

import {
  approvalEnvironment,
  matchingApprovals,
  strictestPolicy,
  type EffectiveApproval,
  type EffectiveDefinition,
} from '@eab/assembler'

/**
 * 把调用上下文整理成规则可用的事实。字段齐全、没有缺失的键，规则里不必写 has() 判断；
 * 缺少的信息用空字符串或空列表表示。
 */

/** 调用模式（主提示词 7.2 节） */
export type CallMode = 'on-behalf' | 'delegated' | 'integration'

export interface SubjectFacts {
  userId: string
  tenantId: string
  roles: readonly string[]
  /** 用户的业务权限（可调用的 BO 操作） */
  operations: readonly string[]
}

export interface EntryContext {
  subject: SubjectFacts
  tenantId: string
  assistant?: { ref: string; allowedRoles: readonly string[] }
}

export function buildEntryInput(ctx: EntryContext): Record<string, unknown> {
  return {
    subject: { ...ctx.subject },
    target: {
      tenantId: ctx.tenantId,
      assistant: ctx.assistant?.ref ?? '',
      allowedRoles: [...(ctx.assistant?.allowedRoles ?? [])],
    },
  }
}

export interface HandoffContext {
  subject: SubjectFacts
  caller: { type: 'router' | 'assistant' | 'agent' | 'external'; ref: string; depth: number }
  target: {
    ref: string
    kind: 'Agent' | 'RemoteAgent' | 'Assistant'
    enabledForTenant: boolean
    allowedCallers: readonly string[]
  }
}

export function buildHandoffInput(ctx: HandoffContext): Record<string, unknown> {
  return {
    subject: { ...ctx.subject },
    caller: { ...ctx.caller },
    target: { ...ctx.target, allowedCallers: [...ctx.target.allowedCallers] },
  }
}

export interface ToolContext {
  subject: SubjectFacts
  mode: CallMode
  definition: EffectiveDefinition
  toolName: string
  /** 工具入参，用于求值确认点的 when 条件 */
  toolInput?: Record<string, unknown>
  tenant: { l3Enabled: boolean }
  delegation?: { id: string; scope: readonly string[] }
  /** 涉及单据的写操作：单据编号、本次动作、提交人 */
  document?: { id: string; action: 'submit' | 'approve' | ''; submittedBy: string }
}

/**
 * 只保留 when 条件成立的确认点。条件求值出错时按“命中”处理：宁可多一次确认，也不漏掉。
 */
function applicableApprovals(
  approvals: readonly EffectiveApproval[],
  toolInput: Record<string, unknown>,
  subject: SubjectFacts,
): EffectiveApproval[] {
  const env = approvalEnvironment()
  return approvals.filter((approval) => {
    if (approval.when === undefined) return true
    try {
      return (
        env.evaluate(approval.when, {
          input: toolInput,
          user: { id: subject.userId, roles: [...subject.roles] },
        }) === true
      )
    } catch {
      return true
    }
  })
}

export function buildToolInput(ctx: ToolContext): Record<string, unknown> {
  const tool = ctx.definition.tools.find((t) => t.name === ctx.toolName)
  const approvals =
    tool === undefined
      ? []
      : applicableApprovals(
          matchingApprovals(ctx.definition.approvals, tool),
          ctx.toolInput ?? {},
          ctx.subject,
        )
  const policy = strictestPolicy(approvals)
  const strictest = approvals.find((a) => a.policy === policy)
  return {
    subject: { ...ctx.subject },
    mode: ctx.mode,
    agent: { ref: ctx.definition.provenance.agent },
    tool: {
      name: ctx.toolName,
      declared: tool !== undefined,
      writeLevel: tool?.writeLevel ?? '',
      category: tool?.category ?? '',
      approvalPolicy: policy,
      approverRole: strictest?.approverRole ?? '',
      inDelegationScope: ctx.delegation?.scope.includes(ctx.toolName) ?? false,
    },
    tenant: { ...ctx.tenant },
    delegation: { id: ctx.delegation?.id ?? '' },
    document: {
      id: ctx.document?.id ?? '',
      action: ctx.document?.action ?? '',
      submittedBy: ctx.document?.submittedBy ?? '',
    },
  }
}

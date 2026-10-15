import type { FastifyInstance } from 'fastify'
import type { ZodTypeProvider } from 'fastify-type-provider-zod'
import { z } from 'zod'
import {
  CHECKPOINT_LABELS,
  buildEntryInput,
  buildHandoffInput,
  buildToolInput,
  evaluate,
  type Decision,
} from '@eab/policy'
import { formatAssetRef, parseAssetRef, resolveAsset } from '@eab/spec'
import type { AuditStore } from '../audit/audit-store'
import { AuthHeader, subjectFacts, type RequirePrincipal } from '../http/auth'
import { sendError, success } from '../http/envelope'
import { assembleFromRegistry } from '../registry/effective'
import type { PlatformServices } from '../services'

const AssetRefString = z.string().regex(/^[a-z0-9.-]+\/[a-z0-9-]+$/, '写作 namespace/name')

const EvaluateBody = z.discriminatedUnion('checkpoint', [
  z.object({ checkpoint: z.literal('entry'), assistant: AssetRefString.optional() }),
  z.object({
    checkpoint: z.literal('handoff'),
    target: AssetRefString,
    caller: z
      .object({
        type: z.enum(['router', 'assistant', 'agent', 'external']),
        ref: z.string(),
        depth: z.number().int().min(0),
      })
      .default({ type: 'router', ref: 'router', depth: 0 }),
  }),
  z.object({
    checkpoint: z.literal('tool'),
    agent: AssetRefString,
    tool: z.string(),
    toolInput: z.record(z.string(), z.unknown()).optional(),
    mode: z.enum(['on-behalf', 'delegated', 'integration']).default('on-behalf'),
    delegationId: z.string().optional(),
    document: z
      .object({ id: z.string(), action: z.enum(['submit', 'approve', '']), submittedBy: z.string() })
      .optional(),
  }),
])

/** 每一次裁决写入审计（主提示词 7.3 节） */
export function recordDecision(
  audit: AuditStore,
  decision: Decision,
  context: { actor: string; tenantId: string; userId: string; target: string; agent?: string; tool?: string; mode?: string },
): void {
  audit.append({
    actor: context.actor,
    action: `policy.${decision.checkpoint}`,
    target: context.target,
    decision: decision.outcome,
    reason: `${CHECKPOINT_LABELS[decision.checkpoint]}：${decision.reason}`,
    tenantId: context.tenantId,
    userId: context.userId,
    checkpoint: decision.checkpoint,
    ...(decision.ruleId === null ? {} : { ruleId: decision.ruleId }),
    ...(context.agent === undefined ? {} : { agent: context.agent }),
    ...(context.tool === undefined ? {} : { tool: context.tool }),
    ...(context.mode === undefined ? {} : { mode: context.mode }),
    detail: { rulesVersion: decision.rulesVersion, requirements: decision.requirements },
  })
}

export function registerPolicyRoutes(app: FastifyInstance, services: PlatformServices, requirePrincipal: RequirePrincipal): void {
  const r = app.withTypeProvider<ZodTypeProvider>()

  r.get('/policy/v1/rules', { schema: { tags: ['policy'], summary: '当前生效的策略规则（治理中心查看）' } }, async () =>
    success(services.policyRules),
  )

  r.post(
    '/policy/v1/evaluate',
    {
      schema: {
        tags: ['policy'],
        summary: '策略试算：以当前用户身份对鉴权点 1 至 3 做一次判定，结果写入审计',
        headers: AuthHeader,
        body: EvaluateBody,
      },
    },
    async (request, reply) => {
      const principal = await requirePrincipal(request, reply)
      if (principal === undefined) return reply
      const { user, viewer } = principal
      if (viewer.type !== 'tenant') {
        return sendError(reply, 403, { code: 'TENANT_ONLY', message: '策略试算以租户用户身份进行' })
      }
      const tenantId = viewer.tenantId
      const subject = subjectFacts(user)
      const settings = services.tenantSettings.get(tenantId)
      const body = request.body

      let decision: Decision
      let target: string
      let extra: { agent?: string; tool?: string; mode?: string } = {}
      if (body.checkpoint === 'entry') {
        target = body.assistant ?? '统一入口'
        decision = evaluate(
          services.policyRules,
          'entry',
          buildEntryInput({
            subject,
            tenantId,
            ...(body.assistant === undefined
              ? {}
              : { assistant: { ref: body.assistant, allowedRoles: settings.assistantRoles[body.assistant] ?? [] } }),
          }),
        )
      } else if (body.checkpoint === 'handoff') {
        target = body.target
        const asset = resolveAsset(services.registry.resolverFor(viewer), body.target)
        decision = evaluate(
          services.policyRules,
          'handoff',
          buildHandoffInput({
            subject,
            caller: body.caller,
            target: {
              ref: body.target,
              kind: asset?.kind === 'RemoteAgent' ? 'RemoteAgent' : asset?.kind === 'Assistant' ? 'Assistant' : 'Agent',
              enabledForTenant: asset !== undefined,
              allowedCallers: asset?.kind === 'RemoteAgent' ? asset.spec.allowedCallers.map((c) => formatAssetRef(parseAssetRef(c))) : [],
            },
          }),
        )
      } else {
        target = `${body.agent} → ${body.tool}`
        const outcome = assembleFromRegistry(services.registry, viewer, { agent: body.agent, tenantId })
        if (!outcome.ok) return sendError(reply, 422, { code: 'ASSEMBLY_FAILED', message: '组装失败', details: outcome.issues })
        const delegation = body.delegationId === undefined ? undefined : services.delegations.get(body.delegationId)
        decision = evaluate(
          services.policyRules,
          'tool',
          buildToolInput({
            subject,
            mode: body.mode,
            definition: outcome.definition,
            toolName: body.tool,
            ...(body.toolInput === undefined ? {} : { toolInput: body.toolInput }),
            tenant: { l3Enabled: settings.l3Enabled },
            ...(delegation === undefined ? {} : { delegation: { id: delegation.id, scope: delegation.scope } }),
            ...(body.document === undefined ? {} : { document: body.document }),
          }),
        )
        extra = { agent: outcome.definition.provenance.agent, tool: body.tool, mode: body.mode }
      }

      recordDecision(services.audit, decision, { actor: user.id, tenantId, userId: user.id, target, ...extra })
      return success(decision)
    },
  )
}

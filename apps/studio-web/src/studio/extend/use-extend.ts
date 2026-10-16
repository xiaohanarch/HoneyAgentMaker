import { useMemo } from 'react'
import { useIdentity } from '../../identity/identity-context'
import type { DraftSummary } from '../api'
import { extendApi, type ExtendApi } from './api'

export function useExtendApi(): { api: ExtendApi; ready: boolean } {
  const { token } = useIdentity()
  const api = useMemo(() => extendApi(token), [token])
  return { api, ready: token !== undefined }
}

export const DRAFT_KIND_LABELS: Record<DraftSummary['kind'], string> = {
  Agent: '新建 Agent',
  AgentExtension: '标品扩展',
  Skill: '技能',
}

/** 草稿按种类打开对应的编辑器 */
export function draftPath(draft: Pick<DraftSummary, 'id' | 'kind'>): string {
  switch (draft.kind) {
    case 'AgentExtension':
      return `/studio/extensions/${draft.id}`
    case 'Skill':
      return `/studio/skills/${draft.id}`
    default:
      return `/studio/drafts/${draft.id}`
  }
}

/** 引用里去掉版本约束：vendor.fin/x@^1.0 → vendor.fin/x */
export function bareRef(ref: string): string {
  const at = ref.indexOf('@')
  return at < 0 ? ref : ref.slice(0, at)
}

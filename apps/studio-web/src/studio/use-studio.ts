import { useCallback, useMemo } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useIdentity } from '../identity/identity-context'
import { studioApi, type DraftView, type StudioApi } from './api'

export function useStudioApi(): { api: StudioApi; ready: boolean } {
  const { token } = useIdentity()
  const api = useMemo(() => studioApi(token), [token])
  return { api, ready: token !== undefined }
}

export function draftKey(id: string): readonly unknown[] {
  return ['studio', 'draft', id]
}

/** 草稿：读取一次，之后每次改动用接口返回的最新 DraftView 覆盖缓存 */
export function useDraft(id: string): {
  draft: DraftView | undefined
  error: Error | null
  setDraft: (view: DraftView) => void
} {
  const { api, ready } = useStudioApi()
  const client = useQueryClient()
  const { data, error } = useQuery({
    queryKey: draftKey(id),
    queryFn: () => api.draft(id),
    enabled: ready,
  })
  const setDraft = useCallback(
    (view: DraftView) => client.setQueryData(draftKey(id), view),
    [client, id],
  )
  return { draft: data, error, setDraft }
}

export function useCatalog() {
  const { api, ready } = useStudioApi()
  return useQuery({
    queryKey: ['studio', 'catalog'],
    queryFn: () => api.catalog(),
    enabled: ready,
    staleTime: 60_000,
  })
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败'
}

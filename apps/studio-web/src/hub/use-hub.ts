import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { deleteJson, getJson, postJson } from '../assist/api'
import { useIdentity } from '../identity/identity-context'

/** 治理中心的查询：键里带上当前用户，切换身份后重新取数 */
export function useHubQuery<T>(path: string, enabled = true) {
  const { token, user } = useIdentity()
  return useQuery({
    queryKey: ['hub', user.id, path],
    queryFn: () => getJson<T>(path, token),
    enabled: enabled && token !== undefined,
  })
}

/** 治理中心的写操作：成功后刷新整个 hub 缓存，各分区的数据互相关联（升级会产生回归、审核会改清单） */
export function useHubAction<T, V = void>(
  method: 'POST' | 'DELETE',
  path: (variables: V) => string,
  body?: (variables: V) => unknown,
) {
  const { token } = useIdentity()
  const client = useQueryClient()
  return useMutation({
    mutationFn: (variables: V) =>
      method === 'DELETE'
        ? deleteJson<T>(path(variables), token)
        : postJson<T>(path(variables), token, body?.(variables) ?? {}),
    onSuccess: () => client.invalidateQueries({ queryKey: ['hub'] }),
  })
}

/** 只有租户管理员（以及平台运维）能做治理中心的写操作，开发者只读 */
export function useHubRole(): { canWrite: boolean; canRead: boolean } {
  const { user } = useIdentity()
  const role = user.role
  const canWrite = role.includes('租户管理员') || role.includes('平台运维')
  const canRead = canWrite || role.includes('租户开发者')
  return { canWrite, canRead }
}

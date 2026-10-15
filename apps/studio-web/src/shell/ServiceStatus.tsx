import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { cn } from '@eab/ui'

interface ServiceProbe {
  key: string
  label: string
  url: string
}

const SERVICES: readonly ServiceProbe[] = [
  { key: 'platform', label: '平台服务', url: '/api/health' },
  { key: 'demo-erp', label: 'DemoERP', url: '/erp/health' },
]

const POLL_MS = 10_000
/** 服务刚启动或暂时不可用时更快地重试，状态能尽快恢复 */
const RETRY_POLL_MS = 2_000

async function probe(url: string): Promise<{ version: string }> {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  return (await response.json()) as { version: string }
}

function ServiceDot({ service }: { service: ServiceProbe }): ReactNode {
  const { data, isError, isPending } = useQuery({
    queryKey: ['health', service.key],
    queryFn: () => probe(service.url),
    refetchInterval: (query) => (query.state.status === 'error' ? RETRY_POLL_MS : POLL_MS),
    retry: false,
  })
  const state = isPending ? 'pending' : isError ? 'down' : 'up'
  return (
    <span
      className="flex items-center gap-1.5"
      data-testid={`health-${service.key}`}
      data-state={state}
    >
      <span
        aria-hidden
        className={cn(
          'size-1.5 rounded-full',
          state === 'up' && 'bg-allow',
          state === 'down' && 'bg-deny',
          state === 'pending' && 'bg-ink-faint',
        )}
      />
      {service.label}
      <span className="text-ink-faint">
        {state === 'up' ? `v${data?.version ?? '?'}` : state === 'down' ? '不可用' : '检查中'}
      </span>
    </span>
  )
}

export function ServiceStatus(): ReactNode {
  return (
    <div className="flex items-center gap-4" role="status" aria-live="polite" aria-label="服务状态">
      {SERVICES.map((s) => (
        <ServiceDot key={s.key} service={s} />
      ))}
    </div>
  )
}

#!/usr/bin/env node
// 一条命令启动演示环境：并行拉起各服务，等健康检查全部通过后打印访问地址。
//   pnpm demo        正常启动：Java 后端（平台、DemoERP，jar 比源码旧时先打包）和前端
//   pnpm dev         同上；前端由 Vite 热更新，后端改动后重新运行即可
// 后续阶段新增的服务（外部 Agent、MCP Server）在 SERVICES 里追加即可；
// optional 为 true 的服务启动失败不会拖垮整个演示环境。
import { execFileSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { join } from 'node:path'
import { concurrently } from 'concurrently'
import { ensureJars, jarOf } from './java-cli.mjs'

const HOST = process.env.EAB_HOST ?? '127.0.0.1'
const HEALTH_TIMEOUT_MS = 90_000
const POLL_MS = 500
const READY_BANNER = 'ERP Agent Builder 演示环境已启动'
// 平台与 DemoERP 之间的内部调用密钥（事件推送签名、委托核实），每次启动随机生成，不落盘
const INTERNAL_SECRET = process.env.EAB_INTERNAL_SECRET ?? randomBytes(24).toString('base64url')
const PLATFORM_URL = 'http://127.0.0.1:4000'
const DEMO_ERP_URL = 'http://127.0.0.1:4100'

/** 用 java -jar 启动后端服务；JAVA_HOME 优先，否则用 PATH 上的 java */
function javaCommand(module) {
  const home = process.env.JAVA_HOME
  const java = home === undefined || home === '' ? 'java' : join(home, 'bin', 'java')
  // 显式指定 UTF-8 输出，Windows 中文控制台默认是 GBK，日志里的中文会变成乱码
  return `"${java}" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "${jarOf(module)}"`
}

/** uv 可能装在 PATH 上，也可能只作为 Python 模块安装（pip install --user uv）；都没有时返回 undefined */
function findUv() {
  for (const candidate of [['uv'], ['python', '-m', 'uv'], ['python3', '-m', 'uv']]) {
    try {
      execFileSync(candidate[0], [...candidate.slice(1), '--version'], { stdio: 'ignore' })
      return candidate.join(' ')
    } catch {
      // 换下一个候选
    }
  }
  return undefined
}

/** 供应商风险舆情 Agent（Python）：先 uv sync 装好依赖；没有 uv 或安装失败时跳过，统一入口对它给出降级说明 */
function supplierRiskCommand() {
  const uv = findUv()
  if (uv === undefined) {
    console.warn('[demo] 没有找到 uv，跳过供应商风险舆情 Agent；安装方法：pip install --user uv')
    return undefined
  }
  try {
    execFileSync(uv.split(' ')[0], [...uv.split(' ').slice(1), 'sync', '--frozen', '--quiet'], {
      cwd: SUPPLIER_RISK_DIR,
      stdio: 'inherit',
    })
  } catch {
    console.warn('[demo] 供应商风险舆情 Agent 的依赖安装失败，跳过它')
    return undefined
  }
  return `node scripts/optional-service.mjs "cd external-agents/supplier-risk-py && ${uv} run --frozen python -m supplier_risk.app"`
}

const SUPPLIER_RISK_DIR = join('external-agents', 'supplier-risk-py')

/** 外部 Agent 与 MCP Server（P7）：都是可选服务，启动失败不影响主流程 */
function externalServices() {
  const services = []
  const risk = supplierRiskCommand()
  if (risk !== undefined) {
    services.push({
      name: 'risk-py',
      color: 'yellow',
      command: risk,
      env: { PORT: '4200', PUBLIC_URL: 'http://127.0.0.1:4200', EAB_PLATFORM_URL: PLATFORM_URL },
      healthUrl: 'http://127.0.0.1:4200/.well-known/agent-card.json',
      optional: true,
    })
  }
  services.push(
    {
      name: 'fx-ts',
      color: 'blue',
      command: 'node scripts/optional-service.mjs pnpm --filter @eab/fx-rate-ts run start',
      env: { PORT: '4210', PUBLIC_URL: 'http://127.0.0.1:4210', EAB_PLATFORM_URL: PLATFORM_URL },
      healthUrl: 'http://127.0.0.1:4210/.well-known/agent-card.json',
      optional: true,
    },
    {
      name: 'mcp',
      color: 'gray',
      command: 'node scripts/optional-service.mjs pnpm --filter @eab/logistics-mcp run start',
      env: { PORT: '4400' },
      healthUrl: 'http://127.0.0.1:4400/health',
      optional: true,
    },
  )
  return services
}

/**
 * 端口通过各服务专用的环境变量显式下发，避免继承开发者 shell 里的通用 PORT。
 * @type {Array<{name: string, color: string, command: string, env: Record<string, string>, healthUrl: string, url?: string, optional?: boolean}>}
 */
const SERVICES = [
  {
    name: 'platform',
    color: 'magenta',
    command: javaCommand('platform'),
    env: {
      EAB_PLATFORM_PORT: '4000',
      EAB_INTERNAL_SECRET: INTERNAL_SECRET,
      EAB_DEMO_MODE: '1',
      EAB_DEMO_ERP_URL: DEMO_ERP_URL,
      // 端到端测试与验收脚本和所有演示页面共用 127.0.0.1 一个来源地址，登录限流给足余量（生产默认 30）
      EAB_LOGIN_RATE_PER_MINUTE: '240',
    },
    healthUrl: 'http://127.0.0.1:4000/health',
  },
  {
    name: 'demo-erp',
    color: 'cyan',
    command: javaCommand('demo-erp'),
    env: {
      EAB_DEMO_ERP_PORT: '4100',
      EAB_INTERNAL_SECRET: INTERNAL_SECRET,
      EAB_PLATFORM_URL: PLATFORM_URL,
    },
    healthUrl: 'http://127.0.0.1:4100/health',
  },
  {
    name: 'web',
    color: 'green',
    command: 'pnpm --filter @eab/studio-web run start',
    env: {
      EAB_PLATFORM_URL: PLATFORM_URL,
      EAB_DEMO_ERP_URL: DEMO_ERP_URL,
    },
    healthUrl: 'http://127.0.0.1:5173/',
    url: 'http://localhost:5173',
  },
  ...externalServices(),
]

async function isHealthy(url) {
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(2_000) })
    return response.ok
  } catch {
    return false
  }
}

/** 轮询直到健康、超时，或者进程组已经退出（stopped() 返回 true）。 */
async function waitForHealthy(service, stopped) {
  const deadline = Date.now() + HEALTH_TIMEOUT_MS
  while (Date.now() < deadline && !stopped()) {
    if (await isHealthy(service.healthUrl)) return true
    await new Promise((resolve) => setTimeout(resolve, POLL_MS))
  }
  return false
}

ensureJars(['platform', 'demo-erp'])

const { result, commands } = concurrently(
  SERVICES.map((s) => ({
    name: s.name,
    command: s.command,
    prefixColor: s.color,
    env: { EAB_HOST: HOST, ...s.env },
  })),
  { prefix: 'name', killOthersOn: ['failure'], restartTries: 0 },
)

// 立即挂上处理，避免健康检查期间某个服务退出导致未处理的 rejection
let exited = false
const settled = result.then(
  () => 0,
  () => 1,
)
void settled.then(() => {
  exited = true
})

async function stopAll(code) {
  for (const command of commands) command.kill()
  await settled
  process.exit(code)
}

const checks = await Promise.all(
  SERVICES.map(async (s) => ({ service: s, ok: await waitForHealthy(s, () => exited) })),
)

if (exited) {
  console.error('\n[demo] 有服务在启动过程中退出，演示环境已停止，详见上方日志')
  process.exit(await settled)
}

const failedRequired = checks.filter((c) => !c.ok && !c.service.optional)
if (failedRequired.length > 0) {
  console.error(
    `\n[demo] 以下服务在 ${HEALTH_TIMEOUT_MS / 1000} 秒内没有通过健康检查：${failedRequired
      .map((c) => c.service.name)
      .join('、')}`,
  )
  await stopAll(1)
}

const lines = checks.map(({ service, ok }) => {
  const status = ok ? '就绪' : '未就绪（可选服务，不影响主流程）'
  return `  ${service.name.padEnd(10)} ${status.padEnd(6)} ${service.url ?? service.healthUrl}`
})
console.info(
  [
    '',
    `──────── ${READY_BANNER} ────────`,
    ...lines,
    '',
    '  浏览器打开 http://localhost:5173 ，按 Ctrl+C 停止全部服务',
    '─────────────────────────────────────────────────',
    '',
  ].join('\n'),
)

// Ctrl+C 时 concurrently 会把信号转发给各服务并以 0 结束；某个服务异常退出时以 1 结束
process.exitCode = await settled

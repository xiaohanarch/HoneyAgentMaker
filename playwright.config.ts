import { defineConfig, devices } from '@playwright/test'

const WEB_URL = process.env.EAB_WEB_URL ?? 'http://127.0.0.1:5173'

// 端到端测试依赖完整的演示环境。默认由 Playwright 启动 `pnpm demo`；
// 如果演示环境已经在运行，会直接复用。
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  // spec 之间存在状态依赖（重置、委托撤销、语义包写入按文件顺序衔接），文件之间也必须串行，
  // 不能让默认的多 worker 按文件并行互踩
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: WEB_URL,
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
  ],
  webServer: {
    command: 'pnpm demo',
    // demo.mjs 在全部服务健康后才打印就绪横幅，以它为准，而不是只等前端端口
    wait: { stdout: /演示环境已启动/ },
    url: WEB_URL,
    reuseExistingServer: true,
    timeout: 120_000,
  },
})

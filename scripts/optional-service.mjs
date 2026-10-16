#!/usr/bin/env node
// 可选服务的包装：外部 Agent、MCP Server 这类服务启动失败或中途退出时只打印提示，不让 concurrently 停掉整个演示环境。
//   node scripts/optional-service.mjs <命令>
// 子进程退出后本进程保持运行，直到收到 Ctrl+C；统一入口调用不到它时会给出降级说明。
import { spawn } from 'node:child_process'

const command = process.argv.slice(2).join(' ')
const child = spawn(command, { shell: true, stdio: 'inherit' })

const stop = () => {
  child.kill()
  process.exit(0)
}
process.on('SIGINT', stop)
process.on('SIGTERM', stop)

child.on('exit', (code) => {
  console.warn(
    `[optional] 可选服务已退出（退出码 ${code ?? '无'}），演示环境照常运行，相关能力会显示降级说明`,
  )
  setInterval(() => {}, 1 << 30)
})

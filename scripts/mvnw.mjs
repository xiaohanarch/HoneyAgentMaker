#!/usr/bin/env node
// 在 backend/ 下调用 Maven Wrapper，屏蔽 Windows 与 Unix 的差异。
//   node scripts/mvnw.mjs -q -B test
// 也被 scripts/demo.mjs 引用，用于启动 Java 后端前打包。
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const BACKEND_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', 'backend')

export function runMaven(args) {
  const windows = process.platform === 'win32'
  const mvnw = windows ? '.\\mvnw.cmd' : './mvnw'
  if (!existsSync(join(BACKEND_DIR, mvnw))) throw new Error(`找不到 backend/${mvnw}`)
  // Windows 上 .cmd 不能直接 execFile，经 cmd.exe 调用；参数逐个传入，不拼接成命令行字符串
  const [command, commandArgs] = windows ? ['cmd.exe', ['/d', '/c', mvnw, ...args]] : [mvnw, args]
  execFileSync(command, commandArgs, { cwd: BACKEND_DIR, stdio: 'inherit' })
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    runMaven(process.argv.slice(2))
  } catch (error) {
    process.exit(typeof error.status === 'number' ? error.status : 1)
  }
}

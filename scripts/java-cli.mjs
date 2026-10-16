#!/usr/bin/env node
// pnpm 命令到 Java 命令行工具的映射：assets:validate、assets:assemble、seed:reset、data:backup、tools:import-bo、agent:run、eval:smoke、data:simulate。
//   node scripts/java-cli.mjs assets:validate
// jar 比 backend/ 下的源码旧时先用 Maven Wrapper 打包，再用 Spring Boot 的 PropertiesLauncher 运行指定的主类。
import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { runMaven } from './mvnw.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const BACKEND = join(ROOT, 'backend')

/** 每个命令依次运行的 [模块, 主类] */
const COMMANDS = {
  'assets:validate': [['platform', 'dev.eab.platform.cli.ValidateAssets']],
  'assets:assemble': [['platform', 'dev.eab.platform.cli.AssembleAgent']],
  'data:backup': [['platform', 'dev.eab.platform.cli.BackupData']],
  'tools:import-bo': [['platform', 'dev.eab.platform.cli.ImportBoTools']],
  'agent:run': [['eab-cli', 'dev.eab.cli.AgentRun']],
  'eval:smoke': [['eab-cli', 'dev.eab.cli.EvalSmoke']],
  'data:simulate': [['platform', 'dev.eab.platform.cli.SimulateHistory']],
  'seed:reset': [
    ['demo-erp', 'dev.eab.erp.cli.ResetErp'],
    ['platform', 'dev.eab.platform.cli.ResetDemo'],
  ],
}

export const jarOf = (module) => join(BACKEND, module, 'target', `${module}-exec.jar`)

/** backend/ 下源码与 pom 的最新修改时间，跳过 target 和隐藏目录 */
function newestSource(dir) {
  let newest = 0
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'target' || entry.name.startsWith('.')) continue
    const path = join(dir, entry.name)
    if (entry.isDirectory()) newest = Math.max(newest, newestSource(path))
    else if (entry.name === 'pom.xml' || path.includes(`${join('src', 'main')}`))
      newest = Math.max(newest, statSync(path).mtimeMs)
  }
  return newest
}

/** jar 不存在或比源码旧时重新打包；输出走 stderr，stdout 只留给命令本身（ecs.sh 读取 data:backup 的最后一行） */
export function ensureJars(modules) {
  const stale = modules.some((m) => !existsSync(jarOf(m)))
  const newest = stale ? Infinity : newestSource(BACKEND)
  if (stale || modules.some((m) => statSync(jarOf(m)).mtimeMs < newest)) {
    console.error('[java-cli] 打包 Java 后端（mvnw package -DskipTests）')
    runMaven(['-q', '-B', 'package', '-DskipTests'], { toStderr: true })
  }
}

function javaBinary() {
  const home = process.env.JAVA_HOME
  return home === undefined || home === '' ? 'java' : join(home, 'bin', 'java')
}

function main() {
  const [command, ...args] = process.argv.slice(2)
  const steps = COMMANDS[command]
  if (steps === undefined) {
    console.error(`[java-cli] 未知命令 ${command}，可用：${Object.keys(COMMANDS).join('、')}`)
    return 2
  }
  ensureJars([...new Set(steps.map(([module]) => module))])
  for (const [module, mainClass] of steps) {
    try {
      execFileSync(
        javaBinary(),
        [
          '-Dstdout.encoding=UTF-8',
          '-Dstderr.encoding=UTF-8',
          `-Dloader.main=${mainClass}`,
          '-cp',
          jarOf(module),
          'org.springframework.boot.loader.launch.PropertiesLauncher',
          ...args,
        ],
        // Windows 上 Java 用 ANSI 代码页解码命令行参数，中文会变成乱码；参数同时经环境变量（UTF-16）传一份
        {
          cwd: ROOT,
          stdio: 'inherit',
          env: { ...process.env, EAB_CLI_ARGS: JSON.stringify(args) },
        },
      )
    } catch (error) {
      if (typeof error.status === 'number') return error.status
      console.error(`[java-cli] 无法运行 java：${error.message}`)
      return 1
    }
  }
  return 0
}

if (process.argv[1] !== undefined && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main()
}

/**
 * pnpm data:backup
 * 对运行数据做一致性快照：用 SQLite 在线备份接口复制 platform.db（服务运行时也安全），
 * 连同签名密钥打包成 data/backups/eab-data-<时间>.tar.gz，只保留最近 10 份。
 * 演示主机是抢占式实例，快照由本地的 scripts/ecs.sh backup 拉回保存。
 */
import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { join, resolve } from 'node:path'
import Database from 'better-sqlite3'
import { REPO_ROOT } from './repo-registry'

const KEEP = 10

async function main(): Promise<number> {
  const dataDir = resolve(process.env.EAB_DATA_DIR ?? join(REPO_ROOT, 'data'))
  const backupsDir = join(dataDir, 'backups')
  const stamp = new Date().toISOString().replace(/[:.]/g, '-')
  const workDir = join(backupsDir, `eab-data-${stamp}`)
  mkdirSync(workDir, { recursive: true })

  const dbPath = join(dataDir, 'platform.db')
  if (existsSync(dbPath)) {
    const db = new Database(dbPath, { readonly: true })
    await db.backup(join(workDir, 'platform.db'))
    db.close()
  }
  const keysDir = join(dataDir, 'keys')
  if (existsSync(keysDir)) cpSync(keysDir, join(workDir, 'keys'), { recursive: true })

  const archive = `${workDir}.tar.gz`
  execFileSync('tar', ['-czf', archive, '-C', backupsDir, `eab-data-${stamp}`])
  rmSync(workDir, { recursive: true, force: true })

  const archives = readdirSync(backupsDir)
    .filter((f) => f.startsWith('eab-data-') && f.endsWith('.tar.gz'))
    .sort()
  for (const old of archives.slice(0, Math.max(0, archives.length - KEEP))) {
    rmSync(join(backupsDir, old))
  }
  console.info(archive)
  return 0
}

process.exitCode = await main()

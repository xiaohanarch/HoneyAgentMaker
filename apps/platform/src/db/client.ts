import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import Database from 'better-sqlite3'
import { drizzle, type BetterSQLite3Database } from 'drizzle-orm/better-sqlite3'
import { migrate } from 'drizzle-orm/better-sqlite3/migrator'
import * as schema from './schema'

export type PlatformDb = BetterSQLite3Database<typeof schema> & { $client: Database.Database }

const MIGRATIONS_FOLDER = resolve(dirname(fileURLToPath(import.meta.url)), '../../drizzle')

/**
 * 打开平台数据库并执行迁移。path 为 ':memory:' 时用于测试。
 * 数据访问只经过仓储接口，换 PostgreSQL 时只需替换仓储实现。
 */
export function openPlatformDb(path: string): PlatformDb {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true })
  const sqlite = new Database(path)
  sqlite.pragma('journal_mode = WAL')
  sqlite.pragma('foreign_keys = ON')
  const db = drizzle(sqlite, { schema })
  migrate(db, { migrationsFolder: MIGRATIONS_FOLDER })
  return db
}

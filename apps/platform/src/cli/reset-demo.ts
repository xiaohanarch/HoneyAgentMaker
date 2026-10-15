/**
 * pnpm seed:reset
 * 把演示数据重置到初始状态：DemoERP 按今天重新生成业务数据；平台清空审计、业务事件、委托、租户设置，
 * 并重新预置演示委托。资产与密钥保留（标品资产仍然有效，签名不变）。服务运行中也可以执行。
 */
import { join, resolve } from 'node:path'
import { openErpDb } from '@eab/demo-erp/db'
import { seedErp } from '@eab/demo-erp/seed'
import { openPlatformDb } from '../db/client'
import { auditEvents, businessEvents, delegations, tenantSettings } from '../db/schema'
import { DelegationStore } from '../identity/delegations'
import { ensureDemoDefaults } from '../services'
import { REPO_ROOT } from './repo-registry'

const dataDir = resolve(process.env.EAB_DATA_DIR ?? join(REPO_ROOT, 'data'))

const erp = openErpDb(join(dataDir, 'erp.db'))
seedErp(erp)
erp.$client.close()

const platform = openPlatformDb(join(dataDir, 'platform.db'))
platform.transaction((tx) => {
  for (const table of [auditEvents, businessEvents, delegations, tenantSettings])
    tx.delete(table).run()
})
ensureDemoDefaults({ delegations: new DelegationStore(platform) })
platform.$client.close()

console.info(`演示数据已重置：${dataDir}`)

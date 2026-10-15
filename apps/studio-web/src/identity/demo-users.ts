/**
 * 演示用户的展示信息。用户清单来自平台的 GET /identity/v1/users（单一来源是 backend/eab-directory 的 DemoDirectory），
 * 前端不再写死用户。
 */
export type Workspace = 'assistant' | 'studio' | 'hub'

export interface DemoUser {
  id: string
  name: string
  tenantId: string
  tenantName: string
  role: string
  dataScope: string
  /** 切换到该用户时默认进入的入口 */
  home: Workspace
}

export const DEFAULT_USER_ID = 'zhangmin'

/** 平台不可用时的占位身份，界面仍能渲染 */
export const OFFLINE_USER: DemoUser = {
  id: DEFAULT_USER_ID,
  name: '张敏',
  tenantId: 't001',
  tenantName: '星海制造',
  role: '采购经理',
  dataScope: '采购组织：华东',
  home: 'assistant',
}

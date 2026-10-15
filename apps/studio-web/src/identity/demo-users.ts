/**
 * 演示用户（主提示词 6.2 节），人名与公司均为虚构。
 * P0 先写死在前端，用于身份切换；P2 起改为从平台的模拟身份服务读取。
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

export const DEMO_USERS: readonly DemoUser[] = [
  {
    id: 'zhangmin',
    name: '张敏',
    tenantId: 't001',
    tenantName: '星海制造',
    role: '采购经理',
    dataScope: '采购组织：华东',
    home: 'assistant',
  },
  {
    id: 'liqiang',
    name: '李强',
    tenantId: 't001',
    tenantName: '星海制造',
    role: '采购员',
    dataScope: '采购组织：华南',
    home: 'assistant',
  },
  {
    id: 'wangfang',
    name: '王芳',
    tenantId: 't001',
    tenantName: '星海制造',
    role: '财务经理',
    dataScope: '公司主体：星海制造本部',
    home: 'assistant',
  },
  {
    id: 'chengong',
    name: '陈工',
    tenantId: 't001',
    tenantName: '星海制造',
    role: '租户开发者',
    dataScope: '无业务数据权限，有 Studio 权限',
    home: 'studio',
  },
  {
    id: 'zhaoadmin',
    name: '赵管理员',
    tenantId: 't001',
    tenantName: '星海制造',
    role: '租户管理员',
    dataScope: '管理 Agent 授权与委托',
    home: 'hub',
  },
  {
    id: 'vendordev',
    name: '厂商研发',
    tenantId: 'vendor',
    tenantName: '厂商',
    role: '标品开发者',
    dataScope: '无任何租户业务数据',
    home: 'studio',
  },
]

export const DEFAULT_USER_ID = 'zhangmin'

export function findDemoUser(id: string | null | undefined): DemoUser {
  return DEMO_USERS.find((u) => u.id === id) ?? (DEMO_USERS[0] as DemoUser)
}

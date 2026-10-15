/** 种子数据用的虚构名称词库，不对应任何真实企业 */

export const SUPPLIER_PREFIX_T001 = [
  '华',
  '中',
  '恒',
  '瑞',
  '鑫',
  '宏',
  '晨',
  '联',
  '盛',
  '泰',
  '嘉',
  '通',
  '宇',
  '金',
  '科',
  '启',
]
export const SUPPLIER_PREFIX_T002 = [
  '航',
  '星',
  '远',
  '达',
  '海',
  '云',
  '新',
  '博',
  '天',
  '昊',
  '凌',
  '锐',
  '弘',
  '晟',
  '聚',
  '恒',
]
export const SUPPLIER_MIDDLE = [
  '达',
  '泰',
  '源',
  '丰',
  '辉',
  '邦',
  '诚',
  '德',
  '信',
  '利',
  '安',
  '和',
]
export const SUPPLIER_SUFFIX = [
  '精密',
  '电子',
  '机械',
  '材料',
  '五金',
  '塑胶',
  '科技',
  '包装',
  '化工',
  '模具',
]

export const CITIES_EAST = ['苏州', '无锡', '宁波', '常州', '嘉兴', '南通', '昆山', '绍兴']
export const CITIES_SOUTH = ['东莞', '佛山', '中山', '惠州', '江门', '珠海']
export const CITIES_NORTH = ['天津', '廊坊', '保定', '唐山']

export const MATERIAL_TYPES = [
  '轴承',
  '齿轮',
  '电机',
  '传感器',
  '印制电路板',
  '连接器',
  '外壳',
  '螺栓',
  '密封圈',
  '线束',
]
export const MATERIAL_SPECS = [
  'A型',
  'B型',
  'C型',
  '精密级',
  '标准级',
  '加强型',
  '微型',
  '大功率',
  '耐高温',
  '防水型',
]
export const UNITS = ['件', '个', '套', '米', '千克']

export const PAYMENT_TERMS = ['月结 30 天', '月结 60 天', '货到付款', '预付 30%，验收后付清']
export const PRICE_TERMS = ['年度框架价，季度调价', '一单一议', '阶梯价：月采购量超过 5000 件降 3%']

export const ANOMALY_TYPES = [
  { type: 'duplicate_payment', label: '重复付款' },
  { type: 'split_invoice', label: '拆分发票' },
  { type: 'round_amount', label: '整数金额' },
  { type: 'off_hours', label: '非工作时间过账' },
  { type: 'reversal', label: '冲销' },
] as const

export const CLOSE_CHECKS_ASSET_ACCOUNTING = [
  { name: '本期折旧计提', owner: '资产会计', fail: '两张资产卡片缺少折旧方法，计提未完成' },
  {
    name: '在建工程转固确认',
    owner: '项目会计',
    fail: '二号厂房改造项目验收单未上传，无法确认转固',
  },
  { name: '资产减值测试', owner: '财务经理', fail: '三台闲置设备缺少评估报告' },
  { name: '资产卡片与总账对账', owner: '资产会计', fail: null },
  { name: '本期新增资产入账', owner: '资产会计', fail: null },
  { name: '资产处置损益结转', owner: '总账会计', fail: null },
  { name: '租赁资产使用权摊销', owner: '总账会计', fail: null },
  { name: '资产盘点差异处理', owner: '资产管理员', fail: null },
] as const

/**
 * 演示控制台调用的接口（主提示词 17 节，Studio 右上角隐藏菜单）。
 * 演示状态三个端点只对租户管理员开放（服务端硬校验，前端只做可用性提示）；
 * 触发事件与升级回归沿用既有的 /demo/v1/events 与 /hub/demo/upgrade。
 */
import { getJson, postJson } from '../assist/api'
import type { IntegrationCompare, Regression } from '../hub/types'

/** POST /demo/v1/reset：清空运行、追踪、通知、委托等运行期数据并重建演示委托 */
export interface ResetResult {
  erpReset: boolean
  delegation: string
}

/** POST /demo/v1/story-start：A–E 纯重置；F 重置后发布完成态快照 */
export interface StoryStartResult {
  story: string
  published: string[]
}

/** POST /demo/v1/complete：完成态快照 + 30 天模拟历史 */
export interface CompleteResult {
  published: string[]
  history: { runs: number; metricPoints: number }
}

export function resetDemo(token: string | undefined): Promise<ResetResult> {
  return postJson('/demo/v1/reset', token, {})
}

export function storyStart(token: string | undefined, story: string): Promise<StoryStartResult> {
  return postJson('/demo/v1/story-start', token, { story })
}

export function completeDemo(token: string | undefined): Promise<CompleteResult> {
  return postJson('/demo/v1/complete', token, {})
}

/** 触发一条业务事件：DemoERP 生成载荷后经 webhook 推给平台，匹配的 Agent 以受托方式运行 */
export function triggerEvent(token: string | undefined, type: string): Promise<unknown> {
  return postJson('/demo/v1/events', token, { type })
}

/** 模拟发布标品新版本，跑各租户扩展的评测集回归 */
export function upgradeVendor(token: string | undefined): Promise<Regression[]> {
  return postJson('/hub/demo/upgrade', token, {})
}

/** 同一问题经 BO API（用户身份）与 Open API（集成账号）的数据范围对照 */
export function fetchCompare(
  token: string | undefined,
  userId: string,
): Promise<IntegrationCompare> {
  return getJson(`/hub/demo/integration-compare?user=${userId}`, token)
}

import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js'

/** 平台地址，与 main.ts 的默认值一致 */
const PLATFORM_URL = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'

/** 回调平台失败：调用方应把原因告诉用户，不能编造平台数据 */
export class CallbackError extends Error {}

interface RestrictedToken {
  accessToken: string
  expiresIn: number
  scope: string[]
}

/**
 * 用平台出站调用携带的服务令牌换受限只读回调令牌（RFC 8693 令牌交换）。
 * 换到的令牌受众是 eab-platform，只含发起用户的只读（L0）操作，主体是发起用户而不是本 Agent；
 * 它不能再换写操作，也不能再次交换。没有用户身份、用户没有只读操作时平台直接拒绝。
 */
async function exchangeRestricted(
  serviceToken: string,
  scope?: string[],
): Promise<RestrictedToken> {
  const response = await fetch(new URL('/identity/v1/token', PLATFORM_URL), {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      grant_type: 'urn:ietf:params:oauth:grant-type:token-exchange',
      subject_token: serviceToken,
      subject_token_type: 'urn:eab:params:oauth:token-type:service',
      audience: 'eab-platform',
      ...(scope === undefined ? {} : { scope: scope.join(' ') }),
    }),
  })
  const body = (await response.json().catch(() => ({}))) as Record<string, unknown>
  if (!response.ok) {
    throw new CallbackError(
      `换受限回调令牌被拒绝（HTTP ${response.status}）：${String(body.error_description ?? body.error ?? '未知原因')}`,
    )
  }
  return {
    accessToken: String(body.access_token ?? ''),
    expiresIn: Number(body.expires_in ?? 0),
    scope: String(body.scope ?? '')
      .split(' ')
      .filter((s) => s !== ''),
  }
}

export interface PlatformToolCall {
  /** 平台出站调用的 Authorization 头里去掉 Bearer 前缀后的服务令牌 */
  serviceToken: string
  /** 平台 MCP 工具名（BO 操作名，点换成下划线，例如 purchase_order_list） */
  tool: string
  args: Record<string, unknown>
  /** 收窄回调范围（必须是用户自己的只读操作）；不传则用该用户的全部只读操作 */
  scope?: string[]
}

/** 平台拒绝时的内容有两种形态：工具网关的 {"error":{"code","reason"}} 与端点的纯文本，都取出原因 */
function reasonOf(text: string): string {
  try {
    const parsed = JSON.parse(text) as { error?: { reason?: unknown } | string }
    if (typeof parsed.error === 'string') return parsed.error
    if (
      parsed.error !== null &&
      typeof parsed.error === 'object' &&
      typeof parsed.error.reason === 'string'
    ) {
      return parsed.error.reason
    }
  } catch {
    // 纯文本错误，直接用原文
  }
  return text
}

/**
 * 以受限只读令牌调用平台的 MCP 端点（/mcp）查数据：走官方 SDK 的 Streamable HTTP 客户端，
 * 与 Agent 的工具调用过同一条工具网关（鉴权、审计、数据范围）。返回工具结果的 JSON；
 * 任何一步失败都抛 CallbackError，不降级、不编造。
 */
export async function callPlatformTool(call: PlatformToolCall): Promise<Record<string, unknown>> {
  const restricted = await exchangeRestricted(call.serviceToken, call.scope)
  const transport = new StreamableHTTPClientTransport(new URL('/mcp', PLATFORM_URL), {
    requestInit: { headers: { authorization: `Bearer ${restricted.accessToken}` } },
  })
  const client = new Client({ name: '{{name}}', version: '0.1.0' })
  try {
    await client.connect(transport)
    const result = await client.callTool({ name: call.tool, arguments: call.args })
    const text = (result.content as Array<{ type: string; text?: string }>)
      .filter((part) => part.type === 'text')
      .map((part) => part.text ?? '')
      .join('')
    if (result.isError === true) throw new CallbackError(reasonOf(text))
    try {
      return JSON.parse(text) as Record<string, unknown>
    } catch {
      throw new CallbackError(`平台工具 ${call.tool} 返回了无法解析的结果：${text.slice(0, 200)}`)
    }
  } catch (error) {
    if (error instanceof CallbackError) throw error
    throw new CallbackError(`调用平台工具 ${call.tool} 失败：${(error as Error).message}`)
  } finally {
    await client.close().catch(() => {})
  }
}

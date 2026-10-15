/**
 * @eab/spec：资产 schema（zod）、YAML 解析与校验，前后端、CLI、Studio MCP 共用的唯一来源。
 * 需求见 docs/brief/02-主提示词.md 第 5 节。
 */
export * from './layers'
export * from './refs'
export * from './common'
export * from './assets'
export * from './parse'
export * from './kinds/agent'
export * from './kinds/agent-parts'
export * from './kinds/agent-extension'
export * from './kinds/catalog'
export * from './kinds/lifecycle'
export * from './kinds/semantic-pack'
export * from './kinds/steps'
export * from './kinds/tool-source'
export * from './visibility'
export * from './resolver'
export * from './builtins'
export * from './references'
export * from './lint'
export * from './json-schema'

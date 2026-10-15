import { z } from 'zod'
import { ASSET_KINDS, KIND_SCHEMAS, type AssetKind } from './assets'

/**
 * 从 zod schema 生成每个 kind 的 JSON Schema（描述作者写的 YAML，即输入形态）。
 * 生成物提交在 packages/spec/json-schema/，供编辑器做 YAML 校验和补全；
 * 由 `pnpm spec:json-schema` 重新生成，测试会检查它与 schema 同步。
 */
export function kindJsonSchema(kind: AssetKind): Record<string, unknown> {
  return {
    $id: `https://eab.dev/schemas/v1/${kind}.json`,
    title: kind,
    ...z.toJSONSchema(KIND_SCHEMAS[kind], { io: 'input', unrepresentable: 'any' }),
  }
}

export function allJsonSchemas(): Record<AssetKind, Record<string, unknown>> {
  return Object.fromEntries(ASSET_KINDS.map((kind) => [kind, kindJsonSchema(kind)])) as Record<
    AssetKind,
    Record<string, unknown>
  >
}

export function jsonSchemaFileName(kind: AssetKind): string {
  return `${kind}.schema.json`
}

export function serializeJsonSchema(schema: Record<string, unknown>): string {
  return `${JSON.stringify(schema, null, 2)}\n`
}

import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ASSET_KINDS } from './assets'
import { jsonSchemaFileName, kindJsonSchema, serializeJsonSchema } from './json-schema'

const DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../json-schema')

describe('JSON Schema 生成物', () => {
  it.each(ASSET_KINDS)(
    '%s 的生成物与 zod schema 同步（不同步时运行 pnpm spec:json-schema）',
    (kind) => {
      const committed = readFileSync(join(DIR, jsonSchemaFileName(kind)), 'utf8')
      expect(committed).toBe(serializeJsonSchema(kindJsonSchema(kind)))
    },
  )

  it('Agent 的 schema 要求 spec 必填字段', () => {
    const schema = kindJsonSchema('Agent') as {
      properties: { spec: { required: string[] } }
    }
    expect(schema.properties.spec.required).toEqual(
      expect.arrayContaining(['expertise', 'instructions', 'model', 'writePolicy', 'routing']),
    )
  })
})

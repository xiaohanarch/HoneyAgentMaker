/** 生成 packages/spec/json-schema/*.schema.json：pnpm spec:json-schema */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { allJsonSchemas, jsonSchemaFileName, serializeJsonSchema } from '../src/json-schema'
import type { AssetKind } from '../src/assets'

const outDir = resolve(dirname(fileURLToPath(import.meta.url)), '../json-schema')
mkdirSync(outDir, { recursive: true })
for (const [kind, schema] of Object.entries(allJsonSchemas())) {
  writeFileSync(join(outDir, jsonSchemaFileName(kind as AssetKind)), serializeJsonSchema(schema))
}
console.info(`已生成 ${Object.keys(allJsonSchemas()).length} 个 JSON Schema 到 ${outDir}`)

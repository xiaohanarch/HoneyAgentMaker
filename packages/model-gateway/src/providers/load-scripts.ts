import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { parse } from 'yaml'
import { ScriptFileSchema, type Script } from './scripted'

export class ScriptLoadError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ScriptLoadError'
  }
}

/** 加载 demo/scripts 下全部剧本文件，按文件名排序；任何一个不合法都报错，并指出文件和字段 */
export function loadScripts(dir: string): Script[] {
  const files = readdirSync(dir)
    .filter((f) => f.endsWith('.yaml') || f.endsWith('.yml'))
    .sort()
  const scripts = files.flatMap((file) => {
    const parsed = ScriptFileSchema.safeParse(parse(readFileSync(join(dir, file), 'utf8')))
    if (!parsed.success) {
      const detail = parsed.error.issues.map((i) => `${i.path.join('.')}：${i.message}`).join('；')
      throw new ScriptLoadError(`剧本文件 ${file} 不合法：${detail}`)
    }
    return parsed.data.scripts
  })
  const seen = new Set<string>()
  for (const script of scripts) {
    if (seen.has(script.id)) throw new ScriptLoadError(`剧本编号 ${script.id} 重复`)
    seen.add(script.id)
  }
  return scripts
}

import { platformVerifier, remoteJwks } from './auth.js'
import { createApp } from './app.js'

const port = Number(process.env.PORT ?? 4310)
const publicUrl = process.env.PUBLIC_URL ?? `http://127.0.0.1:${port}`
const platformUrl = process.env.EAB_PLATFORM_URL ?? 'http://127.0.0.1:4000'

createApp(publicUrl, platformVerifier(remoteJwks(platformUrl))).listen(
  port,
  process.env.HOST ?? '127.0.0.1',
  () => {
    console.info(`{{name}} 已启动：${publicUrl}/.well-known/agent-card.json`)
  },
)

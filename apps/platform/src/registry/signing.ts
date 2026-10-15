import {
  createHash,
  createHmac,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  sign,
  verify,
  type KeyObject,
} from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import canonicalize from 'canonicalize'

/**
 * 标品包签名（主提示词 5.2 节）。演示用内容哈希加平台私钥签名：
 * 对解析后资产的规范化 JSON（RFC 8785）取 sha256，再用 Ed25519 签名。
 * 生产环境应把私钥放进 KMS/HSM，签名在构建流水线里完成。
 */
export interface Signer {
  sign(payload: string): string
  verify(payload: string, signature: string): boolean
  /**
   * 带密钥的摘要（HMAC-SHA256 截断到 64 位），用于裁剪占位块里的“是否已变更”标记。
   * 没有密钥就无法用字典比对猜出被隐藏的原文（ADR 0004）。
   */
  digest(text: string): string
}

/**
 * 签名内容同时绑定资产标识、版本、发布状态和内容哈希：
 * 把一个版本的签名挪到另一个版本、或者把已下线的版本在库里改回已发布，都会验签失败。
 */
export function signingPayload(ref: string, contentHash: string): string {
  return `eab.dev/v1|${ref}|published|${contentHash}`
}

export function canonicalJson(value: unknown): string {
  const text = canonicalize(value)
  if (text === undefined) throw new Error('无法规范化资产内容')
  return text
}

export function contentHashOf(canonical: string): string {
  return `sha256:${createHash('sha256').update(canonical, 'utf8').digest('hex')}`
}

export class Ed25519Signer implements Signer {
  readonly #digestKey: Buffer

  constructor(
    private readonly privateKey: KeyObject,
    private readonly publicKey: KeyObject,
  ) {
    // 摘要密钥由私钥派生，不另外保存
    this.#digestKey = createHash('sha256')
      .update('eab-redaction-digest')
      .update(privateKey.export({ type: 'pkcs8', format: 'der' }))
      .digest()
  }

  /** 测试和命令行用的临时密钥 */
  static ephemeral(): Ed25519Signer {
    const { privateKey, publicKey } = generateKeyPairSync('ed25519')
    return new Ed25519Signer(privateKey, publicKey)
  }

  /** 从数据目录读取平台密钥，首次启动时生成。密钥文件在 .gitignore 里，不进仓库 */
  static fromDirectory(dir: string): Ed25519Signer {
    const privatePath = join(dir, 'platform-ed25519.pem')
    if (!existsSync(privatePath)) {
      mkdirSync(dir, { recursive: true })
      const { privateKey } = generateKeyPairSync('ed25519')
      writeFileSync(privatePath, privateKey.export({ type: 'pkcs8', format: 'pem' }), {
        mode: 0o600,
      })
    }
    const privateKey = createPrivateKey(readFileSync(privatePath, 'utf8'))
    return new Ed25519Signer(privateKey, createPublicKey(privateKey))
  }

  sign(payload: string): string {
    return sign(null, Buffer.from(payload, 'utf8'), this.privateKey).toString('base64')
  }

  digest(text: string): string {
    return createHmac('sha256', this.#digestKey).update(text, 'utf8').digest('hex').slice(0, 16)
  }

  verify(payload: string, signature: string): boolean {
    try {
      return verify(
        null,
        Buffer.from(payload, 'utf8'),
        this.publicKey,
        Buffer.from(signature, 'base64'),
      )
    } catch {
      return false
    }
  }
}

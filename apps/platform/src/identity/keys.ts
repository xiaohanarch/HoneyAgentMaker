import { generateKeyPairSync, createPrivateKey, createPublicKey, type KeyObject } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { exportJWK, type JWK } from 'jose'

/**
 * 身份服务的签名密钥（EdDSA / Ed25519），与资产签名密钥分开存放。
 * 生产环境应由企业 IdP 签发令牌，平台只做令牌交换与校验（ADR 0005）。
 */
export interface IdentityKeys {
  privateKey: KeyObject
  publicKey: KeyObject
  kid: string
  jwks: { keys: JWK[] }
}

async function fromPrivateKey(privateKey: KeyObject, kid: string): Promise<IdentityKeys> {
  const publicKey = createPublicKey(privateKey)
  const jwk = { ...(await exportJWK(publicKey)), kid, alg: 'EdDSA', use: 'sig' }
  return { privateKey, publicKey, kid, jwks: { keys: [jwk] } }
}

export function ephemeralIdentityKeys(kid = 'eab-identity-test'): Promise<IdentityKeys> {
  return fromPrivateKey(generateKeyPairSync('ed25519').privateKey, kid)
}

export function identityKeysFromDirectory(dir: string): Promise<IdentityKeys> {
  const path = join(dir, 'identity-ed25519.pem')
  if (!existsSync(path)) {
    mkdirSync(dir, { recursive: true })
    const { privateKey } = generateKeyPairSync('ed25519')
    writeFileSync(path, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
  }
  return fromPrivateKey(createPrivateKey(readFileSync(path, 'utf8')), 'eab-identity-1')
}

"""校验平台签发的服务令牌。

平台调用外部 Agent 时带 Bearer 服务令牌（EdDSA 签名的 JWT，受众 eab-a2a，kind=service，sub 是最终用户，act 是发起调用的平台 Agent）。
这里按平台公开的 JWKS 验签，并检查签发者、受众、有效期和令牌种类；没有令牌或校验失败一律返回 401，不做匿名降级。
"""

from __future__ import annotations

import os
from dataclasses import dataclass

import jwt
from jwt import PyJWKClient

ISSUER = "eab-identity"
AUDIENCE = "eab-a2a"


@dataclass(frozen=True)
class Caller:
    user: str
    tenant: str
    agent: str


class AuthError(Exception):
    pass


class TokenVerifier:
    def __init__(self, jwks_url: str | None = None, jwk_client: PyJWKClient | None = None) -> None:
        url = jwks_url or os.environ.get("EAB_PLATFORM_URL", "http://127.0.0.1:4000").rstrip("/") + "/identity/v1/jwks.json"
        self._jwks = jwk_client or PyJWKClient(url, cache_keys=True)

    def verify(self, authorization: str | None) -> Caller:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise AuthError("缺少平台签发的服务令牌")
        token = authorization.split(" ", 1)[1].strip()
        try:
            key = self._jwks.get_signing_key_from_jwt(token)
            claims = jwt.decode(token, key.key, algorithms=["EdDSA"], audience=AUDIENCE, issuer=ISSUER)
        except jwt.PyJWTError as error:
            raise AuthError(f"服务令牌无效：{error}") from error
        if claims.get("kind") != "service":
            raise AuthError("令牌种类不是服务令牌")
        act = claims.get("act") or {}
        return Caller(user=str(claims.get("sub")), tenant=str(claims.get("tid")), agent=str(act.get("sub", "")))

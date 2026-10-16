"""供应商风险舆情 Agent 的 A2A Server（A2A 官方 Python SDK，JSON-RPC 传输）。

- Agent Card 同时在 /.well-known/agent-card.json（A2A v1.0）和 /.well-known/agent.json（旧路径）提供；
- JSON-RPC 端点开启 0.3 兼容，旧版客户端也能调用；
- 除 Agent Card 外的请求都要带平台签发的服务令牌；
- 支持流式、多轮（input-required）和推送通知，推送地址只允许平台的回调地址。
"""

from __future__ import annotations

import logging
import os
import urllib.parse

import httpx
import uvicorn
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import BasePushNotificationSender, InMemoryPushNotificationConfigStore, InMemoryTaskStore
from a2a.types import (
    AgentCapabilities,
    AgentCard,
    AgentInterface,
    AgentProvider,
    AgentSkill,
    HTTPAuthSecurityScheme,
    SecurityRequirement,
    SecurityScheme,
    StringList,
)
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from supplier_risk.auth import AuthError, TokenVerifier
from supplier_risk.executor import SupplierRiskExecutor

LOG = logging.getLogger("supplier-risk")


def agent_card(base_url: str) -> AgentCard:
    return AgentCard(
        name="供应商风险舆情 Agent",
        description="按供应商名称查询外部舆情，给出风险等级和依据。用 LangGraph 编写，经 A2A 接入。",
        version="1.0.0",
        provider=AgentProvider(organization="外部服务商（演示）", url=base_url),
        supported_interfaces=[AgentInterface(url=base_url + "/", protocol_binding="JSONRPC", protocol_version="1.0")],
        capabilities=AgentCapabilities(streaming=True, push_notifications=True),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        security_schemes={
            "platformToken": SecurityScheme(
                http_auth_security_scheme=HTTPAuthSecurityScheme(
                    scheme="bearer", bearer_format="JWT", description="ERP Agent Builder 平台签发的服务令牌"
                )
            )
        },
        security_requirements=[SecurityRequirement(schemes={"platformToken": StringList()})],
        skills=[
            AgentSkill(
                id="supplier-risk",
                name="供应商风险舆情",
                description="查询供应商近期的外部舆情（停产、诉讼、提价等），给出风险等级和依据",
                tags=["supplier", "risk", "sentiment"],
                examples=["百润精密最近有没有什么风险", "帮我深度检索华远电子的舆情"],
            )
        ],
    )


class BearerAuth(BaseHTTPMiddleware):
    """Agent Card 公开，其余请求都要校验服务令牌"""

    def __init__(self, app, verifier: TokenVerifier) -> None:
        super().__init__(app)
        self.verifier = verifier

    async def dispatch(self, request, call_next):
        if request.method == "GET" and request.url.path.startswith("/.well-known/"):
            return await call_next(request)
        try:
            caller = self.verifier.verify(request.headers.get("authorization"))
        except AuthError as error:
            return JSONResponse({"error": str(error)}, status_code=401)
        LOG.info("A2A 调用：用户 %s（租户 %s），经平台 Agent %s", caller.user, caller.tenant, caller.agent)
        return await call_next(request)


def platform_only(platform_url: str):
    """推送通知只允许发往平台的回调地址，防止被当作请求转发器"""
    allowed = urllib.parse.urlparse(platform_url)

    async def validate(url: str) -> bool:
        target = urllib.parse.urlparse(url)
        return (target.scheme, target.netloc) == (allowed.scheme, allowed.netloc) and target.path.startswith("/a2a/callbacks/")

    return validate


def create_app(base_url: str, verifier: TokenVerifier | None = None, platform_url: str | None = None) -> Starlette:
    card = agent_card(base_url)
    platform = platform_url or os.environ.get("EAB_PLATFORM_URL", "http://127.0.0.1:4000")
    push_store = InMemoryPushNotificationConfigStore()
    validator = platform_only(platform)
    handler = DefaultRequestHandler(
        agent_executor=SupplierRiskExecutor(),
        task_store=InMemoryTaskStore(),
        agent_card=card,
        push_config_store=push_store,
        push_sender=BasePushNotificationSender(httpx.AsyncClient(timeout=10), push_store, push_url_validator=validator),
        push_url_validator=validator,
    )
    routes = [
        *create_agent_card_routes(card),
        *create_agent_card_routes(card, card_url="/.well-known/agent.json"),
        *create_jsonrpc_routes(handler, rpc_url="/", enable_v0_3_compat=True),
    ]
    app = Starlette(routes=routes)
    app.add_middleware(BearerAuth, verifier=verifier or TokenVerifier())
    return app


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    port = int(os.environ.get("PORT", "4200"))
    host = os.environ.get("HOST", "127.0.0.1")
    base = os.environ.get("PUBLIC_URL", f"http://127.0.0.1:{port}")
    uvicorn.run(create_app(base), host=host, port=port, log_level="info")


if __name__ == "__main__":
    main()

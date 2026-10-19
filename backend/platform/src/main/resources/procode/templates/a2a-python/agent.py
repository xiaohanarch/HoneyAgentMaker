"""A2A 外部 Agent 最小实现：Agent Card + JSON-RPC + 服务令牌校验。

- Agent Card 在 /.well-known/agent-card.json 与 /.well-known/agent.json 公开；
- 其余请求校验平台签发的服务令牌（EdDSA，受众 eab-a2a），失败返回 401，不做匿名降级；
- executor 走完整的任务生命周期，业务逻辑替换 process() 即可。
"""

from __future__ import annotations

import os

import jwt
import uvicorn
from a2a.helpers import new_task_from_user_message, new_text_part
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
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
from jwt import PyJWKClient
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

ISSUER = "eab-identity"
AUDIENCE = "eab-a2a"

BASE_URL = os.environ.get("PUBLIC_URL", "http://127.0.0.1:4300")
PLATFORM_URL = os.environ.get("EAB_PLATFORM_URL", "http://127.0.0.1:4000")


def agent_card() -> AgentCard:
    return AgentCard(
        name="{{name}}",
        description="<一句话描述这个外部 Agent 做什么>",
        version="0.1.0",
        provider=AgentProvider(organization="<组织名>", url=BASE_URL),
        supported_interfaces=[AgentInterface(url=BASE_URL + "/", protocol_binding="JSONRPC", protocol_version="1.0")],
        capabilities=AgentCapabilities(streaming=False, push_notifications=False),
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
                id="{{name}}",
                name="<技能名>",
                description="<技能说明>",
                tags=[],
                examples=["<用户会怎么问>"],
            )
        ],
    )


class BearerAuth(BaseHTTPMiddleware):
    """Agent Card 公开，其余请求都要校验平台签发的服务令牌"""

    def __init__(self, app, jwks: PyJWKClient) -> None:
        super().__init__(app)
        self.jwks = jwks

    async def dispatch(self, request, call_next):
        if request.method == "GET" and request.url.path.startswith("/.well-known/"):
            return await call_next(request)
        authorization = request.headers.get("authorization")
        if not authorization or not authorization.lower().startswith("bearer "):
            return JSONResponse({"error": "缺少平台签发的服务令牌"}, status_code=401)
        token = authorization.split(" ", 1)[1].strip()
        try:
            key = self.jwks.get_signing_key_from_jwt(token)
            claims = jwt.decode(token, key.key, algorithms=["EdDSA"], audience=AUDIENCE, issuer=ISSUER)
        except jwt.PyJWTError as error:
            return JSONResponse({"error": f"服务令牌无效：{error}"}, status_code=401)
        if claims.get("kind") != "service":
            return JSONResponse({"error": "令牌种类不是服务令牌"}, status_code=401)
        return await call_next(request)


def process(text: str) -> str:
    """业务逻辑：换成真实实现"""
    return f"收到：{text}"


class Executor(AgentExecutor):
    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        if context.current_task is None:
            # 新任务：先把 Task 放进事件队列，之后才能发状态更新
            await event_queue.enqueue_event(new_task_from_user_message(context.message))
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.start_work()
        await updater.add_artifact([new_text_part(process(context.get_user_input()))], name="{{name}}")
        await updater.complete()

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.cancel()


def create_app() -> Starlette:
    card = agent_card()
    handler = DefaultRequestHandler(agent_executor=Executor(), task_store=InMemoryTaskStore(), agent_card=card)
    routes = [
        *create_agent_card_routes(card),
        *create_agent_card_routes(card, card_url="/.well-known/agent.json"),
        *create_jsonrpc_routes(handler, rpc_url="/"),
    ]
    app = Starlette(routes=routes)
    jwks = PyJWKClient(PLATFORM_URL.rstrip("/") + "/identity/v1/jwks.json", cache_keys=True)
    app.add_middleware(BearerAuth, jwks=jwks)
    return app


def main() -> None:
    port = int(os.environ.get("PORT", "4300"))
    uvicorn.run(create_app(), host=os.environ.get("HOST", "127.0.0.1"), port=port, log_level="info")


if __name__ == "__main__":
    main()

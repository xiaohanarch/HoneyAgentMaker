"""供应商风险舆情 Agent 的测试：Agent Card、鉴权、完成的任务、多轮追问、令牌校验。"""

from __future__ import annotations

import json
import time
import uuid

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from starlette.testclient import TestClient

from supplier_risk import graph
from supplier_risk.app import create_app
from supplier_risk.auth import AuthError, Caller, TokenVerifier

BASE = "http://testserver"


class AllowVerifier:
    def verify(self, authorization):
        if not authorization:
            raise AuthError("缺少平台签发的服务令牌")
        return Caller(user="zhangmin", tenant="t001", agent="agent:tenant.t001.agents/price-negotiation-assistant")


@pytest.fixture()
def client():
    with TestClient(create_app(BASE, verifier=AllowVerifier(), platform_url="http://127.0.0.1:4000")) as c:
        yield c


def send(client, text, context_id=None, task_id=None, token="t"):
    message = {"messageId": str(uuid.uuid4()), "role": "ROLE_USER", "parts": [{"text": text}]}
    if context_id:
        message["contextId"] = context_id
    if task_id:
        message["taskId"] = task_id
    body = {"jsonrpc": "2.0", "id": "1", "method": "SendMessage", "params": {"message": message}}
    headers = {"A2A-Version": "1.0"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return client.post("/", json=body, headers=headers)


def task_text(task):
    texts = [p.get("text", "") for p in task.get("status", {}).get("message", {}).get("parts", [])]
    for artifact in task.get("artifacts", []):
        texts += [p.get("text", "") for p in artifact.get("parts", [])]
    return "\n".join(t for t in texts if t)


def test_agent_card_on_both_paths(client):
    for path in ("/.well-known/agent-card.json", "/.well-known/agent.json"):
        card = client.get(path).json()
        assert card["name"] == "供应商风险舆情 Agent"
        assert card["skills"][0]["id"] == "supplier-risk"
        assert card["capabilities"]["pushNotifications"] is True


def test_rejects_call_without_token(client):
    response = send(client, "百润精密最近有没有什么风险", token=None)
    assert response.status_code == 401


def test_completed_task_with_risk_level(client):
    result = send(client, "百润精密最近有没有什么风险").json()["result"]
    task = result["task"]
    assert task["status"]["state"] == "TASK_STATE_COMPLETED"
    text = task_text(task)
    assert "风险等级：高" in text
    assert "停产" in text


def test_multi_turn_input_required(client):
    first = send(client, "最近有没有什么风险").json()["result"]["task"]
    assert first["status"]["state"] == "TASK_STATE_INPUT_REQUIRED"
    assert "哪家供应商" in task_text(first)
    second = send(client, "华远电子", context_id=first["contextId"], task_id=first["id"]).json()["result"]["task"]
    assert second["id"] == first["id"]
    assert second["status"]["state"] == "TASK_STATE_COMPLETED"
    assert "华远电子" in task_text(second)


def test_graph_levels():
    assert graph.run("东瑞机械怎么样")["level"] == "low"
    assert graph.run("恒通的舆情")["supplier"] == "恒通物流"


class StaticJwks:
    def __init__(self, public_key):
        self.public_key = public_key

    def get_signing_key_from_jwt(self, token):
        return type("Key", (), {"key": self.public_key})()


def test_token_verifier_checks_signature_audience_and_kind():
    private = Ed25519PrivateKey.generate()
    verifier = TokenVerifier(jwk_client=StaticJwks(private.public_key()))
    now = int(time.time())
    claims = {
        "iss": "eab-identity",
        "aud": "eab-a2a",
        "iat": now,
        "exp": now + 300,
        "kind": "service",
        "sub": "zhangmin",
        "tid": "t001",
        "act": {"sub": "agent:tenant.t001.agents/price-negotiation-assistant"},
    }
    token = jwt.encode(claims, private, algorithm="EdDSA")
    caller = verifier.verify(f"Bearer {token}")
    assert caller.user == "zhangmin" and caller.agent.endswith("price-negotiation-assistant")

    with pytest.raises(AuthError):
        verifier.verify(None)
    wrong_audience = jwt.encode({**claims, "aud": "eab-platform"}, private, algorithm="EdDSA")
    with pytest.raises(AuthError):
        verifier.verify(f"Bearer {wrong_audience}")
    user_token = jwt.encode({**claims, "kind": "user"}, private, algorithm="EdDSA")
    with pytest.raises(AuthError):
        verifier.verify(f"Bearer {user_token}")
    other_key = Ed25519PrivateKey.generate()
    forged = jwt.encode(claims, other_key, algorithm="EdDSA")
    with pytest.raises(AuthError):
        verifier.verify(f"Bearer {forged}")


def test_data_file_is_valid_json():
    path = graph.Path(graph.__file__).parent / "data" / "sentiment.json"
    assert json.loads(path.read_text(encoding="utf-8"))["suppliers"]

# 供应商风险舆情 Agent（Python + LangGraph）

按供应商名称查询本地模拟的舆情数据（`supplier_risk/data/sentiment.json`，均为虚构），给出风险等级和依据。

- `graph.py`：LangGraph 状态图，识别供应商，识别不到时追问，识别到后查舆情并评估。
- `executor.py`：A2A 的 AgentExecutor，把图接到任务的生命周期上。话术里有“深度”时模拟耗时检索（默认 75 秒，`RISK_SLOW_SECONDS`），超过平台的同步超时，平台转为异步任务等推送回调。
- `auth.py`：校验平台签发的服务令牌。
- `app.py`：Agent Card（两个路径）、JSON-RPC 路由（开启 0.3 兼容）、鉴权中间件；推送地址只允许平台的 `/a2a/callbacks/`。

```
uv sync
uv run python -m supplier_risk.app          # 默认 127.0.0.1:4200，EAB_PLATFORM_URL 指向平台
uv run pytest -q
```

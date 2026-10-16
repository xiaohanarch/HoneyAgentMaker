"""供应商风险舆情的 LangGraph 图：识别供应商 → 查询舆情 → 评估风险等级。

不调用大模型，规则确定，便于离线演示。识别不到供应商时图停在 ask 节点，执行器把追问交给调用方（A2A 的 input-required）。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import TypedDict

from langgraph.graph import END, START, StateGraph

DATA = json.loads((Path(__file__).parent / "data" / "sentiment.json").read_text(encoding="utf-8"))

SEVERITY_WEIGHT = {"high": 3, "medium": 2, "low": 1}
LEVEL_NAMES = {"high": "高", "medium": "中", "low": "低"}


class RiskState(TypedDict, total=False):
    text: str
    supplier: str | None
    events: list[dict]
    level: str
    answer: str
    question: str


def identify(state: RiskState) -> RiskState:
    text = state.get("text", "")
    for supplier in DATA["suppliers"]:
        names = [supplier["name"], *supplier.get("aliases", [])]
        if any(name in text for name in names):
            return {"supplier": supplier["name"]}
    return {"supplier": None}


def ask(state: RiskState) -> RiskState:
    known = "、".join(s["name"] for s in DATA["suppliers"])
    return {"question": f"请告诉我要查询哪家供应商（例如：{known}）。"}


def lookup(state: RiskState) -> RiskState:
    supplier = next(s for s in DATA["suppliers"] if s["name"] == state["supplier"])
    return {"events": supplier["events"]}


def assess(state: RiskState) -> RiskState:
    events = state.get("events", [])
    score = sum(SEVERITY_WEIGHT[e["severity"]] for e in events)
    level = "high" if score >= 5 else "medium" if score >= 2 else "low"
    lines = [f"{state['supplier']}的外部舆情风险等级：{LEVEL_NAMES[level]}。"]
    if events:
        lines.append("依据：")
        for event in events:
            lines.append(f"- {event['date']}（{event['source']}，{LEVEL_NAMES[event['severity']]}）{event['title']}")
    else:
        lines.append("近 90 天没有检索到相关舆情。")
    lines.append("数据来源：模拟的外部舆情库，仅供演示。")
    return {"level": level, "answer": "\n".join(lines)}


def route(state: RiskState) -> str:
    return "lookup" if state.get("supplier") else "ask"


def build_graph():
    graph = StateGraph(RiskState)
    graph.add_node("identify", identify)
    graph.add_node("ask", ask)
    graph.add_node("lookup", lookup)
    graph.add_node("assess", assess)
    graph.add_edge(START, "identify")
    graph.add_conditional_edges("identify", route, {"ask": "ask", "lookup": "lookup"})
    graph.add_edge("lookup", "assess")
    graph.add_edge("assess", END)
    graph.add_edge("ask", END)
    return graph.compile()


GRAPH = build_graph()


def run(text: str) -> RiskState:
    return GRAPH.invoke({"text": text})

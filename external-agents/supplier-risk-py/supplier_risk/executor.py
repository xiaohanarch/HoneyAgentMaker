"""A2A 的 AgentExecutor：把 LangGraph 图接到 A2A 任务的生命周期上。

- 识别不到供应商时进入 input-required，调用方带着 contextId、taskId 回答后继续；
- 话术里有“深度”时模拟一次耗时的深度检索（默认 75 秒，超过平台 60 秒的同步超时），平台会转为异步任务，结果经推送回调送达。
"""

from __future__ import annotations

import asyncio
import os

from a2a.helpers import new_task_from_user_message, new_text_part
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.tasks import TaskUpdater

from supplier_risk import graph

SLOW_SECONDS = float(os.environ.get("RISK_SLOW_SECONDS", "75"))


class SupplierRiskExecutor(AgentExecutor):
    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        if context.current_task is None:
            # 新任务：先把 Task 放进事件队列，之后才能发状态更新
            await event_queue.enqueue_event(new_task_from_user_message(context.message))
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.start_work()
        text = context.get_user_input()
        if "深度" in text:
            await asyncio.sleep(SLOW_SECONDS)
        result = await asyncio.to_thread(graph.run, text)
        if result.get("question"):
            await updater.requires_input(updater.new_agent_message([new_text_part(result["question"])]))
            return
        await updater.add_artifact([new_text_part(result["answer"])], name="供应商风险评估")
        await updater.complete()

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        updater = TaskUpdater(event_queue, context.task_id, context.context_id)
        await updater.cancel()

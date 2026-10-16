-- P7：A2A 出站调用的任务记录（多轮续接、异步回调）与入站调用方（ADR 0012）
CREATE TABLE "a2a_tasks" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"session_id" text NOT NULL,
	"run_id" text,
	"remote" text NOT NULL,
	"task_id" text,
	"context_id" text,
	"state" text NOT NULL,
	"text" text,
	"callback_token_hash" text NOT NULL,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL
);

CREATE INDEX "a2a_tasks_session" ON "a2a_tasks" ("tenant_id", "user_id", "session_id", "remote", "updated_at");
CREATE INDEX "a2a_tasks_task" ON "a2a_tasks" ("task_id");

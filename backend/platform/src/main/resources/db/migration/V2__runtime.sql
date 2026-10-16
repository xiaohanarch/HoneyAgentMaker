-- P3 运行时：追踪、运行状态、会话记忆、长期记忆、风险告警、IM 渠道消息

CREATE TABLE "trace_spans" (
	"trace_id" text NOT NULL,
	"span_id" text NOT NULL,
	"parent_span_id" text,
	"name" text NOT NULL,
	"kind" text NOT NULL,
	"start_time" text NOT NULL,
	"end_time" text NOT NULL,
	"status" text NOT NULL,
	"status_message" text,
	"attributes" text NOT NULL,
	"prompt_segments" text,
	"tenant_id" text,
	PRIMARY KEY("trace_id", "span_id")
);

-- 一次运行：挂起时 journal 保存运行日志，恢复时从这里重放
CREATE TABLE "runs" (
	"id" text PRIMARY KEY NOT NULL,
	"trace_id" text NOT NULL,
	"session_id" text NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"agent" text NOT NULL,
	"mode" text NOT NULL,
	"delegation_id" text,
	"utterance" text NOT NULL,
	"status" text NOT NULL,
	"journal" text NOT NULL,
	"result" text,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL
);

CREATE INDEX "runs_session" ON "runs" ("session_id");

CREATE TABLE "session_messages" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"session_id" text NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"agent" text NOT NULL,
	"utterance" text NOT NULL,
	"reply" text NOT NULL,
	"at" text NOT NULL
);

CREATE INDEX "session_messages_session" ON "session_messages" ("session_id");

-- 长期记忆按“租户、用户、Agent”三元组隔离（主提示词 8.5 节）
CREATE TABLE "memories" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"agent" text NOT NULL,
	"text" text NOT NULL,
	"created_at" text NOT NULL
);

CREATE INDEX "memories_owner" ON "memories" ("tenant_id", "user_id", "agent");

CREATE TABLE "risk_alerts" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"at" text NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"agent" text NOT NULL,
	"run_id" text NOT NULL,
	"type" text NOT NULL,
	"severity" text NOT NULL,
	"message" text NOT NULL,
	"detail" text NOT NULL
);

CREATE TABLE "write_log" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"session_id" text NOT NULL,
	"tool" text NOT NULL,
	"at" text NOT NULL
);

CREATE INDEX "write_log_session" ON "write_log" ("session_id", "at");

-- 模拟 IM 渠道（主提示词 8.6 节）：通知步骤和 IM 通知工具的消息
CREATE TABLE "im_messages" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"at" text NOT NULL,
	"tenant_id" text NOT NULL,
	"channel" text NOT NULL,
	"recipient" text,
	"message" text NOT NULL,
	"run_id" text
);

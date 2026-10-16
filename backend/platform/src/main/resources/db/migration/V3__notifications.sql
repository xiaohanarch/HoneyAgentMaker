-- P4：统一入口的通知区，业务事件触发的受托运行结果推送给委托人
CREATE TABLE "notifications" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"at" text NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"title" text NOT NULL,
	"body" text NOT NULL,
	"source" text NOT NULL,
	"run_id" text,
	"trace_id" text,
	"read" integer NOT NULL DEFAULT 0
);

CREATE INDEX "notifications_user" ON "notifications" ("tenant_id", "user_id", "id");

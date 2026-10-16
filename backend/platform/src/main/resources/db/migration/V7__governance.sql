-- P8：治理中心、经验回流、升级回归、方案包、运行指标（ADR 0013）

-- 经验回流：用户在统一入口点“结论有误”，生成候选规则，进入审核队列；审核通过后写入租户语义包扩展
CREATE TABLE "feedback" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"user_id" text NOT NULL,
	"run_id" text NOT NULL,
	"trace_id" text,
	"agent" text NOT NULL,
	"utterance" text,
	"reason" text NOT NULL,
	"candidate" text NOT NULL,
	"decided_by" text NOT NULL,
	"status" text NOT NULL,
	"reviewer" text,
	"review_note" text,
	"pack_ref" text,
	"created_at" text NOT NULL,
	"reviewed_at" text
);

CREATE INDEX "feedback_tenant" ON "feedback" ("tenant_id", "status", "created_at");

-- 升级回归：标品 Agent 升级后，对依赖它的每个租户扩展运行评测，记录升级前后的对比
CREATE TABLE "upgrade_regressions" (
	"id" text PRIMARY KEY NOT NULL,
	"at" text NOT NULL,
	"agent" text NOT NULL,
	"from_version" text NOT NULL,
	"to_version" text NOT NULL,
	"tenant_id" text NOT NULL,
	"extension" text NOT NULL,
	"suite" text NOT NULL,
	"status" text NOT NULL,
	"baseline" text,
	"after" text,
	"regressions" text NOT NULL,
	"issues" text NOT NULL,
	"triggered_by" text NOT NULL
);

CREATE INDEX "upgrade_regressions_tenant" ON "upgrade_regressions" ("tenant_id", "at");

-- 方案包：方案里的资产解析、校验后编译成不可变的包（清单、内容哈希、签名），部署的对象是方案包
CREATE TABLE "solution_packages" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"solution" text NOT NULL,
	"version" text NOT NULL,
	"manifest" text NOT NULL,
	"manifest_hash" text NOT NULL,
	"signature" text NOT NULL,
	"created_at" text NOT NULL,
	"created_by" text NOT NULL
);

CREATE UNIQUE INDEX "solution_packages_version" ON "solution_packages" ("tenant_id", "solution", "version");

CREATE TABLE "package_deployments" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"tenant_id" text NOT NULL,
	"environment" text NOT NULL,
	"package_id" text NOT NULL,
	"active" integer NOT NULL DEFAULT 1,
	"deployed_at" text NOT NULL,
	"deployed_by" text NOT NULL,
	"note" text
);

-- 运行指标：每次运行一行，由运行记录和追踪汇总；simulated 为 1 的是“生成历史数据”灌入的模拟记录
CREATE TABLE "run_metrics" (
	"run_id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"agent" text NOT NULL,
	"user_id" text NOT NULL,
	"at" text NOT NULL,
	"status" text NOT NULL,
	"latency_ms" integer NOT NULL,
	"input_tokens" integer NOT NULL,
	"output_tokens" integer NOT NULL,
	"cost_usd" real NOT NULL,
	"tool_calls" integer NOT NULL,
	"tool_errors" integer NOT NULL,
	"confirmations" integer NOT NULL,
	"simulated" integer NOT NULL DEFAULT 0
);

CREATE INDEX "run_metrics_tenant" ON "run_metrics" ("tenant_id", "at");

-- 流程指标与业务结果（演示里是模拟数据）：按天、按指标记一个值
CREATE TABLE "business_metrics" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"tenant_id" text NOT NULL,
	"day" text NOT NULL,
	"layer" text NOT NULL,
	"metric" text NOT NULL,
	"agent" text,
	"value" real NOT NULL
);

CREATE INDEX "business_metrics_tenant" ON "business_metrics" ("tenant_id", "layer", "metric", "day");

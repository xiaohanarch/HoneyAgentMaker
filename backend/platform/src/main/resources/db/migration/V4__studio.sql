-- P5：Studio 的工作区草稿、环境部署、评测运行，以及测试运行的发起人与叠加的草稿（ADR 0010）

-- 测试运行：发起测试的开发者，以及私有环境里叠加的草稿（YAML 数组的 JSON），恢复挂起的运行时据此重建
ALTER TABLE "runs" ADD COLUMN "started_by" text;
ALTER TABLE "runs" ADD COLUMN "overlay" text;

-- 工作区草稿：向导每一步都可以保存，内容可改；发布时才写入注册中心成为不可改的版本
CREATE TABLE "studio_drafts" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"owner" text NOT NULL,
	"kind" text NOT NULL,
	"namespace" text NOT NULL,
	"name" text NOT NULL,
	"intent" text,
	"step" integer NOT NULL DEFAULT 1,
	"yaml" text NOT NULL,
	"eval_yaml" text,
	"published_version" text,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL
);

CREATE INDEX "studio_drafts_owner" ON "studio_drafts" ("tenant_id", "owner", "updated_at");

-- 环境部署：每个租户的 dev、test、prod 三个环境，晋级即新增一条记录，active 标记当前生效的版本，回滚恢复上一条
CREATE TABLE "deployments" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"tenant_id" text NOT NULL,
	"environment" text NOT NULL,
	"namespace" text NOT NULL,
	"name" text NOT NULL,
	"version" text NOT NULL,
	"active" integer NOT NULL DEFAULT 1,
	"deployed_at" text NOT NULL,
	"deployed_by" text NOT NULL,
	"note" text
);

CREATE INDEX "deployments_lookup" ON "deployments" ("tenant_id", "environment", "namespace", "name", "id");

-- 评测运行：按被测资产的内容哈希记录，晋级到 prod 时要求同一内容的评测通过
CREATE TABLE "eval_runs" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"suite" text NOT NULL,
	"target" text NOT NULL,
	"target_hash" text NOT NULL,
	"started_by" text NOT NULL,
	"started_at" text NOT NULL,
	"finished_at" text,
	"total" integer NOT NULL DEFAULT 0,
	"passed" integer NOT NULL DEFAULT 0,
	"min_pass_rate" real NOT NULL,
	"status" text NOT NULL,
	"result" text
);

CREATE INDEX "eval_runs_target" ON "eval_runs" ("tenant_id", "target", "target_hash", "started_at");

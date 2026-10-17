-- P9：意图驱动生成的六步方案卡（DevRequest 的工作材料，不进注册中心；发布后的交付物才是资产）

CREATE TABLE "request_plans" (
	"tenant_id" text NOT NULL,
	"request_ref" text NOT NULL,
	"plan" text NOT NULL,
	"adopted" text NOT NULL DEFAULT '[]',
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL,
	PRIMARY KEY ("tenant_id", "request_ref")
);

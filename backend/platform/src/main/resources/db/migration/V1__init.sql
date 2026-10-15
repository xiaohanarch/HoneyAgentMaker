-- 平台数据库初始结构，与 TS 版 drizzle 迁移 0000、0001 等价（ADR 0006）

CREATE TABLE "assets" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"kind" text NOT NULL,
	"namespace" text NOT NULL,
	"name" text NOT NULL,
	"version" text NOT NULL,
	"layer" text NOT NULL,
	"tenant_id" text,
	"status" text NOT NULL,
	"content_yaml" text NOT NULL,
	"content_json" text NOT NULL,
	"content_hash" text NOT NULL,
	"signature" text,
	"change_note" text,
	"created_by" text NOT NULL,
	"created_at" text NOT NULL,
	"updated_at" text NOT NULL
);

CREATE UNIQUE INDEX "assets_ns_name_version" ON "assets" ("namespace","name","version");
CREATE INDEX "assets_kind" ON "assets" ("kind");
CREATE INDEX "assets_tenant" ON "assets" ("tenant_id");
CREATE TABLE "audit_events" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"at" text NOT NULL,
	"actor" text NOT NULL,
	"action" text NOT NULL,
	"target" text NOT NULL,
	"decision" text NOT NULL,
	"reason" text NOT NULL,
	"detail" text
);

CREATE INDEX "audit_at" ON "audit_events" ("at");
CREATE INDEX "audit_action" ON "audit_events" ("action");

CREATE TABLE "business_events" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"received_at" text NOT NULL,
	"source" text NOT NULL,
	"type" text NOT NULL,
	"tenant_id" text NOT NULL,
	"payload" text NOT NULL
);

CREATE INDEX "events_type" ON "business_events" ("type");
CREATE TABLE "delegations" (
	"id" text PRIMARY KEY NOT NULL,
	"tenant_id" text NOT NULL,
	"delegator_user_id" text NOT NULL,
	"agent" text NOT NULL,
	"scope" text NOT NULL,
	"valid_from" text NOT NULL,
	"valid_until" text NOT NULL,
	"revoked_at" text,
	"revoked_by" text,
	"note" text,
	"created_by" text NOT NULL,
	"created_at" text NOT NULL
);

CREATE INDEX "delegations_tenant" ON "delegations" ("tenant_id");
CREATE TABLE "tenant_settings" (
	"tenant_id" text PRIMARY KEY NOT NULL,
	"l3_enabled" integer NOT NULL,
	"assistant_roles" text NOT NULL,
	"updated_at" text NOT NULL
);

ALTER TABLE "audit_events" ADD "tenant_id" text;
ALTER TABLE "audit_events" ADD "user_id" text;
ALTER TABLE "audit_events" ADD "agent" text;
ALTER TABLE "audit_events" ADD "extensions" text;
ALTER TABLE "audit_events" ADD "session_id" text;
ALTER TABLE "audit_events" ADD "mode" text;
ALTER TABLE "audit_events" ADD "checkpoint" text;
ALTER TABLE "audit_events" ADD "tool" text;
ALTER TABLE "audit_events" ADD "data_scope" text;
ALTER TABLE "audit_events" ADD "rule_id" text;
ALTER TABLE "audit_events" ADD "result_summary" text;
CREATE INDEX "audit_tenant_user" ON "audit_events" ("tenant_id","user_id");
CREATE INDEX "audit_agent" ON "audit_events" ("agent");

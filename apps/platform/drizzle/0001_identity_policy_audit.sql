CREATE TABLE `business_events` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`received_at` text NOT NULL,
	`source` text NOT NULL,
	`type` text NOT NULL,
	`tenant_id` text NOT NULL,
	`payload` text NOT NULL
);
--> statement-breakpoint
CREATE INDEX `events_type` ON `business_events` (`type`);--> statement-breakpoint
CREATE TABLE `delegations` (
	`id` text PRIMARY KEY NOT NULL,
	`tenant_id` text NOT NULL,
	`delegator_user_id` text NOT NULL,
	`agent` text NOT NULL,
	`scope` text NOT NULL,
	`valid_from` text NOT NULL,
	`valid_until` text NOT NULL,
	`revoked_at` text,
	`revoked_by` text,
	`note` text,
	`created_by` text NOT NULL,
	`created_at` text NOT NULL
);
--> statement-breakpoint
CREATE INDEX `delegations_tenant` ON `delegations` (`tenant_id`);--> statement-breakpoint
CREATE TABLE `tenant_settings` (
	`tenant_id` text PRIMARY KEY NOT NULL,
	`l3_enabled` integer NOT NULL,
	`assistant_roles` text NOT NULL,
	`updated_at` text NOT NULL
);
--> statement-breakpoint
ALTER TABLE `audit_events` ADD `tenant_id` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `user_id` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `agent` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `extensions` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `session_id` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `mode` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `checkpoint` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `tool` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `data_scope` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `rule_id` text;--> statement-breakpoint
ALTER TABLE `audit_events` ADD `result_summary` text;--> statement-breakpoint
CREATE INDEX `audit_tenant_user` ON `audit_events` (`tenant_id`,`user_id`);--> statement-breakpoint
CREATE INDEX `audit_agent` ON `audit_events` (`agent`);
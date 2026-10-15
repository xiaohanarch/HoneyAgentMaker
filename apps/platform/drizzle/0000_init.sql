CREATE TABLE `assets` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`kind` text NOT NULL,
	`namespace` text NOT NULL,
	`name` text NOT NULL,
	`version` text NOT NULL,
	`layer` text NOT NULL,
	`tenant_id` text,
	`status` text NOT NULL,
	`content_yaml` text NOT NULL,
	`content_json` text NOT NULL,
	`content_hash` text NOT NULL,
	`signature` text,
	`change_note` text,
	`created_by` text NOT NULL,
	`created_at` text NOT NULL,
	`updated_at` text NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `assets_ns_name_version` ON `assets` (`namespace`,`name`,`version`);--> statement-breakpoint
CREATE INDEX `assets_kind` ON `assets` (`kind`);--> statement-breakpoint
CREATE INDEX `assets_tenant` ON `assets` (`tenant_id`);--> statement-breakpoint
CREATE TABLE `audit_events` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`at` text NOT NULL,
	`actor` text NOT NULL,
	`action` text NOT NULL,
	`target` text NOT NULL,
	`decision` text NOT NULL,
	`reason` text NOT NULL,
	`detail` text
);
--> statement-breakpoint
CREATE INDEX `audit_at` ON `audit_events` (`at`);--> statement-breakpoint
CREATE INDEX `audit_action` ON `audit_events` (`action`);
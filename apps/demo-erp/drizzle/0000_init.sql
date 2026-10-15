CREATE TABLE `access_log` (
	`id` integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	`at` text NOT NULL,
	`tenant_id` text,
	`api` text NOT NULL,
	`operation` text NOT NULL,
	`principal` text NOT NULL,
	`agent` text,
	`mode` text NOT NULL,
	`outcome` text NOT NULL,
	`code` text NOT NULL,
	`reason` text NOT NULL,
	`data_scope` text NOT NULL,
	`target` text NOT NULL
);
--> statement-breakpoint
CREATE INDEX `access_log_at` ON `access_log` (`at`);--> statement-breakpoint
CREATE TABLE `accounting_periods` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`company_id` text NOT NULL,
	`status` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `company_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `ap_invoices` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`supplier_id` text NOT NULL,
	`po_id` text NOT NULL,
	`company_id` text NOT NULL,
	`invoice_date` text NOT NULL,
	`amount` real NOT NULL,
	`currency` text NOT NULL,
	`match_status` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `close_check_items` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`period_id` text NOT NULL,
	`company_id` text NOT NULL,
	`product` text NOT NULL,
	`name` text NOT NULL,
	`status` text NOT NULL,
	`owner` text NOT NULL,
	`blocker` text,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `deliveries` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`po_id` text NOT NULL,
	`po_line_id` text NOT NULL,
	`planned_date` text NOT NULL,
	`actual_date` text,
	`quantity` real NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE INDEX `deliveries_po` ON `deliveries` (`po_id`);--> statement-breakpoint
CREATE TABLE `delivery_date_changes` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`po_id` text NOT NULL,
	`old_date` text NOT NULL,
	`new_date` text NOT NULL,
	`reason` text NOT NULL,
	`status` text NOT NULL,
	`requested_by` text NOT NULL,
	`requested_via` text,
	`approved_by` text,
	`approved_via` text,
	`created_at` text NOT NULL,
	`approved_at` text,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `followup_tasks` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`title` text NOT NULL,
	`assignee` text NOT NULL,
	`related_object` text,
	`due_date` text,
	`created_by` text NOT NULL,
	`created_via` text,
	`created_at` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `idempotency_keys` (
	`tenant_id` text NOT NULL,
	`operation` text NOT NULL,
	`key` text NOT NULL,
	`status_code` integer NOT NULL,
	`response` text NOT NULL,
	`created_at` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `operation`, `key`)
);
--> statement-breakpoint
CREATE TABLE `inventory_balances` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`material_id` text NOT NULL,
	`warehouse_id` text NOT NULL,
	`company_id` text NOT NULL,
	`quantity` real NOT NULL,
	`value` real NOT NULL,
	`age_days` integer NOT NULL,
	`slow_moving` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `materials` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`name` text NOT NULL,
	`category` text NOT NULL,
	`unit` text NOT NULL,
	`safety_stock` real NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `payment_plans` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`invoice_id` text NOT NULL,
	`company_id` text NOT NULL,
	`due_date` text NOT NULL,
	`amount` real NOT NULL,
	`currency` text NOT NULL,
	`status` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `po_lines` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`po_id` text NOT NULL,
	`line_no` integer NOT NULL,
	`material_id` text NOT NULL,
	`quantity` real NOT NULL,
	`unit_price` real NOT NULL,
	`planned_delivery_date` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE INDEX `po_lines_po` ON `po_lines` (`po_id`);--> statement-breakpoint
CREATE TABLE `purchase_orders` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`supplier_id` text NOT NULL,
	`purchasing_org` text NOT NULL,
	`company_id` text NOT NULL,
	`created_at` text NOT NULL,
	`status` text NOT NULL,
	`currency` text NOT NULL,
	`total_amount` real NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE INDEX `po_tenant` ON `purchase_orders` (`tenant_id`);--> statement-breakpoint
CREATE TABLE `quotations` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`rfq_id` text NOT NULL,
	`line_no` integer NOT NULL,
	`quantity` real NOT NULL,
	`unit_price` real NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `rfqs` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`supplier_id` text NOT NULL,
	`purchasing_org` text NOT NULL,
	`material_id` text NOT NULL,
	`issued_at` text NOT NULL,
	`status` text NOT NULL,
	`target_price` real NOT NULL,
	`currency` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE INDEX `rfqs_tenant` ON `rfqs` (`tenant_id`);--> statement-breakpoint
CREATE TABLE `stock_movements` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`material_id` text NOT NULL,
	`warehouse_id` text NOT NULL,
	`company_id` text NOT NULL,
	`direction` text NOT NULL,
	`quantity` real NOT NULL,
	`date` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `suppliers` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`name` text NOT NULL,
	`rating` text NOT NULL,
	`purchasing_org` text NOT NULL,
	`city` text NOT NULL,
	`price_terms` text NOT NULL,
	`warranty_months` integer NOT NULL,
	`lead_time_days` integer NOT NULL,
	`payment_terms` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE INDEX `suppliers_tenant` ON `suppliers` (`tenant_id`);--> statement-breakpoint
CREATE TABLE `transaction_anomalies` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`company_id` text NOT NULL,
	`anomaly_type` text NOT NULL,
	`severity` text NOT NULL,
	`amount` real NOT NULL,
	`currency` text NOT NULL,
	`detected_at` text NOT NULL,
	`status` text NOT NULL,
	`description` text NOT NULL,
	`invoice_ids` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);
--> statement-breakpoint
CREATE TABLE `warehouses` (
	`id` text NOT NULL,
	`tenant_id` text NOT NULL,
	`name` text NOT NULL,
	`company_id` text NOT NULL,
	PRIMARY KEY(`tenant_id`, `id`)
);

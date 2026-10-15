ALTER TABLE `idempotency_keys` ADD `user_id` text DEFAULT '' NOT NULL;--> statement-breakpoint
ALTER TABLE `idempotency_keys` ADD `fingerprint` text DEFAULT '' NOT NULL;
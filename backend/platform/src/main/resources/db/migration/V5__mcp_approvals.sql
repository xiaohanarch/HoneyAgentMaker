-- P6：MCP Server 登记的审批状态（ADR 0011）。未审批通过的 MCP Server 不能用在 prod
CREATE TABLE "mcp_approvals" (
	"id" integer PRIMARY KEY AUTOINCREMENT NOT NULL,
	"tenant_id" text NOT NULL,
	"source" text NOT NULL,
	"status" text NOT NULL,
	"requested_by" text NOT NULL,
	"requested_at" text NOT NULL,
	"decided_by" text,
	"decided_at" text,
	"note" text
);

CREATE UNIQUE INDEX "mcp_approvals_source" ON "mcp_approvals" ("tenant_id", "source");

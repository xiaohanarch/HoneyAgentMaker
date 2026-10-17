-- 故事 E：物料的备选供应商（含现货、协议价与备货周期），以及从备选供应商补货时生成的请购单草稿

CREATE TABLE "supplier_alternatives" (
	"id" text NOT NULL,
	"tenant_id" text NOT NULL,
	"material_id" text NOT NULL,
	"supplier_id" text NOT NULL,
	"purchasing_org" text NOT NULL,
	"unit_price" real NOT NULL,
	"stock_qty" real NOT NULL,
	"lead_time_days" integer NOT NULL,
	PRIMARY KEY("tenant_id", "id")
);

CREATE INDEX "supplier_alternatives_material" ON "supplier_alternatives" ("material_id");
CREATE TABLE "purchase_requisitions" (
	"id" text NOT NULL,
	"tenant_id" text NOT NULL,
	"material_id" text NOT NULL,
	"supplier_id" text NOT NULL,
	"purchasing_org" text NOT NULL,
	"source_po_id" text,
	"quantity" real NOT NULL,
	"unit_price" real NOT NULL,
	"amount" real NOT NULL,
	"currency" text NOT NULL,
	"status" text NOT NULL,
	"note" text,
	"requested_by" text NOT NULL,
	"requested_via" text,
	"created_at" text NOT NULL,
	PRIMARY KEY("tenant_id", "id")
);

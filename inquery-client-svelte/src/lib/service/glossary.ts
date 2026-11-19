import createRequest from "./base";

export interface ICatalogTerm {
  id?: number;
  dataSourceId: number;
  canonicalValue: string;
  aliases: string[];
  /** Optional schema.table.column holding the value. */
  columnRef?: string | null;
  note?: string | null;
}

export interface ICatalogMetric {
  id?: number;
  dataSourceId: number;
  name: string;
  aliases: string[];
  description?: string | null;
  /** schema.table (or database.schema.table). */
  tableRef: string;
  /** SQL aggregate expression, e.g. SUM(amount). */
  expression: string;
  /** SQL condition always applied. */
  filters?: string | null;
  grain?: string | null;
  caveats?: string | null;
  /** additive | distinct | non_additive, or empty. */
  aggregation?: string | null;
}

// Saves use the default error level: a failed save rejects (and toasts),
// so the editor stays open and shows the server's validation message.
const listTerms = createRequest<{ dataSourceId: number }, ICatalogTerm[]>(
  "/api/catalog/glossary/terms",
);
const saveTerm = createRequest<ICatalogTerm, ICatalogTerm>(
  "/api/catalog/glossary/terms",
  { method: "post" },
);
const deleteTerm = createRequest<{ id: number }, void>(
  "/api/catalog/glossary/terms/:id",
  { method: "delete" },
);
const listMetrics = createRequest<{ dataSourceId: number }, ICatalogMetric[]>(
  "/api/catalog/glossary/metrics",
);
// databaseName / schemaName are defaults for a table reference without them;
// the server also opens the data source to check the metric's columns exist.
const saveMetric = createRequest<
  { dataSourceId: number; databaseName?: string; schemaName?: string; metric: ICatalogMetric },
  ICatalogMetric
>(
  "/api/catalog/glossary/metrics",
  { method: "post" },
);
const deleteMetric = createRequest<{ id: number }, void>(
  "/api/catalog/glossary/metrics/:id",
  { method: "delete" },
);

export interface ICatalogRule {
  id?: number;
  dataSourceId: number;
  /** Written as an instruction, e.g. "Never count orders with status 'pending'". */
  ruleText: string;
  /** Optional schema.table the rule is about. */
  tableRef?: string | null;
  enabled: boolean;
  /** Returned on save when the rule reads like a description. */
  styleWarning?: string | null;
}

const listRules = createRequest<{ dataSourceId: number }, ICatalogRule[]>(
  "/api/catalog/glossary/rules",
);
const saveRule = createRequest<ICatalogRule, ICatalogRule>(
  "/api/catalog/glossary/rules",
  { method: "post" },
);
const deleteRule = createRequest<{ id: number }, void>(
  "/api/catalog/glossary/rules/:id",
  { method: "delete" },
);

export default {
  listRules,
  saveRule,
  deleteRule,
  listTerms,
  saveTerm,
  deleteTerm,
  listMetrics,
  saveMetric,
  deleteMetric,
};

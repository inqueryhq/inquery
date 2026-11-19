-- Data rules the assistant must always follow when writing SQL for a data
-- source (e.g. "Never count orders with status 'pending'"). Written as
-- instructions: descriptive facts are followed far less reliably.
CREATE TABLE IF NOT EXISTS catalog_rule (
    id BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT NOT NULL,
    rule_text TEXT NOT NULL,
    table_ref VARCHAR(512) DEFAULT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE catalog_rule IS 'Data rules per data source, injected into SQL generation';
COMMENT ON COLUMN catalog_rule.table_ref IS 'Optional schema.table the rule is about (shown with the rule)';
CREATE INDEX IF NOT EXISTS idx_catalog_rule_data_source ON catalog_rule(data_source_id);

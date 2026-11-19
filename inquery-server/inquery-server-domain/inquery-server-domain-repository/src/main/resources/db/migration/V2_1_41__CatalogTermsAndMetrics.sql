-- Business glossary: one canonical value with any number of aliases
-- (abbreviations, nicknames, other spellings). One alias may point to
-- several canonical values; the assistant asks when that is ambiguous.
CREATE TABLE IF NOT EXISTS catalog_term (
    id BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT NOT NULL,
    canonical_value VARCHAR(512) NOT NULL,
    aliases TEXT NOT NULL DEFAULT '[]',
    column_ref VARCHAR(512) DEFAULT NULL,
    note TEXT DEFAULT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE catalog_term IS 'Business glossary terms per data source';
COMMENT ON COLUMN catalog_term.canonical_value IS 'Exact value as stored in the data';
COMMENT ON COLUMN catalog_term.aliases IS 'JSON array of aliases / abbreviations';
COMMENT ON COLUMN catalog_term.column_ref IS 'Optional schema.table.column holding the value';
CREATE INDEX IF NOT EXISTS idx_catalog_term_data_source ON catalog_term(data_source_id);

-- Official metric definitions the assistant must follow when computing them.
CREATE TABLE IF NOT EXISTS catalog_metric (
    id BIGSERIAL PRIMARY KEY,
    data_source_id BIGINT NOT NULL,
    name VARCHAR(256) NOT NULL,
    aliases TEXT NOT NULL DEFAULT '[]',
    description TEXT DEFAULT NULL,
    table_ref VARCHAR(512) NOT NULL,
    expression TEXT NOT NULL,
    filters TEXT DEFAULT NULL,
    grain VARCHAR(512) DEFAULT NULL,
    caveats TEXT DEFAULT NULL,
    aggregation VARCHAR(32) DEFAULT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (data_source_id, name)
);
COMMENT ON TABLE catalog_metric IS 'Official metric definitions per data source';
COMMENT ON COLUMN catalog_metric.table_ref IS 'schema.table (or database.schema.table) the metric is computed from';
COMMENT ON COLUMN catalog_metric.expression IS 'SQL aggregate expression, e.g. SUM(amount)';
COMMENT ON COLUMN catalog_metric.filters IS 'SQL boolean condition always applied, e.g. status <> ''cancelled''';
COMMENT ON COLUMN catalog_metric.aggregation IS 'additive | distinct | non_additive (NULL = unknown)';

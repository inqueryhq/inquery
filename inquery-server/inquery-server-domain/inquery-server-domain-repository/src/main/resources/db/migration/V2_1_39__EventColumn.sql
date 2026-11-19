-- User-designated event column: marks the column whose values are analytics
-- event names (e.g. event_name). Drives per-event description structure and
-- the code-anchored GitHub event scan during AI collection, replacing the
-- name/cardinality heuristic when set.
ALTER TABLE data_catalog_column ADD COLUMN IF NOT EXISTS is_event_column BOOLEAN DEFAULT FALSE;
COMMENT ON COLUMN data_catalog_column.is_event_column IS 'User-designated event column (values are event names)';

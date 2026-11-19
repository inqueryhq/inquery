-- How a numeric column may be aggregated across rows:
--   additive     - safe to SUM across rows (built from SUM/COUNT)
--   distinct     - distinct count; rows overlap, so a SUM double counts
--   non_additive - ratio, average, extreme or running/snapshot value
-- NULL means unknown. Read by the SQL guard and result interpretation.
ALTER TABLE data_catalog_column ADD COLUMN IF NOT EXISTS aggregation VARCHAR(32);
COMMENT ON COLUMN data_catalog_column.aggregation IS 'additive | distinct | non_additive (NULL = unknown)';

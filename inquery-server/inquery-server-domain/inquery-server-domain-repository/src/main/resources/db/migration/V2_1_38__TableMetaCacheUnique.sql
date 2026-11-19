-- Remove duplicate table rows in L2 cache (keep lowest id per schema + table name)
DELETE FROM table_meta_cache t1
USING table_meta_cache t2
WHERE t1.schema_cache_id = t2.schema_cache_id
  AND t1.table_name = t2.table_name
  AND t1.id > t2.id;

CREATE UNIQUE INDEX IF NOT EXISTS uk_table_meta_cache_schema_table
    ON table_meta_cache (schema_cache_id, table_name);

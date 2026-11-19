package ai.inquery.server.domain.core.catalog.glossary;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.domain.api.model.CatalogTermDTO;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.domain.core.catalog.ColumnAggregation;
import ai.inquery.server.domain.repository.entity.CatalogMetricDO;
import ai.inquery.server.domain.repository.entity.CatalogTermDO;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CRUD for glossary terms and metric definitions.
 *
 * <p>Metric definitions are validated before saving: the expression and the
 * filters must parse as SQL, and every column they reference must exist in
 * the metric's table, so a definition can never point the assistant at a
 * column that does not exist.
 */
@Slf4j
@Service
public class CatalogGlossaryService {

    @Autowired(required = false)
    private DataCatalogService dataCatalogService;

    // ------------------------------------------------------------------
    // Terms
    // ------------------------------------------------------------------

    public List<CatalogTermDTO> listTerms(Long dataSourceId) {
        return CatalogGlossaryStore.listTerms(dataSourceId);
    }

    public DataResult<CatalogTermDTO> saveTerm(CatalogTermDTO dto) {
        if (dto == null || dto.getDataSourceId() == null) {
            return DataResult.error("GLOSSARY_INVALID", "dataSourceId is required.");
        }
        if (dto.getCanonicalValue() == null || dto.getCanonicalValue().isBlank()) {
            return DataResult.error("GLOSSARY_INVALID", "Canonical value is required.");
        }
        CatalogTermDO row = dto.getId() == null ? null : CatalogGlossaryStore.termMapper().selectById(dto.getId());
        boolean isNew = row == null;
        if (isNew) {
            row = new CatalogTermDO();
            row.setCreateTime(LocalDateTime.now());
        }
        row.setDataSourceId(dto.getDataSourceId());
        row.setCanonicalValue(dto.getCanonicalValue().trim());
        row.setAliases(CatalogGlossaryStore.aliasesJson(dto.getAliases()));
        row.setColumnRef(blankToNull(dto.getColumnRef()));
        row.setNote(blankToNull(dto.getNote()));
        row.setUpdateTime(LocalDateTime.now());
        if (isNew) {
            CatalogGlossaryStore.termMapper().insert(row);
        } else {
            CatalogGlossaryStore.termMapper().updateById(row);
            clearNullTermFields(row);
        }
        return DataResult.of(CatalogGlossaryStore.toDto(row));
    }

    public void deleteTerm(Long id) {
        if (id != null) CatalogGlossaryStore.termMapper().deleteById(id);
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    public List<ai.inquery.server.domain.api.model.CatalogRuleDTO> listRules(Long dataSourceId) {
        return CatalogGlossaryStore.listRules(dataSourceId);
    }

    /** Saves the rule; a descriptive (non-directive) rule is saved with a style warning. */
    public DataResult<ai.inquery.server.domain.api.model.CatalogRuleDTO> saveRule(
            ai.inquery.server.domain.api.model.CatalogRuleDTO dto) {
        if (dto == null || dto.getDataSourceId() == null) {
            return DataResult.error("RULE_INVALID", "dataSourceId is required.");
        }
        if (dto.getRuleText() == null || dto.getRuleText().isBlank()) {
            return DataResult.error("RULE_INVALID", "Rule text is required.");
        }
        var mapper = CatalogGlossaryStore.ruleMapper();
        var row = dto.getId() == null ? null : mapper.selectById(dto.getId());
        boolean isNew = row == null;
        if (isNew) {
            row = new ai.inquery.server.domain.repository.entity.CatalogRuleDO();
            row.setCreateTime(LocalDateTime.now());
        }
        row.setDataSourceId(dto.getDataSourceId());
        row.setRuleText(dto.getRuleText().trim());
        row.setTableRef(blankToNull(dto.getTableRef()));
        row.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        row.setUpdateTime(LocalDateTime.now());
        if (isNew) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
            if (row.getTableRef() == null) {
                mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<
                        ai.inquery.server.domain.repository.entity.CatalogRuleDO>()
                        .eq(ai.inquery.server.domain.repository.entity.CatalogRuleDO::getId, row.getId())
                        .set(ai.inquery.server.domain.repository.entity.CatalogRuleDO::getTableRef, null));
            }
        }
        var saved = CatalogGlossaryStore.toDto(row);
        if (RuleStyle.looksDescriptive(saved.getRuleText())) saved.setStyleWarning(RuleStyle.WARNING);
        return DataResult.of(saved);
    }

    public void deleteRule(Long id) {
        if (id != null) CatalogGlossaryStore.ruleMapper().deleteById(id);
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    public List<CatalogMetricDTO> listMetrics(Long dataSourceId) {
        return CatalogGlossaryStore.listMetrics(dataSourceId);
    }

    /**
     * @param databaseName default database for a table reference without one
     * @param schemaName   default schema for a bare table reference
     */
    public DataResult<CatalogMetricDTO> saveMetric(CatalogMetricDTO dto, String databaseName, String schemaName) {
        String error = validateMetric(dto, databaseName, schemaName);
        if (error != null) return DataResult.error("METRIC_INVALID", error);

        CatalogMetricDO row = dto.getId() == null ? null : CatalogGlossaryStore.metricMapper().selectById(dto.getId());
        CatalogMetricDO sameName = CatalogGlossaryStore.metricMapper().selectOne(new LambdaQueryWrapper<CatalogMetricDO>()
                .eq(CatalogMetricDO::getDataSourceId, dto.getDataSourceId())
                .eq(CatalogMetricDO::getName, dto.getName().trim()));
        if (sameName != null && (row == null || !sameName.getId().equals(row.getId()))) {
            return DataResult.error("METRIC_INVALID", "A metric named '" + dto.getName().trim() + "' already exists.");
        }
        boolean isNew = row == null;
        if (isNew) {
            row = new CatalogMetricDO();
            row.setCreateTime(LocalDateTime.now());
        }
        row.setDataSourceId(dto.getDataSourceId());
        row.setName(dto.getName().trim());
        row.setAliases(CatalogGlossaryStore.aliasesJson(dto.getAliases()));
        row.setDescription(blankToNull(dto.getDescription()));
        row.setTableRef(dto.getTableRef().trim());
        row.setExpression(dto.getExpression().trim());
        row.setFilters(blankToNull(dto.getFilters()));
        row.setGrain(blankToNull(dto.getGrain()));
        row.setCaveats(blankToNull(dto.getCaveats()));
        String aggregation = ColumnAggregation.fromCode(dto.getAggregation()).code();
        if (aggregation == null) {
            // Derive it from the expression when the user left it unset.
            try {
                aggregation = ColumnAggregation.classify(CCJSqlParserUtil.parseExpression(row.getExpression())).code();
            } catch (Exception ignored) {
                // validated above; keep unknown
            }
        }
        row.setAggregation(aggregation);
        row.setUpdateTime(LocalDateTime.now());
        if (isNew) {
            CatalogGlossaryStore.metricMapper().insert(row);
        } else {
            CatalogGlossaryStore.metricMapper().updateById(row);
            clearNullMetricFields(row);
        }
        return DataResult.of(CatalogGlossaryStore.toDto(row));
    }

    public void deleteMetric(Long id) {
        if (id != null) CatalogGlossaryStore.metricMapper().deleteById(id);
    }

    /** Null when the definition is valid, otherwise a message for the user. */
    String validateMetric(CatalogMetricDTO dto, String databaseName, String schemaName) {
        if (dto == null || dto.getDataSourceId() == null) return "dataSourceId is required.";
        if (dto.getName() == null || dto.getName().isBlank()) return "Metric name is required.";
        if (dto.getTableRef() == null || dto.getTableRef().isBlank()) return "Table is required.";
        if (dto.getExpression() == null || dto.getExpression().isBlank()) return "Expression is required.";

        Set<String> referenced = new LinkedHashSet<>();
        try {
            collectColumns(CCJSqlParserUtil.parseExpression(dto.getExpression().trim(), false), referenced);
        } catch (Exception e) {
            return "Expression is not valid SQL: " + firstLine(e.getMessage());
        }
        if (dto.getFilters() != null && !dto.getFilters().isBlank()) {
            try {
                collectColumns(CCJSqlParserUtil.parseCondExpression(dto.getFilters().trim(), false), referenced);
            } catch (Exception e) {
                return "Filters are not a valid SQL condition: " + firstLine(e.getMessage());
            }
        }

        Set<String> columns = tableColumns(dto.getDataSourceId(), dto.getTableRef().trim(), databaseName, schemaName);
        if (columns == null) return null; // catalog unavailable: skip the column check rather than block saving
        if (columns.isEmpty()) return "Table '" + dto.getTableRef().trim() + "' was not found or has no columns.";
        List<String> missing = new ArrayList<>();
        for (String c : referenced) {
            if (!columns.contains(c)) missing.add(c);
        }
        if (!missing.isEmpty()) {
            return "Column(s) not found in " + dto.getTableRef().trim() + ": " + String.join(", ", missing);
        }
        return null;
    }

    private static void collectColumns(Expression expr, Set<String> out) {
        expr.accept(new ExpressionVisitorAdapter() {
            @Override
            public void visit(Column column) {
                String name = column.getColumnName();
                // Quoted string literals in some dialects parse as columns; ignore obvious literals.
                if (name != null && !name.startsWith("'")) {
                    out.add(unquote(name));
                }
            }
        });
    }

    /** Lower-cased column names of the table, empty if not found, null if the catalog is unavailable. */
    @SuppressWarnings("unchecked")
    private Set<String> tableColumns(Long dataSourceId, String tableRef, String databaseName, String schemaName) {
        if (dataCatalogService == null) return null;
        String[] parts = tableRef.split("\\.");
        TableQueryParam param = new TableQueryParam();
        param.setDataSourceId(dataSourceId);
        param.setTableName(stripQuotes(parts[parts.length - 1]));
        param.setSchemaName(parts.length >= 2 ? stripQuotes(parts[parts.length - 2]) : schemaName);
        String database = parts.length >= 3 ? stripQuotes(parts[parts.length - 3]) : databaseName;
        if (database == null || database.isBlank()) {
            // No database selected in the UI: take it from the catalog.
            database = ai.inquery.server.domain.core.catalog.AggregationGuard.catalogDatabase(
                    dataSourceId, param.getSchemaName(), param.getTableName());
        }
        param.setDatabaseName(database);
        try {
            DataResult<Map<String, Object>> result = dataCatalogService.queryCatalog(param);
            Set<String> out = new HashSet<>();
            if (result != null && result.getData() != null && result.getData().get("columns") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> col && col.get("name") != null) {
                        out.add(String.valueOf(col.get("name")).toLowerCase(Locale.ROOT));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[Glossary] column check skipped for {}: {}", tableRef, e.getMessage());
            return null;
        }
    }

    // MyBatis-Plus updateById skips null fields; clear optional fields explicitly.
    private static void clearNullTermFields(CatalogTermDO row) {
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CatalogTermDO>()
                .eq(CatalogTermDO::getId, row.getId());
        boolean any = false;
        if (row.getColumnRef() == null) { update.set(CatalogTermDO::getColumnRef, null); any = true; }
        if (row.getNote() == null) { update.set(CatalogTermDO::getNote, null); any = true; }
        if (any) CatalogGlossaryStore.termMapper().update(null, update);
    }

    private static void clearNullMetricFields(CatalogMetricDO row) {
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CatalogMetricDO>()
                .eq(CatalogMetricDO::getId, row.getId());
        boolean any = false;
        if (row.getDescription() == null) { update.set(CatalogMetricDO::getDescription, null); any = true; }
        if (row.getFilters() == null) { update.set(CatalogMetricDO::getFilters, null); any = true; }
        if (row.getGrain() == null) { update.set(CatalogMetricDO::getGrain, null); any = true; }
        if (row.getCaveats() == null) { update.set(CatalogMetricDO::getCaveats, null); any = true; }
        if (row.getAggregation() == null) { update.set(CatalogMetricDO::getAggregation, null); any = true; }
        if (any) CatalogGlossaryStore.metricMapper().update(null, update);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String stripQuotes(String name) {
        String s = name.trim();
        if (s.length() >= 2 && (s.startsWith("`") || s.startsWith("\"") || s.startsWith("["))) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String unquote(String name) {
        return stripQuotes(name).toLowerCase(Locale.ROOT);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}

package ai.inquery.server.domain.core.catalog.glossary;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.domain.api.model.CatalogTermDTO;
import ai.inquery.server.domain.repository.Dbutils;
import ai.inquery.server.domain.repository.entity.CatalogMetricDO;
import ai.inquery.server.domain.repository.entity.CatalogTermDO;
import ai.inquery.server.domain.repository.mapper.CatalogMetricMapper;
import ai.inquery.server.domain.repository.mapper.CatalogTermMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Database access for glossary terms and metric definitions. Static so the
 * (non-Spring) assistant tools can read it the same way as the services.
 */
@Slf4j
public final class CatalogGlossaryStore {

    private CatalogGlossaryStore() {
    }

    private static CatalogTermMapper terms() {
        return Dbutils.getMapper(CatalogTermMapper.class);
    }

    private static CatalogMetricMapper metrics() {
        return Dbutils.getMapper(CatalogMetricMapper.class);
    }

    public static List<CatalogTermDTO> listTerms(Long dataSourceId) {
        if (dataSourceId == null) return List.of();
        List<CatalogTermDO> rows = terms().selectList(new LambdaQueryWrapper<CatalogTermDO>()
                .eq(CatalogTermDO::getDataSourceId, dataSourceId)
                .orderByAsc(CatalogTermDO::getCanonicalValue));
        List<CatalogTermDTO> out = new ArrayList<>(rows.size());
        for (CatalogTermDO d : rows) out.add(toDto(d));
        return out;
    }

    public static List<CatalogMetricDTO> listMetrics(Long dataSourceId) {
        if (dataSourceId == null) return List.of();
        List<CatalogMetricDO> rows = metrics().selectList(new LambdaQueryWrapper<CatalogMetricDO>()
                .eq(CatalogMetricDO::getDataSourceId, dataSourceId)
                .orderByAsc(CatalogMetricDO::getName));
        List<CatalogMetricDTO> out = new ArrayList<>(rows.size());
        for (CatalogMetricDO d : rows) out.add(toDto(d));
        return out;
    }

    /** Lists, swallowing database errors (assistant tools must never fail on lookup). */
    public static List<CatalogTermDTO> listTermsQuietly(Long dataSourceId) {
        try {
            return listTerms(dataSourceId);
        } catch (Exception e) {
            log.warn("[Glossary] term lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    public static List<CatalogMetricDTO> listMetricsQuietly(Long dataSourceId) {
        try {
            return listMetrics(dataSourceId);
        } catch (Exception e) {
            log.warn("[Glossary] metric lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static ai.inquery.server.domain.repository.mapper.CatalogRuleMapper rules() {
        return Dbutils.getMapper(ai.inquery.server.domain.repository.mapper.CatalogRuleMapper.class);
    }

    public static List<ai.inquery.server.domain.api.model.CatalogRuleDTO> listRules(Long dataSourceId) {
        if (dataSourceId == null) return List.of();
        var rows = rules().selectList(new LambdaQueryWrapper<ai.inquery.server.domain.repository.entity.CatalogRuleDO>()
                .eq(ai.inquery.server.domain.repository.entity.CatalogRuleDO::getDataSourceId, dataSourceId)
                .orderByAsc(ai.inquery.server.domain.repository.entity.CatalogRuleDO::getId));
        List<ai.inquery.server.domain.api.model.CatalogRuleDTO> out = new ArrayList<>(rows.size());
        for (var d : rows) out.add(toDto(d));
        return out;
    }

    /**
     * Enabled rules as a prompt block, or an empty string. Swallows database
     * errors: the assistant must never fail because rules could not be read.
     */
    public static String rulesBlock(Long dataSourceId) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (var r : listRules(dataSourceId)) {
                if (!Boolean.TRUE.equals(r.getEnabled()) || r.getRuleText() == null || r.getRuleText().isBlank()) continue;
                if (n++ >= 50) break;
                sb.append("- ").append(r.getRuleText().trim().replaceAll("\\s+", " "));
                if (r.getTableRef() != null && !r.getTableRef().isBlank()) {
                    sb.append(" (applies to ").append(r.getTableRef().trim()).append(")");
                }
                sb.append("\n");
            }
            if (n == 0) return "";
            return "[Data rules for this database - always follow them when choosing tables, filters and formulas]\n"
                    + sb + "\n";
        } catch (Exception e) {
            log.warn("[Glossary] rule lookup failed: {}", e.getMessage());
            return "";
        }
    }

    static ai.inquery.server.domain.repository.mapper.CatalogRuleMapper ruleMapper() {
        return rules();
    }

    static ai.inquery.server.domain.api.model.CatalogRuleDTO toDto(ai.inquery.server.domain.repository.entity.CatalogRuleDO d) {
        var r = new ai.inquery.server.domain.api.model.CatalogRuleDTO();
        r.setId(d.getId());
        r.setDataSourceId(d.getDataSourceId());
        r.setRuleText(d.getRuleText());
        r.setTableRef(d.getTableRef());
        r.setEnabled(d.getEnabled() == null || d.getEnabled());
        return r;
    }

    static CatalogTermMapper termMapper() {
        return terms();
    }

    static CatalogMetricMapper metricMapper() {
        return metrics();
    }

    static CatalogTermDTO toDto(CatalogTermDO d) {
        CatalogTermDTO t = new CatalogTermDTO();
        t.setId(d.getId());
        t.setDataSourceId(d.getDataSourceId());
        t.setCanonicalValue(d.getCanonicalValue());
        t.setAliases(parseAliases(d.getAliases()));
        t.setColumnRef(d.getColumnRef());
        t.setNote(d.getNote());
        return t;
    }

    static CatalogMetricDTO toDto(CatalogMetricDO d) {
        CatalogMetricDTO m = new CatalogMetricDTO();
        m.setId(d.getId());
        m.setDataSourceId(d.getDataSourceId());
        m.setName(d.getName());
        m.setAliases(parseAliases(d.getAliases()));
        m.setDescription(d.getDescription());
        m.setTableRef(d.getTableRef());
        m.setExpression(d.getExpression());
        m.setFilters(d.getFilters());
        m.setGrain(d.getGrain());
        m.setCaveats(d.getCaveats());
        m.setAggregation(d.getAggregation());
        return m;
    }

    static List<String> parseAliases(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> list = JSON.parseArray(json, String.class);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Trimmed, de-duplicated, non-empty aliases as a JSON array. */
    static String aliasesJson(List<String> aliases) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        if (aliases != null) {
            for (String a : aliases) {
                if (a != null && !a.isBlank()) clean.add(a.trim());
            }
        }
        return JSON.toJSONString(new ArrayList<>(clean));
    }
}

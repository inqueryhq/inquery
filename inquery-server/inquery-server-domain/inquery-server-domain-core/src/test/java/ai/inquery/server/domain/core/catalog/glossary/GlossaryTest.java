package ai.inquery.server.domain.core.catalog.glossary;

import ai.inquery.server.domain.api.model.CatalogMetricDTO;
import ai.inquery.server.domain.api.model.CatalogTermDTO;
import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlossaryTest {

    private static CatalogTermDTO term(String canonical, String... aliases) {
        CatalogTermDTO t = new CatalogTermDTO();
        t.setCanonicalValue(canonical);
        t.setAliases(List.of(aliases));
        t.setColumnRef("shop.products.series");
        return t;
    }

    private static final List<CatalogTermDTO> TERMS = List.of(
            term("Summer Collection 2024", "SC", "SC24", "summer 24"),
            term("Summer Collection 2025", "SC", "SC25"),
            term("Very Important Customer", "VIP"));

    @Test
    void normalizesCaseWidthSpacesAndPunctuation() {
        assertEquals("sc24", GlossaryMatcher.normalize(" S-C 24 "));
        assertEquals("vip", GlossaryMatcher.normalize("\uFF36\uFF29\uFF30")); // full-width letters
    }

    @Test
    void exactAliasWinsAndDoesNotPullShorterAlias() {
        var r = GlossaryMatcher.matchTerms(TERMS, "sc24");
        assertTrue(r.exact());
        assertEquals(1, r.matches().size());
        assertEquals("Summer Collection 2024", r.matches().get(0).getCanonicalValue());
    }

    @Test
    void sharedAliasIsAmbiguous() {
        var r = GlossaryMatcher.matchTerms(TERMS, "SC");
        assertTrue(r.exact());
        assertEquals(2, r.matches().size());
        String out = GlossaryMatcher.formatTermLookup("SC", r);
        assertTrue(out.contains("AMBIGUOUS"), out);
        assertTrue(out.contains("request_clarification"), out);
    }

    @Test
    void partialOnlyWhenNoExact() {
        var r = GlossaryMatcher.matchTerms(TERMS, "Important Customer");
        assertFalse(r.exact());
        assertEquals(1, r.matches().size());
        assertTrue(GlossaryMatcher.formatTermLookup("Important Customer", r).startsWith("TERM_PARTIAL_MATCH"));
        assertTrue(GlossaryMatcher.matchTerms(TERMS, "x").isEmpty(), "single characters never partial-match");
        assertTrue(GlossaryMatcher.formatTermLookup("zzz", GlossaryMatcher.matchTerms(TERMS, "zzz"))
                .startsWith("NO_TERM_FOUND"));
    }

    private static CatalogMetricDTO metric(String name, String table, String expr, String filters) {
        CatalogMetricDTO m = new CatalogMetricDTO();
        m.setDataSourceId(1L);
        m.setName(name);
        m.setAliases(List.of("AOV"));
        m.setTableRef(table);
        m.setExpression(expr);
        m.setFilters(filters);
        return m;
    }

    @Test
    void metricLookupAndFormat() {
        CatalogMetricDTO aov = metric("Average order value", "shop.orders", "SUM(total) / COUNT(*)", "status <> 'cancelled'");
        var r = GlossaryMatcher.matchMetrics(List.of(aov), "aov");
        assertTrue(r.exact());
        String out = GlossaryMatcher.formatMetricLookup("aov", r);
        assertTrue(out.startsWith("OFFICIAL_METRIC"), out);
        assertTrue(out.contains("Filters: status <> 'cancelled'"), out);
    }

    @Test
    void metricValidation() {
        CatalogGlossaryService service = new CatalogGlossaryService();
        DataCatalogService catalog = mock(DataCatalogService.class);
        when(catalog.queryCatalog(any(TableQueryParam.class))).thenAnswer(inv -> {
            TableQueryParam p = inv.getArgument(0);
            if (!"orders".equals(p.getTableName())) return DataResult.of(Map.of("columns", List.of()));
            return DataResult.of(Map.of("columns", List.of(
                    Map.of("name", "total"), Map.of("name", "status"), Map.of("name", "customer_id"))));
        });
        ReflectionTestUtils.setField(service, "dataCatalogService", catalog);

        assertNull(service.validateMetric(metric("aov", "shop.orders", "SUM(total) / COUNT(*)",
                "status <> 'cancelled'"), "db", "shop"));
        assertTrue(service.validateMetric(metric("aov", "shop.orders", "SUM(amount)", null), "db", "shop")
                .contains("amount"));
        assertTrue(service.validateMetric(metric("aov", "shop.orders", "SUM(total", null), "db", "shop")
                .startsWith("Expression is not valid SQL"));
        assertTrue(service.validateMetric(metric("aov", "shop.orders", "SUM(total)", "status ="), "db", "shop")
                .startsWith("Filters are not a valid SQL condition"));
        assertTrue(service.validateMetric(metric("aov", "shop.missing", "SUM(total)", null), "db", "shop")
                .contains("not found"));
        assertTrue(service.validateMetric(metric("aov", "shop.orders", "COUNT(DISTINCT customer_id)",
                "status IN ('paid', 'shipped')"), "db", "shop") == null);
    }
}

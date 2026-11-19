package ai.inquery.server.domain.core.catalog;

import ai.inquery.server.domain.api.param.TableQueryParam;
import ai.inquery.server.domain.api.service.DataCatalogService;
import ai.inquery.server.tools.base.wrapper.result.DataResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DateColumnsUsedTest {

    @Test
    void findsDateColumnsTheQueryUses() {
        DataCatalogService catalog = mock(DataCatalogService.class);
        when(catalog.queryCatalog(any(TableQueryParam.class))).thenReturn(DataResult.of(Map.of("columns", List.of(
                Map.of("name", "month", "columnType", "date"),
                Map.of("name", "created_at", "columnType", "timestamp"),
                Map.of("name", "revenue", "columnType", "numeric")))));
        AggregationGuard guard = new AggregationGuard();
        ReflectionTestUtils.setField(guard, "dataCatalogService", catalog);
        var uses = guard.dateColumnsUsed(
                "SELECT month, SUM(revenue) FROM inquery_sample.public.monthly_revenue WHERE month >= '2026-05-01' GROUP BY month",
                1L, "inquery_sample", "public", 3);
        assertEquals(1, uses.size(), uses.toString());
        assertEquals("month", uses.get(0).column());
        assertEquals("inquery_sample.public.monthly_revenue", uses.get(0).tableInSql());
    }
}

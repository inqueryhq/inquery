package ai.inquery.server.domain.core.catalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnAggregationTest {

    @Test
    void classifiesTopLevelSelect() {
        Map<String, ColumnAggregation> m = ColumnAggregation.fromModelSql(
                "SELECT order_date, region, SUM(amount) AS revenue, COUNT(*) AS orders, "
                        + "COUNT(DISTINCT user_id) AS buyers, SUM(amount) / COUNT(*) AS aov "
                        + "FROM shop.orders GROUP BY order_date, region");
        assertEquals(ColumnAggregation.ADDITIVE, m.get("revenue"));
        assertEquals(ColumnAggregation.ADDITIVE, m.get("orders"));
        assertEquals(ColumnAggregation.DISTINCT, m.get("buyers"));
        assertEquals(ColumnAggregation.NON_ADDITIVE, m.get("aov"));
        assertFalse(m.containsKey("region"), "plain dimension columns stay unknown");
    }

    @Test
    void tracesColumnsThroughCtesAndStarInTemplatedModel() {
        String model = """
                {{ config(materialized='incremental') }}
                -- daily active users per region
                with base as (
                    select event_date, region, user_id, amount
                    from {{ ref('stg_events') }}
                    {% if is_incremental() %} where event_date >= current_date - 3 {% endif %}
                ),
                daily as (
                    select event_date, region,
                           count(distinct user_id) as active_users,
                           sum(amount) as revenue,
                           max(amount) as max_amount
                    from base
                    group by 1, 2
                ),
                final as (
                    select d.*, sum(revenue) over (partition by region order by event_date) as cumulative_revenue
                    from daily d
                )
                select * from final
                """;
        Map<String, ColumnAggregation> m = ColumnAggregation.fromModelSql(model);
        assertEquals(ColumnAggregation.DISTINCT, m.get("active_users"));
        assertEquals(ColumnAggregation.ADDITIVE, m.get("revenue"));
        assertEquals(ColumnAggregation.NON_ADDITIVE, m.get("max_amount"));
        assertEquals(ColumnAggregation.NON_ADDITIVE, m.get("cumulative_revenue"));
    }

    @Test
    void renamedPassThroughKeepsKind() {
        Map<String, ColumnAggregation> m = ColumnAggregation.fromModelSql(
                "with t as (select day, count(distinct uid) as dau from e group by day) "
                        + "select day, dau as daily_users, round(dau * 1.0, 0) as dau_rounded from t");
        assertEquals(ColumnAggregation.DISTINCT, m.get("daily_users"));
        assertEquals(ColumnAggregation.DISTINCT, m.get("dau_rounded"));
    }

    @Test
    void unparseableSqlGivesNothing() {
        assertTrue(ColumnAggregation.fromModelSql("this is not sql").isEmpty());
        assertTrue(ColumnAggregation.fromModelSql(null).isEmpty());
    }

    @Test
    void codesRoundTrip() {
        for (ColumnAggregation a : ColumnAggregation.values()) {
            assertEquals(a, ColumnAggregation.fromCode(a.code()));
        }
        assertEquals(ColumnAggregation.UNKNOWN, ColumnAggregation.fromCode("bogus"));
        assertTrue(ColumnAggregation.DISTINCT.isNonSummable());
        assertFalse(ColumnAggregation.ADDITIVE.isNonSummable());
    }
}

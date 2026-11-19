package ai.inquery.server.domain.core.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AggregationGuardTest {

    /** daily_users(day DATE, region TEXT, active_users distinct, revenue additive, conversion non_additive). */
    private static final AggregationGuard.TableShape DAILY = new AggregationGuard.TableShape(
            Map.of("active_users", ColumnAggregation.DISTINCT,
                    "revenue", ColumnAggregation.ADDITIVE,
                    "conversion", ColumnAggregation.NON_ADDITIVE),
            Set.of("day", "region"));

    private static final Function<String, AggregationGuard.TableShape> LOOKUP =
            t -> t.endsWith("daily_users") ? DAILY : AggregationGuard.TableShape.empty();

    private static List<String> check(String sql) {
        return AggregationGuard.check(sql, LOOKUP);
    }

    @Test
    void rejectsDistinctSummedAcrossDates() {
        List<String> v = check("SELECT region, SUM(active_users) FROM analytics.daily_users GROUP BY region");
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("`active_users`") && v.get(0).contains("day"), v.get(0));
    }

    @Test
    void rejectsMonthlyRollUpOfDailyDistinct() {
        assertEquals(1, check("SELECT DATE_TRUNC('month', day) AS m, region, SUM(active_users) AS mau "
                + "FROM daily_users GROUP BY 1, 2").size());
    }

    @Test
    void rejectsCollapsingRegionsWithinOneDay() {
        List<String> v = check("SELECT day, SUM(active_users) FROM daily_users GROUP BY day");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("region"), v.get(0));
    }

    @Test
    void allowsFullGrainOrPinnedValues() {
        assertTrue(check("SELECT day, region, SUM(active_users) FROM daily_users GROUP BY day, region").isEmpty());
        assertTrue(check("SELECT d.day, SUM(d.active_users) FROM daily_users d "
                + "WHERE d.region = 'north' GROUP BY d.day").isEmpty());
        assertTrue(check("SELECT SUM(active_users) FROM daily_users "
                + "WHERE day = DATE '2025-01-01' AND region IN ('north')").isEmpty());
        assertTrue(check("SELECT day AS d, region AS r, SUM(active_users) FROM daily_users GROUP BY d, r").isEmpty());
    }

    @Test
    void allowsAdditiveColumnsAnywhere() {
        assertTrue(check("SELECT SUM(revenue) FROM daily_users").isEmpty());
    }

    @Test
    void allowsAverageForms() {
        assertTrue(check("SELECT region, SUM(active_users) / COUNT(DISTINCT day) AS avg_daily "
                + "FROM daily_users GROUP BY region").isEmpty());
        assertTrue(check("SELECT region, SAFE_DIVIDE(SUM(active_users), COUNT(DISTINCT day)) "
                + "FROM daily_users GROUP BY region").isEmpty());
        assertTrue(check("SELECT AVG(active_users) FROM daily_users").isEmpty());
    }

    @Test
    void rejectsRatioSumAndWindowSum() {
        assertEquals(1, check("SELECT SUM(conversion) FROM daily_users").size());
        assertEquals(1, check("SELECT day, region, SUM(active_users) OVER (PARTITION BY region ORDER BY day) "
                + "FROM daily_users").size());
    }

    @Test
    void checksInsideCtesWithoutOverride() {
        String sql = "WITH t AS (SELECT region, SUM(COALESCE(active_users, 0)) AS u FROM daily_users GROUP BY region) "
                + "SELECT * FROM t";
        assertEquals(1, check(sql).size());
        // No comment can switch the guard off.
        assertEquals(1, check("-- additive-ok\n" + sql).size());
    }

    @Test
    void unknownTablesAndUnparseableSqlPass() {
        assertTrue(check("SELECT SUM(active_users) FROM other_table").isEmpty());
        assertTrue(check("SELEC broken sql").isEmpty());
    }

    @Test
    void outputKindsForPlainColumns() {
        Map<String, ColumnAggregation> kinds = AggregationGuard.outputColumnKinds(
                "SELECT day, active_users AS users, revenue FROM daily_users", LOOKUP);
        assertEquals(ColumnAggregation.DISTINCT, kinds.get("users"));
        assertEquals(ColumnAggregation.ADDITIVE, kinds.get("revenue"));
        assertTrue(!kinds.containsKey("day"));
    }

    @Test
    void grainTypes() {
        assertTrue(AggregationGuard.isGrainType("DATE"));
        assertTrue(AggregationGuard.isGrainType("character varying"));
        assertTrue(!AggregationGuard.isGrainType("bigint"));
        assertTrue(!AggregationGuard.isGrainType("STRUCT<a INT64>"));
    }
}

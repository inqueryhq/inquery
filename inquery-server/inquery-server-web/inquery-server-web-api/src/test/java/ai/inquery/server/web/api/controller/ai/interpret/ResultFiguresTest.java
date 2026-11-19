package ai.inquery.server.web.api.controller.ai.interpret;

import ai.inquery.server.domain.core.catalog.ColumnAggregation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFiguresTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void classifiesSelectExpressions() {
        assertEquals(ColumnAggregation.ADDITIVE, kindOf("SELECT SUM(amount) AS revenue FROM orders", "revenue"));
        assertEquals(ColumnAggregation.ADDITIVE, kindOf("SELECT COUNT(*) AS orders FROM orders", "orders"));
        assertEquals(ColumnAggregation.ADDITIVE, kindOf("SELECT ROUND(SUM(amount) * 1.1, 2) AS gross FROM orders", "gross"));
        assertEquals(ColumnAggregation.DISTINCT, kindOf("SELECT COUNT(DISTINCT user_id) AS buyers FROM orders", "buyers"));
        assertEquals(ColumnAggregation.NON_ADDITIVE, kindOf("SELECT AVG(amount) AS aov FROM orders", "aov"));
        assertEquals(ColumnAggregation.NON_ADDITIVE, kindOf("SELECT SUM(a) / SUM(b) AS rate FROM t", "rate"));
        assertEquals(ColumnAggregation.NON_ADDITIVE,
                kindOf("SELECT SUM(amount) OVER (ORDER BY day) AS running FROM daily", "running"));
        assertEquals(ColumnAggregation.UNKNOWN, kindOf("SELECT day, active_users FROM daily", "active_users"));
    }

    private static ColumnAggregation kindOf(String sql, String column) {
        return ResultFigures.analyzeSql(sql).kinds.getOrDefault(column, ColumnAggregation.UNKNOWN);
    }

    @Test
    void computesTotalsSharesAndSubtotals() {
        String sql = "SELECT region, channel, SUM(amount) AS revenue, COUNT(DISTINCT user_id) AS buyers "
                + "FROM orders GROUP BY region, channel";
        List<Map<String, Object>> rows = List.of(
                row("region", "North", "channel", "web", "revenue", "600", "buyers", "30"),
                row("region", "North", "channel", "app", "revenue", "200", "buyers", "20"),
                row("region", "South", "channel", "web", "revenue", "150", "buyers", "10"),
                row("region", "South", "channel", "app", "revenue", "50", "buyers", "5"));

        ResultFigures.Summary s = ResultFigures.compute(rows, sql, 4);
        String block = s.block();

        assertTrue(block.contains("revenue [additive"), block);
        assertTrue(block.contains("total 1,000"), block);
        assertTrue(block.contains("North / web: 60.00%"), block);
        assertTrue(block.contains("Subtotal of revenue by region"), block);
        assertTrue(block.contains("- North: 800 (80.00% of total)"), block);
        // Distinct counts get no total.
        assertTrue(block.contains("buyers [distinct count"), block);
        assertFalse(block.contains("total 65"), block);
    }

    @Test
    void computesFirstToLastChangeForTimeSeries() {
        String sql = "SELECT day, SUM(amount) AS revenue FROM orders GROUP BY day";
        List<Map<String, Object>> rows = List.of(
                row("day", "2025-01-03", "revenue", 150),
                row("day", "2025-01-01", "revenue", 100),
                row("day", "2025-01-02", "revenue", 120));

        String block = ResultFigures.compute(rows, sql, 3).block();
        assertTrue(block.contains("Change from 2025-01-01 to 2025-01-03"), block);
        assertTrue(block.contains("100 -> 150, change +50 (+50.00%)"), block);
    }

    @Test
    void notesWhenFiguresCoverOnlyPartOfTheResult() {
        List<Map<String, Object>> rows = List.of(row("k", "a", "n", 1), row("k", "b", "n", 2));
        String block = ResultFigures.compute(rows, "SELECT k, COUNT(*) AS n FROM t GROUP BY k", 10).block();
        assertTrue(block.contains("the query returned 10 rows"), block);
    }

    @Test
    void flagsOnlyFiguresMissingFromResultAndPrecomputed() {
        String sql = "SELECT region, SUM(amount) AS revenue FROM orders GROUP BY region";
        List<Map<String, Object>> rows = List.of(
                row("region", "North", "revenue", "414983394"),
                row("region", "South", "revenue", "85016606"));
        ResultFigures.Summary s = ResultFigures.compute(rows, sql, 2);

        String answer = "North leads with 414,983,394 (83.00% of the 500,000,000 total), "
                + "about 4.9x South's 85,016,606. Combined revenue was 394,883,394.";
        List<String> unverified = ResultFigures.findUnverifiedFigures(answer, rows, s, "revenue by region", sql);

        assertEquals(List.of("394,883,394"), unverified);
    }

    @Test
    void readsEastAsianUnitsAndSkipsDates() {
        String sql = "SELECT SUM(amount) AS revenue FROM orders";
        List<Map<String, Object>> rows = List.of(row("revenue", "345000000"));
        ResultFigures.Summary s = ResultFigures.compute(rows, sql, 1);

        // "3 x 10^8 4,500 x 10^4" and "34,500 x 10^4" both equal 345,000,000.
        String answer = "2025-01-31 \uae30\uc900 3\uc5b5 4,500\ub9cc, 34,500\ub9cc";
        assertTrue(ResultFigures.findUnverifiedFigures(answer, rows, s, "", sql).isEmpty());

        String wrong = "3\uc5b5 5,000\ub9cc";
        assertEquals(1, ResultFigures.findUnverifiedFigures(wrong, rows, s, "", sql).size());
    }

    @Test
    void ranksSharesWithRunningTopNTotals() {
        String sql = "SELECT category, SUM(amount) AS revenue FROM orders GROUP BY category";
        List<Map<String, Object>> rows = List.of(
                row("category", "Books", "revenue", 100),
                row("category", "Toys", "revenue", 300),
                row("category", "Home", "revenue", 200),
                row("category", "Food", "revenue", 400));
        ResultFigures.Summary s = ResultFigures.compute(rows, sql, 4);
        String block = s.block();

        assertTrue(block.contains("- Food: 40.00%\n- Toys: 30.00% | top 2: 700 = 70.00%\n"
                + "- Home: 20.00% | top 3: 900 = 90.00%\n- Books: 10.00%\n"), block);
        // A top-3 share quoted from the block is not flagged.
        assertTrue(ResultFigures.findUnverifiedFigures(
                "The top three categories make up 90.00% (900).", rows, s, "", sql).isEmpty());
    }

    @Test
    void timeSeriesSharesKeepRowOrder() {
        String sql = "SELECT day, SUM(amount) AS revenue FROM orders GROUP BY day";
        List<Map<String, Object>> rows = List.of(
                row("day", "2025-01-01", "revenue", 100),
                row("day", "2025-01-02", "revenue", 300));
        String block = ResultFigures.compute(rows, sql, 2).block();
        assertTrue(block.contains("- 2025-01-01: 25.00%\n- 2025-01-02: 75.00%\n"), block);
        assertFalse(block.contains("top 2"), block);
    }

    @Test
    void skipsFigureCheckForModelErrors() {
        assertTrue(ResultFigures.findUnverifiedFigures(
                "Error: 400 . API key not valid.", List.of(), null, "", "").isEmpty());
    }

    @Test
    void unparseableSqlStillGivesBasicStats() {
        List<Map<String, Object>> rows = List.of(row("v", 1.5), row("v", 2.5));
        String block = ResultFigures.compute(rows, "NOT SQL AT ALL", 2).block();
        assertTrue(block.contains("v [stored value"), block);
        assertTrue(block.contains("average per row 2"), block);
        assertFalse(block.contains("total 4"), block);
    }
}

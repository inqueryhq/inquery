package ai.inquery.server.domain.core.catalog;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshnessNotesTest {

    private static final List<FreshnessNotes.DataThrough> ORDERS =
            List.of(new FreshnessNotes.DataThrough("public.orders", "order_date", LocalDate.parse("2026-11-13")));

    @Test
    void flagsPartialLastMonth() {
        List<List<String>> rows = List.of(List.of("2026-09-01", "120"), List.of("2026-10-01", "130"),
                List.of("2026-11-01", "40"));
        List<String> notes = FreshnessNotes.notes("SELECT DATE_TRUNC('month', order_date) AS m, COUNT(*) FROM orders GROUP BY 1",
                ORDERS, List.of("m", "orders"), rows);
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(notes.get(0).contains("2026-11") && notes.get(0).contains("13 of 30 days"), notes.get(0));
    }

    @Test
    void flagsPartialMonthWrittenAsYearMonthText() {
        List<List<String>> rows = List.of(List.of("2026-10", "1"), List.of("2026-11", "2"));
        assertTrue(FreshnessNotes.partialLastPeriod(List.of("month", "n"), rows, LocalDate.parse("2026-11-13"), LocalDate.parse("2026-11-18"))
                .contains("2026-11"));
    }

    @Test
    void completeLastPeriodAndDailySeriesGiveNoNote() {
        List<List<String>> months = List.of(List.of("2026-09-01"), List.of("2026-10-01"));
        assertNull(FreshnessNotes.partialLastPeriod(List.of("m"), months, LocalDate.parse("2026-11-13"), LocalDate.parse("2026-11-18")));
        List<List<String>> days = List.of(List.of("2026-11-12"), List.of("2026-11-13"));
        assertNull(FreshnessNotes.partialLastPeriod(List.of("d"), days, LocalDate.parse("2026-11-13"), LocalDate.parse("2026-11-18")));
    }

    @Test
    void detectsWeekQuarterAndYearGrains() {
        TreeSet<LocalDate> weeks = new TreeSet<>(List.of(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-09")));
        assertEquals(FreshnessNotes.Grain.WEEK, FreshnessNotes.grainOf(weeks));
        TreeSet<LocalDate> quarters = new TreeSet<>(List.of(LocalDate.parse("2026-04-01"), LocalDate.parse("2026-07-01")));
        assertEquals(FreshnessNotes.Grain.QUARTER, FreshnessNotes.grainOf(quarters));
        TreeSet<LocalDate> years = new TreeSet<>(List.of(LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-01")));
        assertEquals(FreshnessNotes.Grain.YEAR, FreshnessNotes.grainOf(years));
    }

    @Test
    void periodStartColumnsAreJudgedByToday() {
        // A monthly table: its latest value is the first day of its latest month.
        List<FreshnessNotes.DataThrough> monthly =
                List.of(new FreshnessNotes.DataThrough("public.monthly_revenue", "month", LocalDate.parse("2026-11-01")));
        List<List<String>> rows = List.of(List.of("1", "2026-10-01", "5"), List.of("2", "2026-11-01", "2"));
        List<String> headers = List.of("Row Number", "month", "orders");
        List<String> inProgress = FreshnessNotes.notes("SELECT month, total_orders FROM monthly_revenue",
                monthly, headers, rows, LocalDate.parse("2026-11-18"));
        assertEquals(1, inProgress.size());
        assertTrue(inProgress.get(0).contains("still in progress"), inProgress.get(0));
        // The same rows viewed in December: November is complete.
        assertTrue(FreshnessNotes.notes("SELECT month, total_orders FROM monthly_revenue",
                monthly, headers, rows, LocalDate.parse("2026-12-05")).isEmpty());
    }

    @Test
    void flagsRangePastTheData() {
        String sql = "SELECT COUNT(*) FROM orders WHERE order_date >= '2026-11-01' AND order_date <= '2026-11-30'";
        List<String> notes = FreshnessNotes.notes(sql, ORDERS, List.of("n"), List.of(List.of("40")));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("runs through 2026-11-13") && notes.get(0).contains("2026-11-30"), notes.get(0));

        assertEquals(LocalDate.parse("2026-11-30"),
                FreshnessNotes.upperBound("WHERE o.order_date < DATE '2026-12-01'", "order_date"));
        assertEquals(LocalDate.parse("2026-12-31"),
                FreshnessNotes.upperBound("WHERE order_date BETWEEN '2026-01-01' AND '2026-12-31'", "order_date"));
        assertNull(FreshnessNotes.upperBound("WHERE order_date >= '2026-01-01'", "order_date"));
    }

    @Test
    void rangeInsideTheDataGivesNoNote() {
        String sql = "SELECT COUNT(*) FROM orders WHERE order_date BETWEEN '2026-10-01' AND '2026-10-31'";
        assertTrue(FreshnessNotes.notes(sql, ORDERS, List.of("n"), List.of(List.of("1"))).isEmpty());
    }
}

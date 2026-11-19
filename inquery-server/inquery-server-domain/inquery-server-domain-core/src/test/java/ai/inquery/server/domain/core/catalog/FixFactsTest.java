package ai.inquery.server.domain.core.catalog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixFactsTest {

    @Test
    void tablesFromValidSql() {
        assertEquals(List.of("shop.orders", "shop.customers"), FixFacts.referencedTables(
                "SELECT c.name, COUNT(*) FROM shop.orders o JOIN shop.customers c ON c.id = o.customer_id GROUP BY 1"));
    }

    @Test
    void tablesFromBrokenSqlSkipCtes() {
        // Unbalanced parenthesis: does not parse, so the FROM/JOIN scan is used.
        List<String> tables = FixFacts.referencedTables(
                "WITH recent AS (SELECT * FROM shop.orders WHERE order_date >= '2026-01-01' "
                        + "SELECT r.id FROM recent r JOIN `proj.shop.order_lines` l ON l.order_id = r.id WHERE (");
        assertEquals(List.of("shop.orders", "`proj.shop.order_lines`"), tables);
    }

    @Test
    void nearestNamesRankTheLikelyTarget() {
        List<String> known = List.of("public.orders", "public.order_items", "public.customers", "public.products",
                "public.monthly_revenue");
        List<String> near = FixFacts.nearest("public.order_lines", known, 3);
        assertEquals("public.order_items", near.get(0), near.toString());
        assertTrue(near.contains("public.orders"), near.toString());
        assertTrue(FixFacts.nearest("zzz", known, 3).isEmpty());
    }

    @Test
    void namesTheMissingColumnFromCommonErrors() {
        assertEquals("category", FixFacts.missingColumn("ERROR: column \"category\" does not exist at character 18"));
        assertEquals("category", FixFacts.missingColumn("ERROR: column p.category does not exist"));
        assertEquals("dt", FixFacts.missingColumn("Unrecognized name: dt at [3:5]"));
        assertEquals("amount", FixFacts.missingColumn("Unknown column 'o.amount' in 'field list'"));
        assertEquals(null, FixFacts.missingColumn("syntax error at or near FROM"));

        Map<String, Map<String, String>> cols = new LinkedHashMap<>();
        cols.put("public.products", new LinkedHashMap<>(Map.of("product_category", "varchar")));
        String text = FixFacts.format(cols, Map.of(), "column \"category\" does not exist");
        assertTrue(text.contains("`category` is not a column of any table above") && text.contains("product_category"), text);
    }

    @Test
    void formatsColumnsAndMissingTables() {
        Map<String, Map<String, String>> cols = new LinkedHashMap<>();
        cols.put("public.orders", new LinkedHashMap<>(Map.of("id", "integer")));
        cols.put("public.order_lines", Map.of());
        String text = FixFacts.format(cols, Map.of("public.order_lines", List.of("public.order_items")));
        assertTrue(text.contains("public.orders columns: id integer"), text);
        assertTrue(text.contains("public.order_lines: DOES NOT EXIST. Closest existing tables: public.order_items."), text);
        assertTrue(text.contains("Never invent"), text);
    }
}

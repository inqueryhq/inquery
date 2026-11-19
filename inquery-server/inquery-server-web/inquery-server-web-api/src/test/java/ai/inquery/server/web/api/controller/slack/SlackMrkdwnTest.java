package ai.inquery.server.web.api.controller.slack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlackMrkdwnTest {

    @Test
    void headingRuleBoldAndBullets() {
        String md = """
                ---
                ## 📊 October Revenue by Category

                **Explanation:**
                - `p.category`: Groups revenue by **category**.
                * second item
                """;
        String out = SlackMrkdwn.convert(md);
        assertEquals("""
                *📊 October Revenue by Category*

                *Explanation:*
                • `p.category`: Groups revenue by *category*.
                • second item""", out);
    }

    @Test
    void codeFenceDropsLanguageAndKeepsContent() {
        String md = "```sql\nSELECT **x** FROM t\n-- a\n```\n";
        assertEquals("```\nSELECT **x** FROM t\n-- a\n```", SlackMrkdwn.convert(md));
    }

    @Test
    void italicLinkAndStrike() {
        assertEquals("_note_ see <https://example.com|docs> ~old~",
                SlackMrkdwn.convert("*note* see [docs](https://example.com) ~~old~~"));
    }

    @Test
    void inlineCodeIsLeftAlone() {
        assertEquals("use `**raw**` here", SlackMrkdwn.convert("use `**raw**` here"));
    }

    @Test
    void tableBecomesAlignedBlock() {
        String md = """
                | Category | Revenue |
                |---|---:|
                | Electronics | 5,924.64 |
                | **Books** | 334.94 |
                """;
        String out = SlackMrkdwn.convert(md);
        assertTrue(out.startsWith("```\nCategory     Revenue\n"));
        assertTrue(out.contains("Electronics  5,924.64"));
        assertTrue(out.contains("Books        334.94"));
        assertFalse(out.contains("|"));
        assertTrue(out.endsWith("```"));
    }
}

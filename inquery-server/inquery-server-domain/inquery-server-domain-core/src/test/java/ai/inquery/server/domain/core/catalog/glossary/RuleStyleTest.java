package ai.inquery.server.domain.core.catalog.glossary;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleStyleTest {

    @Test
    void instructionsPass() {
        assertFalse(RuleStyle.looksDescriptive("Never count orders with status 'pending'."));
        assertFalse(RuleStyle.looksDescriptive("Always filter status IN ('delivered', 'shipped')."));
        assertFalse(RuleStyle.looksDescriptive("Use fact_orders instead of the raw events table."));
        // Korean: "must filter by ..." / "do not use LIKE"
        assertFalse(RuleStyle.looksDescriptive("\ubc18\ub4dc\uc2dc status\ub85c \ud544\ud130\ud558\ub77c"));
        assertFalse(RuleStyle.looksDescriptive("LIKE\ub97c \uc4f0\uc9c0 \ub9d0 \uac83"));
    }

    @Test
    void factsAreFlagged() {
        assertTrue(RuleStyle.looksDescriptive("The summer line consists of Sandals, Hats and Swimwear."));
        assertTrue(RuleStyle.looksDescriptive("Revenue is stored in cents."));
        assertFalse(RuleStyle.looksDescriptive(""));
    }
}

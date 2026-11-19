package ai.inquery.server.web.api.controller.ai;

import ai.inquery.server.domain.api.param.QueryRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlierQueriesTest {

    private static QueryRequest.ConversationMessage msg(String role, String content, String sql, String digest) {
        QueryRequest.ConversationMessage m = new QueryRequest.ConversationMessage();
        m.setRole(role);
        m.setContent(content);
        m.setGeneratedSql(sql);
        m.setResultDigest(digest);
        return m;
    }

    @Test
    void keepsTheLastTwoDataTurnsInOrder() {
        List<QueryRequest.ConversationMessage> h = new ArrayList<>();
        h.add(msg("user", "orders by city in August", null, null));
        h.add(msg("assistant", "...", "SELECT city, COUNT(*) FROM orders WHERE m = 8 GROUP BY city", "2 row(s)"));
        h.add(msg("user", "hello", null, null));
        h.add(msg("assistant", "Hi!", null, null));
        h.add(msg("user", "orders by city in September", null, null));
        h.add(msg("assistant", "...", "SELECT city, COUNT(*) FROM orders WHERE m = 9 GROUP BY city", "3 row(s)\ncity | n\nParis | 2"));
        h.add(msg("user", "orders by city in October", null, null));
        h.add(msg("assistant", "...", "SELECT city, COUNT(*) FROM orders WHERE m = 10 GROUP BY city", null));

        String block = ChatController.earlierQueries(h);
        assertTrue(block.startsWith("Earlier queries in this conversation"), block);
        assertTrue(block.indexOf("m = 9") < block.indexOf("m = 10"), block);
        assertTrue(!block.contains("m = 8"), block);
        assertTrue(block.contains("-- Question: orders by city in September"), block);
        assertTrue(block.contains("-- Result: 3 row(s)\n--   city | n\n--   Paris | 2"), block);
    }

    @Test
    void emptyWithoutDataTurns() {
        assertEquals("", ChatController.earlierQueries(List.of(msg("user", "hi", null, null), msg("assistant", "hello", null, null))));
        assertEquals("", ChatController.earlierQueries(null));
    }
}

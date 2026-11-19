package ai.inquery.server.web.api.controller.ai;

import ai.inquery.server.domain.api.service.AIService;
import ai.inquery.server.web.api.controller.ai.interpret.ResultFigures;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifyAndCorrectTest {

    private static final String SQL = "SELECT region, SUM(amount) AS revenue FROM orders GROUP BY region";
    private static final List<Map<String, Object>> ROWS = List.of(row("North", 400), row("South", 100));

    private static Map<String, Object> row(String region, int revenue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("region", region);
        m.put("revenue", revenue);
        return m;
    }

    private static ChatController controllerAnswering(String verifierReply, AIService[] holder) {
        ChatController c = new ChatController();
        AIService ai = mock(AIService.class);
        when(ai.generate(anyString(), anyString())).thenReturn(verifierReply);
        ReflectionTestUtils.setField(c, "aiService", ai);
        holder[0] = ai;
        return c;
    }

    private static ChatController.VerifyOutcome run(ChatController c, String answer) {
        ResultFigures.Summary figures = ResultFigures.compute(ROWS, SQL, 2);
        List<String> flagged = ResultFigures.findUnverifiedFigures(answer, ROWS, figures, "revenue by region", SQL);
        return c.verifyAndCorrect("revenue by region", SQL, ROWS, figures, ROWS, null, answer, flagged, "m");
    }

    @Test
    void skipsTheModelWhenEveryFigureIsSupported() {
        AIService[] ai = new AIService[1];
        ChatController c = controllerAnswering("unused", ai);
        ChatController.VerifyOutcome out = run(c, "North had 400 and South 100, 500 in total.");
        assertEquals("skipped", out.status());
        verify(ai[0], never()).generate(anyString(), anyString());
    }

    @Test
    void adoptsACorrectionThatRemovesUnsupportedFigures() {
        AIService[] ai = new AIService[1];
        ChatController c = controllerAnswering("North had 400 and South 100, 500 in total.", ai);
        ChatController.VerifyOutcome out = run(c, "North had 400 and South 100, 650 in total.");
        assertEquals("corrected", out.status());
        assertTrue(out.interpretation().startsWith("North had 400 and South 100, 500 in total."), out.interpretation());
        assertTrue(out.interpretation().contains("1 figure that the result does not support was corrected"), out.interpretation());
        assertEquals("North had 400 and South 100, 650 in total.", out.original());
        assertTrue(out.unverified().isEmpty());
    }

    @Test
    void keepsTheOriginalWhenTheCorrectionIsNoBetter() {
        AIService[] ai = new AIService[1];
        ChatController c = controllerAnswering("North had 400 and South 100, 700 in total.", ai);
        ChatController.VerifyOutcome out = run(c, "North had 400 and South 100, 650 in total.");
        assertEquals("unchanged", out.status());
        assertEquals("North had 400 and South 100, 650 in total.", out.interpretation());
        assertNull(out.original());
    }
}

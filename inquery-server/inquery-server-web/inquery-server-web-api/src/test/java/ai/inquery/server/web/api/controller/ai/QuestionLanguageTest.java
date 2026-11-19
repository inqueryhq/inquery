package ai.inquery.server.web.api.controller.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuestionLanguageTest {

    @Test
    void namesTheQuestionLanguageFromItsScript() {
        assertEquals("English", ChatController.questionLanguage("How many unique customers did we have in 2025?"));
        assertEquals("Korean", ChatController.questionLanguage("\uc6d4\ubcc4 AOV \ucd94\uc774"));
        assertEquals("Japanese", ChatController.questionLanguage("\u58f2\u4e0a\u306e\u63a8\u79fb"));
        assertEquals("Chinese", ChatController.questionLanguage("\u6bcf\u6708\u9500\u552e\u989d"));
        assertEquals("Turkish", ChatController.questionLanguage("Ayl\u0131k sat\u0131\u015f"));
        assertNull(ChatController.questionLanguage("123 456"));
        assertNull(ChatController.questionLanguage("\u0412\u044b\u0440\u0443\u0447\u043a\u0430 \u043f\u043e \u043c\u0435\u0441\u044f\u0446\u0430\u043c"));
    }
}

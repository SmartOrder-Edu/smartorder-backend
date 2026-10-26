package com.smartorder.edu.agent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证恢复游标的数值顺序与非法输入保护。 */
class AgentStreamCursorTest {
    @Test
    void comparesNumericParts() {
        assertTrue(AgentStreamCursor.compare("1-2", "1-10") < 0);
        assertTrue(AgentStreamCursor.compare("10-0", "2-99") > 0);
        assertThrows(IllegalArgumentException.class, () -> AgentStreamCursor.compare("1-x", "0-0"));
    }

    @Test
    void lastEventIdOverridesUrlCursor() {
        assertEquals("5-0", AgentStreamCursor.choose("5-0", "3-0"));
        assertEquals("3-0", AgentStreamCursor.choose(null, "3-0"));
    }
}

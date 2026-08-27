package com.enterprise.orchestrator.orchestrator;

import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.AgentRole;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HandoffResultTest {

    @Test
    void terminal_isTerminal() {
        HandoffResult result = HandoffResult.terminal(AgentRole.HISTORY, "done", Map.of());
        assertTrue(result.isTerminal());
        assertNull(result.nextRecommended());
        assertTrue(result.success());
    }

    @Test
    void handoffTo_isNotTerminal() {
        HandoffResult result = HandoffResult.handoffTo(
                AgentRole.HISTORY, AgentRole.DESIGNER, "partial", Map.of());
        assertFalse(result.isTerminal());
        assertEquals(AgentRole.DESIGNER, result.nextRecommended());
    }
}

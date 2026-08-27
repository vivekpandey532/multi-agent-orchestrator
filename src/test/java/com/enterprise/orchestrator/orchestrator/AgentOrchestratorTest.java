package com.enterprise.orchestrator.orchestrator;

import com.enterprise.orchestrator.agent.BaseAgent;
import com.enterprise.orchestrator.blackboard.SharedBlackboard;
import com.enterprise.orchestrator.config.OrchestratorProperties;
import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.*;
import com.enterprise.orchestrator.tools.McpTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {

    private SharedBlackboard blackboard;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        blackboard = new SharedBlackboard();

        // Stub Manager: returns a fixed decomposition
        BaseAgent manager = stubAgent(AgentRole.MANAGER, "HISTORY: Research the topic\nDESIGNER: Create architecture");
        // Stub History: returns terminal result
        BaseAgent history = stubAgent(AgentRole.HISTORY, "Historical context gathered.");
        // Stub Designer: returns terminal result
        BaseAgent designer = stubAgent(AgentRole.DESIGNER, "Design proposal ready.");

        orchestrator = new AgentOrchestrator(
                List.of(manager, history, designer),
                blackboard,
                Executors.newVirtualThreadPerTaskExecutor(),
                new OrchestratorProperties(10, 30, 5),
                new SimpleMeterRegistry());
    }

    @Test
    void orchestrate_decomposesAndExecutesSubTasks() {
        OrchestrationRequest request = new OrchestrationRequest("Build a dashboard", Map.of());
        OrchestrationResponse response = orchestrator.orchestrate(request);

        assertNotNull(response.requestId());
        assertFalse(response.finalAnswer().isBlank());
        assertTrue(response.steps().size() >= 2, "Should have at least manager + worker steps");
    }

    @Test
    void parseSubTasks_parsesRolePrefixedLines() {
        String output = "HISTORY: Do research\nDESIGNER: Build wireframe\nINVALID LINE WITHOUT COLON";
        List<Task> tasks = orchestrator.parseSubTasks("parent-1", output);

        assertEquals(2, tasks.size());
        assertTrue(tasks.get(0).description().startsWith("HISTORY:"));
        assertTrue(tasks.get(1).description().startsWith("DESIGNER:"));
    }

    @Test
    void inferRole_matchesKnownRoles() {
        assertEquals(AgentRole.HISTORY, orchestrator.inferRole("HISTORY: some task"));
        assertEquals(AgentRole.DESIGNER, orchestrator.inferRole("DESIGNER: another task"));
        assertEquals(AgentRole.MANAGER, orchestrator.inferRole("UNKNOWN: fallback"));
    }

    @Test
    void orchestrate_handoffChainWorks() {
        // History agent that hands off to Designer
        BaseAgent historyWithHandoff = new BaseAgent() {
            @Override public AgentRole role() { return AgentRole.HISTORY; }
            @Override public String goal() { return "test"; }
            @Override public List<McpTool> tools() { return List.of(); }
            @Override public HandoffResult execute(Task task) {
                return HandoffResult.handoffTo(AgentRole.HISTORY, AgentRole.DESIGNER,
                        "History done, needs design", Map.of("chain", true));
            }
        };

        BaseAgent manager = stubAgent(AgentRole.MANAGER, "HISTORY: Research topic");
        BaseAgent designer = stubAgent(AgentRole.DESIGNER, "Design complete after handoff.");

        AgentOrchestrator chainOrchestrator = new AgentOrchestrator(
                List.of(manager, historyWithHandoff, designer),
                new SharedBlackboard(),
                Executors.newVirtualThreadPerTaskExecutor(),
                new OrchestratorProperties(10, 30, 5),
                new SimpleMeterRegistry());

        OrchestrationResponse response = chainOrchestrator.orchestrate(
                new OrchestrationRequest("Test handoff", Map.of()));

        assertTrue(response.finalAnswer().contains("Design complete after handoff"));
    }

    @Test
    void orchestrate_missingAgentThrows() {
        // Only register Manager — no workers
        BaseAgent manager = stubAgent(AgentRole.MANAGER, "HISTORY: Do something");

        AgentOrchestrator incomplete = new AgentOrchestrator(
                List.of(manager),
                new SharedBlackboard(),
                Executors.newVirtualThreadPerTaskExecutor(),
                new OrchestratorProperties(10, 30, 5),
                new SimpleMeterRegistry());

        assertThrows(AgentOrchestrator.OrchestratorException.class,
                () -> incomplete.orchestrate(new OrchestrationRequest("test", Map.of())));
    }

    private static BaseAgent stubAgent(AgentRole role, String fixedOutput) {
        return new BaseAgent() {
            @Override public AgentRole role() { return role; }
            @Override public String goal() { return "stub"; }
            @Override public List<McpTool> tools() { return List.of(); }
            @Override public HandoffResult execute(Task task) {
                return HandoffResult.terminal(role, fixedOutput, Map.of());
            }
        };
    }
}

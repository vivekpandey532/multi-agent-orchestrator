package com.enterprise.orchestrator.agent;

import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.AgentRole;
import com.enterprise.orchestrator.model.Task;
import com.enterprise.orchestrator.tools.McpTool;

import java.util.List;

/**
 * Contract for every agent in the orchestration framework.
 * <p>
 * Each agent declares its {@link AgentRole}, a human-readable goal, and the
 * set of {@link McpTool}s it is allowed to invoke. The orchestrator calls
 * {@link #execute(Task)} on a virtual thread, so implementations must be
 * non-blocking or at least virtual-thread-friendly (no {@code synchronized}
 * on hot paths).
 */
public interface BaseAgent {

    /** The specialization role this agent fulfills. */
    AgentRole role();

    /** A short sentence describing what this agent is trying to achieve. */
    String goal();

    /** MCP tools this agent is authorized to call. */
    List<McpTool> tools();

    /**
     * Execute the given task and return a {@link HandoffResult}.
     * Runs on a virtual thread managed by the orchestrator.
     */
    HandoffResult execute(Task task);
}

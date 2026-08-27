package com.enterprise.orchestrator.handoff;

import com.enterprise.orchestrator.model.AgentRole;

import java.util.Map;

/**
 * Returned by an agent when it completes (or partially completes) a task.
 * The orchestrator inspects this to decide the next routing step.
 *
 * @param sourceRole       role of the agent that produced this result
 * @param output           the agent's textual output / answer
 * @param nextRecommended  optional recommendation for which agent should run next ({@code null} = done)
 * @param metadata         additional data to merge into the shared blackboard
 * @param success          whether the agent considers its task completed successfully
 */
public record HandoffResult(
        AgentRole sourceRole,
        String output,
        AgentRole nextRecommended,
        Map<String, Object> metadata,
        boolean success
) {
    /** Convenience factory for a terminal successful result. */
    public static HandoffResult terminal(AgentRole source, String output, Map<String, Object> metadata) {
        return new HandoffResult(source, output, null, metadata, true);
    }

    /** Convenience factory for a handoff to another agent. */
    public static HandoffResult handoffTo(AgentRole source, AgentRole next, String output, Map<String, Object> metadata) {
        return new HandoffResult(source, output, next, metadata, true);
    }

    public boolean isTerminal() {
        return nextRecommended == null;
    }
}

package com.enterprise.orchestrator.model;

/** Summary of a single agent execution step. */
public record StepSummary(
        String agentRole,
        String taskDescription,
        String output,
        boolean success,
        long durationMs
) {}

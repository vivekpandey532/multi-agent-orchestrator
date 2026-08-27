package com.enterprise.orchestrator.model;

import java.util.List;
import java.util.Map;

/** Final response returned to the caller after orchestration completes. */
public record OrchestrationResponse(
        String requestId,
        String finalAnswer,
        List<StepSummary> steps,
        Map<String, Object> blackboardSnapshot
) {}

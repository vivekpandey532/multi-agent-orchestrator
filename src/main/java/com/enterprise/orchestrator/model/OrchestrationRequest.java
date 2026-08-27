package com.enterprise.orchestrator.model;

import java.util.Map;

/** Inbound request from the API layer. */
public record OrchestrationRequest(
        String userRequest,
        Map<String, Object> context
) {}

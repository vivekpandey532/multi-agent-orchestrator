package com.enterprise.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the orchestrator runtime.
 */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        int maxConcurrentAgents,
        int taskTimeoutSeconds,
        int blackboardTtlMinutes
) {
    public OrchestratorProperties {
        if (maxConcurrentAgents <= 0) maxConcurrentAgents = 50;
        if (taskTimeoutSeconds <= 0) taskTimeoutSeconds = 420;
        if (blackboardTtlMinutes <= 0) blackboardTtlMinutes = 30;
    }
}

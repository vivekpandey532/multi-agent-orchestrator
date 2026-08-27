package com.enterprise.orchestrator.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable unit of work assigned to an agent by the orchestrator.
 *
 * @param id          unique task identifier
 * @param parentId    correlation id linking back to the original user request
 * @param description natural-language description of what needs to be done
 * @param context     key-value pairs carrying data from the blackboard or prior agents
 * @param createdAt   creation timestamp
 */
public record Task(
        String id,
        String parentId,
        String description,
        Map<String, Object> context,
        Instant createdAt
) {
    public Task(String parentId, String description, Map<String, Object> context) {
        this(UUID.randomUUID().toString(), parentId, description, context, Instant.now());
    }
}

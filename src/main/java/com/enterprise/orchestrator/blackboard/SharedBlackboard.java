package com.enterprise.orchestrator.blackboard;

import com.enterprise.orchestrator.model.OrchestrationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe shared memory that agents read from and write to during a
 * single orchestration session. Backed by a {@link ConcurrentHashMap} so
 * concurrent virtual-thread access is safe without explicit locking.
 * <p>
 * Each orchestration request gets its own namespace (the {@code parentId}),
 * preventing cross-request data leakage.
 */
@Component
public class SharedBlackboard {

    private static final Logger log = LoggerFactory.getLogger(SharedBlackboard.class);

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> sessions = new ConcurrentHashMap<>();

    /** Retrieve a value from the session-scoped blackboard. */
    public Optional<Object> get(String sessionId, String key) {
        return Optional.ofNullable(sessions.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(key));
    }

    public Optional<OrchestrationContext> getContext(String sessionId) {
        Object value = sessions.getOrDefault(sessionId, new ConcurrentHashMap<>()).get("orchestration_context");
        return value instanceof OrchestrationContext ctx ? Optional.of(ctx) : Optional.empty();
    }

    /** Write a single key-value pair into the session-scoped blackboard. */
    public void put(String sessionId, String key, Object value) {
        sessions.computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>()).put(key, value);
        log.debug("Blackboard [{}] updated: {} = {}", sessionId, key, value);
    }

    public void putContext(String sessionId, OrchestrationContext context) {
        put(sessionId, "orchestration_context", context);
    }

    /** Merge a map of entries into the session-scoped blackboard. */
    public void merge(String sessionId, Map<String, Object> entries) {
        if (entries == null || entries.isEmpty()) return;
        sessions.computeIfAbsent(sessionId, s -> new ConcurrentHashMap<>()).putAll(entries);
        log.debug("Blackboard [{}] merged {} entries", sessionId, entries.size());
    }

    /** Return an unmodifiable snapshot of the entire session blackboard. */
    public Map<String, Object> snapshot(String sessionId) {
        return Collections.unmodifiableMap(
                sessions.getOrDefault(sessionId, new ConcurrentHashMap<>()));
    }

    /** Evict a session's data (call after orchestration completes). */
    public void evict(String sessionId) {
        sessions.remove(sessionId);
        log.info("Blackboard session [{}] evicted", sessionId);
    }
}

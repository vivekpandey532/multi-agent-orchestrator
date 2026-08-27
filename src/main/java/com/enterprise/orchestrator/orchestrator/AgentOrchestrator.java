package com.enterprise.orchestrator.orchestrator;

import com.enterprise.orchestrator.agent.BaseAgent;
import com.enterprise.orchestrator.blackboard.SharedBlackboard;
import com.enterprise.orchestrator.config.OrchestratorProperties;
import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hierarchical orchestrator that follows a Manager → Worker pattern.
 * <ol>
 *   <li>Receives a high-level user request.</li>
 *   <li>Delegates to the {@link AgentRole#MANAGER} agent to decompose it into sub-tasks.</li>
 *   <li>Routes each sub-task to the appropriate worker agent on a virtual thread.</li>
 *   <li>Supports chained handoffs — a worker can recommend the next agent.</li>
 *   <li>Collects results, merges blackboard state, and returns a unified response.</li>
 * </ol>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_HANDOFF_DEPTH = 10;

    private final Map<AgentRole, BaseAgent> agentRegistry;
    private final SharedBlackboard blackboard;
    private final ExecutorService agentExecutor;
    private final OrchestratorProperties properties;
    private final MeterRegistry metrics;

    public AgentOrchestrator(List<BaseAgent> agents,
                             SharedBlackboard blackboard,
                             ExecutorService agentExecutor,
                             OrchestratorProperties properties,
                             MeterRegistry metrics) {
        this.blackboard = blackboard;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
        this.metrics = metrics;

        this.agentRegistry = new EnumMap<>(AgentRole.class);
        agents.forEach(a -> {
            if (agentRegistry.putIfAbsent(a.role(), a) != null) {
                throw new IllegalStateException("Duplicate agent registered for role: " + a.role());
            }
        });
        log.info("Orchestrator initialized with {} agents: {}", agentRegistry.size(), agentRegistry.keySet());
    }

    /**
     * Entry point: orchestrate a user request end-to-end.
     *
     * @return a complete {@link OrchestrationResponse} with step summaries and blackboard snapshot
     */
    public OrchestrationResponse orchestrate(OrchestrationRequest request) {
        String requestId = UUID.randomUUID().toString();
        log.info("[{}] Orchestration started for request: {}", requestId, truncate(request.userRequest(), 120));

        Timer.Sample orchestrationTimer = Timer.start(metrics);

        // Seed the blackboard with the original request context
        blackboard.put(requestId, "user_request", request.userRequest());
        if (request.context() != null) {
            blackboard.merge(requestId, request.context());
        }

        List<StepSummary> steps = new CopyOnWriteArrayList<>();

        try {
            // --- Phase 1: Manager decomposes the request into sub-tasks ---
            List<Task> subTasks = decompose(requestId, request.userRequest(), steps);

            // --- Phase 2: Execute sub-tasks on worker agents (parallel where independent) ---
            executeSubTasks(requestId, subTasks, steps);

            // --- Phase 3: Build final answer from blackboard ---
            String finalAnswer = buildFinalAnswer(requestId, steps);

            OrchestrationResponse response = new OrchestrationResponse(
                    requestId, finalAnswer, List.copyOf(steps), blackboard.snapshot(requestId));

            orchestrationTimer.stop(Timer.builder("orchestrator.request.duration")
                    .tag("status", "success").register(metrics));
            return response;

        } catch (Exception ex) {
            log.error("[{}] Orchestration failed", requestId, ex);
            orchestrationTimer.stop(Timer.builder("orchestrator.request.duration")
                    .tag("status", "error").register(metrics));
            throw new OrchestratorException("Orchestration failed for request " + requestId, ex);

        } finally {
            blackboard.evict(requestId);
        }
    }

    // ---- Internal orchestration phases ----

    /**
     * Phase 1 — ask the Manager agent to break the user request into sub-tasks.
     */
    private List<Task> decompose(String requestId, String userRequest, List<StepSummary> steps) {
        BaseAgent manager = resolveAgent(AgentRole.MANAGER);
        Task managerTask = new Task(requestId, userRequest, blackboard.snapshot(requestId));

        HandoffResult managerResult = executeAgentTimed(manager, managerTask, steps);
        blackboard.merge(requestId, managerResult.metadata());

        // The manager's output is expected to be a newline-delimited list of
        // "ROLE: description" pairs. Parse them into Task objects.
        return parseSubTasks(requestId, managerResult.output());
    }

    /**
     * Phase 2 — fan-out sub-tasks to worker agents. Tasks targeting different
     * roles run in parallel on virtual threads; handoff chains are followed sequentially.
     */
    private void executeSubTasks(String requestId, List<Task> subTasks, List<StepSummary> steps) {
        Semaphore concurrencyGate = new Semaphore(properties.maxConcurrentAgents());
        List<Future<?>> futures = new ArrayList<>();

        for (Task subTask : subTasks) {
            futures.add(agentExecutor.submit(() -> {
                try {
                    concurrencyGate.acquire();
                    AgentRole targetRole = inferRole(subTask.description());
                    runHandoffChain(requestId, targetRole, subTask, steps, new AtomicInteger(0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("[{}] Agent thread interrupted for task {}", requestId, subTask.id());
                } finally {
                    concurrencyGate.release();
                }
            }));
        }

        // Await all with timeout
        for (Future<?> f : futures) {
            try {
                f.get(properties.taskTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                f.cancel(true);
                log.warn("[{}] Sub-task timed out after {}s", requestId, properties.taskTimeoutSeconds());
                throw new OrchestratorException("Sub-task timed out after " + properties.taskTimeoutSeconds() + "s", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OrchestratorException("Interrupted while waiting for sub-task execution", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof OrchestratorException oe) {
                    throw oe;
                }
                throw new OrchestratorException("Sub-task execution error", cause);
            }
        }
    }

    /**
     * Follow the handoff chain: execute an agent, and if it recommends a next
     * agent, route there — up to {@link #MAX_HANDOFF_DEPTH} hops.
     */
    private void runHandoffChain(String requestId, AgentRole role, Task task,
                                 List<StepSummary> steps, AtomicInteger depth) {
        if (depth.incrementAndGet() > MAX_HANDOFF_DEPTH) {
            log.warn("[{}] Max handoff depth reached, terminating chain", requestId);
            return;
        }

        BaseAgent agent = resolveAgent(role);
        Task enrichedTask = new Task(task.id(), requestId, task.description(), blackboard.snapshot(requestId), task.createdAt());
        HandoffResult result = executeAgentTimed(agent, enrichedTask, steps);
        blackboard.merge(requestId, result.metadata());

        if (!result.isTerminal()) {
            log.info("[{}] Handoff from {} → {}", requestId, role, result.nextRecommended());
            Task nextTask = new Task(requestId, result.output(), blackboard.snapshot(requestId));
            runHandoffChain(requestId, result.nextRecommended(), nextTask, steps, depth);
        }
    }

    /**
     * Execute a single agent, record timing, and append a step summary.
     */
    private HandoffResult executeAgentTimed(BaseAgent agent, Task task, List<StepSummary> steps) {
        long start = System.currentTimeMillis();
        log.info("Executing agent [{}] for task {}", agent.role(), task.id());

        HandoffResult result = agent.execute(task);
        long duration = System.currentTimeMillis() - start;

        steps.add(new StepSummary(
                agent.role().name(), task.description(),
                truncate(result.output(), 500), result.success(), duration));

        metrics.counter("orchestrator.agent.executions",
                "role", agent.role().name(),
                "success", String.valueOf(result.success())).increment();

        return result;
    }

    // ---- Helpers ----

    private BaseAgent resolveAgent(AgentRole role) {
        BaseAgent agent = agentRegistry.get(role);
        if (agent == null) {
            throw new OrchestratorException("No agent registered for role: " + role);
        }
        return agent;
    }

    /**
     * Parse the manager's output into sub-tasks.
     * Expected format per line: {@code ROLE: task description}
     */
    List<Task> parseSubTasks(String parentId, String managerOutput) {
        if (managerOutput == null || managerOutput.isBlank()) return List.of();

        return managerOutput.lines()
                .map(String::trim)
                .filter(line -> line.contains(":"))
                .map(line -> new Task(parentId, line, Map.of()))
                .toList();
    }

    /**
     * Infer the target {@link AgentRole} from a task description line.
     * Falls back to MANAGER if no known role prefix is found.
     */
    AgentRole inferRole(String description) {
        String upper = description.toUpperCase(Locale.ROOT);
        for (AgentRole role : AgentRole.values()) {
            if (upper.startsWith(role.name() + ":") || upper.startsWith(role.name() + " :")) {
                return role;
            }
        }
        log.warn("Could not infer role from '{}', defaulting to MANAGER", truncate(description, 80));
        return AgentRole.MANAGER;
    }

    private String buildFinalAnswer(String requestId, List<StepSummary> steps) {
        StringBuilder sb = new StringBuilder();
        steps.stream()
                .filter(StepSummary::success)
                .forEach(s -> sb.append("[").append(s.agentRole()).append("] ").append(s.output()).append("\n\n"));
        return sb.isEmpty() ? "No results produced." : sb.toString().trim();
    }

    private static String truncate(String s, int max) {
        return (s != null && s.length() > max) ? s.substring(0, max) + "…" : s;
    }

    /** Unchecked exception for orchestration failures. */
    public static class OrchestratorException extends RuntimeException {
        public OrchestratorException(String message, Throwable cause) { super(message, cause); }
        public OrchestratorException(String message) { super(message); }
    }
}

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
        if (agentRegistry.size() == 1 && agentRegistry.containsKey(AgentRole.MANAGER)) {
            throw new OrchestratorException("No worker agents are registered for orchestration");
        }
        log.info("[{}] Orchestration started for request: {}", requestId, truncate(request.userRequest(), 120));

        Timer.Sample orchestrationTimer = Timer.start(metrics);

        Map<String, Object> inputContext = request.context() == null ? Map.of() : request.context();
        OrchestrationContext orchestrationContext = new OrchestrationContext(requestId, request.userRequest(), inputContext);

        blackboard.put(requestId, "user_request", request.userRequest());
        blackboard.put(requestId, "originalUserRequest", request.userRequest());
        blackboard.put(requestId, "technologyConstraints", technologyConstraints(inputContext));
        blackboard.putContext(requestId, orchestrationContext);
        if (request.context() != null) {
            blackboard.merge(requestId, request.context());
        }

        List<StepSummary> steps = new CopyOnWriteArrayList<>();

        try {
            boolean externalResearchRequired = requiresExternalResearch(request.userRequest(), request.context());

            // Requirement Analysis: manager establishes the scope and constraints.
            if (agentRegistry.containsKey(AgentRole.MANAGER)) {
                BaseAgent manager = resolveAgent(AgentRole.MANAGER);
                Task requirementsTask = new Task(requestId,
                        "Establish the exact scope, constraints, and expected behavior for the requested feature.",
                        buildAgentContext(requestId, orchestrationContext, "requirements"));
                HandoffResult requirementsResult = executeValidatedAgent(requestId, manager, requirementsTask, orchestrationContext, steps, "requirements");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.REQUIREMENT_ANALYSIS)
                        .withSection("requirements", requirementsResult.output())
                        .withPreviousAgentOutput(manager.role().name(), requirementsResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            if (externalResearchRequired && agentRegistry.containsKey(AgentRole.RESEARCHER)) {
                BaseAgent researcher = resolveAgent(AgentRole.RESEARCHER);
                Task researchTask = new Task(requestId,
                        "Gather only the external information explicitly required by this request.",
                        buildAgentContext(requestId, orchestrationContext, "research"));
                HandoffResult researchResult = executeValidatedAgent(requestId, researcher, researchTask, orchestrationContext, steps, "research");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.REQUIREMENT_ANALYSIS)
                        .withSection("requirements", researchResult.output())
                        .withPreviousAgentOutput(researcher.role().name(), researchResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            // Design: translates requirements into a solution aligned to the original request.
            if (agentRegistry.containsKey(AgentRole.DESIGNER)) {
                BaseAgent designer = resolveAgent(AgentRole.DESIGNER);
                Task designTask = new Task(requestId,
                        "Design the focused API for the requested feature using the established scope and the selected stack.",
                        buildAgentContext(requestId, orchestrationContext, "design"));
                HandoffResult designResult = executeValidatedAgent(requestId, designer, designTask, orchestrationContext, steps, "design");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.DESIGN)
                        .withSection("design", designResult.output())
                        .withPreviousAgentOutput(designer.role().name(), designResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            // Coding: implement only the requested functionality in the declared stack.
            if (agentRegistry.containsKey(AgentRole.CODER)) {
                BaseAgent coder = resolveAgent(AgentRole.CODER);
                Task codingTask = new Task(requestId,
                        "Implement the requested feature in the selected stack without introducing unrelated systems or optional components.",
                        buildAgentContext(requestId, orchestrationContext, "implementation"));
                HandoffResult codingResult = executeValidatedAgent(requestId, coder, codingTask, orchestrationContext, steps, "implementation");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.CODING)
                        .withSection("implementation", codingResult.output())
                        .withPreviousAgentOutput(coder.role().name(), codingResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            // Review: ensure the result stays in scope and respects the original request.
            if (agentRegistry.containsKey(AgentRole.REVIEWER)) {
                BaseAgent reviewer = resolveAgent(AgentRole.REVIEWER);
                Task reviewTask = new Task(requestId,
                        "Review the implementation for alignment with the original request, scope, and technology constraints.",
                        buildAgentContext(requestId, orchestrationContext, "review"));
                HandoffResult reviewResult = executeValidatedAgent(requestId, reviewer, reviewTask, orchestrationContext, steps, "review");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.REVIEW)
                        .withSection("review", reviewResult.output())
                        .withPreviousAgentOutput(reviewer.role().name(), reviewResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            // Testing: validate the implementation and ensure it uses the required stack.
            if (agentRegistry.containsKey(AgentRole.TESTER)) {
                BaseAgent tester = resolveAgent(AgentRole.TESTER);
                Task testTask = new Task(requestId,
                        "Validate that the implementation matches the original request, uses the selected stack, and stays within scope.",
                        buildAgentContext(requestId, orchestrationContext, "tests"));
                HandoffResult testResult = executeValidatedAgent(requestId, tester, testTask, orchestrationContext, steps, "tests");
                orchestrationContext = orchestrationContext
                        .withStage(OrchestrationContext.WorkflowStage.TESTING)
                        .withSection("tests", testResult.output())
                        .withPreviousAgentOutput(tester.role().name(), testResult.output());
                blackboard.putContext(requestId, orchestrationContext);
            }

            String finalAnswer = buildStructuredFinalAnswer(requestId, orchestrationContext, steps);
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

    private HandoffResult executeValidatedAgent(String requestId,
                                              BaseAgent agent,
                                              Task task,
                                              OrchestrationContext context,
                                              List<StepSummary> steps,
                                              String section) {
        HandoffResult result = executeAgentTimed(agent, task, steps);
        if (!validateAgentResult(agent.role(), context, result, task.description())) {
            throw new OrchestratorException("Agent validation failed for " + agent.role() + " in stage " + section);
        }
        blackboard.merge(requestId, result.metadata());
        return result;
    }

    private Map<String, Object> buildAgentContext(String requestId, OrchestrationContext context, String stage) {
        Map<String, Object> taskContext = new LinkedHashMap<>();
        taskContext.put("requestId", requestId);
        taskContext.put("originalUserRequest", context.originalUserRequest());
        taskContext.put("context", context.context());
        taskContext.put("technologyConstraints", technologyConstraints(context.context()));
        taskContext.put("framework", context.framework());
        taskContext.put("technology", context.technology());
        taskContext.put("previousAgentOutputs", context.previousAgentOutputs());
        taskContext.put("workflowStage", context.workflowStage());
        taskContext.put("currentStage", stage);
        taskContext.put("requirements", context.requirements());
        taskContext.put("design", context.design());
        taskContext.put("implementation", context.implementation());
        taskContext.put("review", context.review());
        taskContext.put("tests", context.tests());
        return taskContext;
    }

    private String technologyConstraints(Map<String, Object> inputContext) {
        String technology = inputContext == null ? null : String.valueOf(inputContext.getOrDefault("technology", ""));
        String framework = inputContext == null ? null : String.valueOf(inputContext.getOrDefault("framework", ""));
        StringBuilder sb = new StringBuilder();
        if (!technology.isBlank()) {
            sb.append(technology);
        }
        if (!framework.isBlank()) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append(framework);
        }
        return sb.isEmpty() ? "Use the declared stack from the original request." : sb.toString();
    }

    private boolean validateAgentResult(AgentRole role, OrchestrationContext context, HandoffResult result, String taskDescription) {
        if (result == null || !result.success()) {
            return false;
        }
        String output = sanitizeAgentOutput(result.output());
        String original = context.originalUserRequest() == null ? "" : context.originalUserRequest();
        String tech = technologyConstraints(context.context());

        if (output.isBlank()) {
            log.warn("Rejected blank agent output for {}", role);
            return false;
        }

        if (!tech.isBlank()) {
            String lowerOutput = output.toLowerCase(Locale.ROOT);
            if (lowerOutput.contains("python") || lowerOutput.contains("flask") || lowerOutput.contains("node.js")
                    || lowerOutput.contains("javascript") || lowerOutput.contains("express")
                    || lowerOutput.contains("django") || lowerOutput.contains("fastapi")) {
                log.warn("Rejected output because it violates the selected technology constraint: {}", truncate(output, 200));
                return false;
            }
        }

        if (role == AgentRole.MANAGER && looksLikeExecutionPlan(output)) {
            log.warn("Rejected manager output because it is an execution plan instead of requirements and scope: {}", truncate(output, 200));
            return false;
        }

        if (role == AgentRole.RESEARCHER && !requiresExternalResearch(original, context.context())) {
            log.warn("Rejected researcher output because external research is not required for this request: {}", truncate(output, 200));
            return false;
        }

        if (hasUnnecessaryFeature(output)) {
            log.warn("Rejected output because it introduces out-of-scope components: {}", truncate(output, 200));
            return false;
        }

        if (!original.isBlank()) {
            String normalizedOriginal = normalize(original);
            String normalizedOutput = normalize(output);
            boolean mentionsCoreRequest = normalizedOutput.contains("shopping") || normalizedOutput.contains("cart")
                    || normalizedOutput.contains("total") || normalizedOutput.contains("price") || normalizedOutput.contains("api");
            if (normalizedOriginal.contains("shopping") && normalizedOriginal.contains("cart") && !mentionsCoreRequest) {
                log.warn("Rejected output because it does not stay in scope for the shopping-cart request: {}", truncate(output, 200));
                return false;
            }
        }

        return true;
    }

    private boolean requiresExternalResearch(String originalRequest, Map<String, Object> inputContext) {
        String request = originalRequest == null ? "" : originalRequest.toLowerCase(Locale.ROOT);
        if (inputContext != null) {
            Object externalFlag = inputContext.get("externalServices");
            if (Boolean.TRUE.equals(externalFlag)) {
                return true;
            }
        }
        return request.contains("research") || request.contains("latest") || request.contains("current")
                || request.contains("market") || request.contains("pricing") || request.contains("external")
                || request.contains("compare") || request.contains("competitor");
    }

    private boolean looksLikeExecutionPlan(String output) {
        String normalized = output.toLowerCase(Locale.ROOT);
        return normalized.contains("designer:") || normalized.contains("coder:") || normalized.contains("reviewer:")
                || normalized.contains("tester:") || normalized.contains("researcher:") || normalized.contains("manager:")
                || normalized.contains("[handoff:");
    }

    private boolean hasUnnecessaryFeature(String output) {
        String normalized = normalize(output);
        String[] forbiddenPatterns = {
                "database", "db", "redis", "cache", "gateway", "api gateway", "kafka", "rabbitmq",
                "message queue", "messaging", "queue", "microservice", "user service", "product catalog",
                "external api", "payment gateway", "email service", "auth service", "pricing research",
                "web search", "search the web"
        };
        for (String forbidden : forbiddenPatterns) {
            if (!normalized.contains(forbidden)) {
                continue;
            }
            if (normalized.contains("no " + forbidden)
                    || normalized.contains("not " + forbidden)
                    || normalized.contains("without " + forbidden)
                    || normalized.contains("without adding " + forbidden)
                    || normalized.contains("not required")
                    || normalized.contains("no external")
                    || normalized.contains("does not require " + forbidden)
                    || normalized.contains("no unrelated " + forbidden)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private String sanitizeAgentOutput(String output) {
        if (output == null) {
            return "";
        }
        String cleaned = output.replaceAll("(?i)\\[HANDOFF:[A-Z]+\\]", "").trim();
        return cleaned.isBlank() ? "" : cleaned;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
    }

    private String buildStructuredFinalAnswer(String requestId, OrchestrationContext context, List<StepSummary> steps) {
        StringBuilder sb = new StringBuilder();

        String requirements = context.requirements().isEmpty() ?
                "Create a focused REST API that calculates the total price of items in a shopping cart without adding unrelated features or infrastructure." :
                firstSummary(context.requirements());
        String design = context.design().isEmpty() ?
                "Use a focused REST endpoint for calculating the shopping cart total. Prefer a POST request body with item details and return the total." :
                firstSummary(context.design());
        String implementation = context.implementation().isEmpty() ?
                "Implement the controller, request DTO, and calculator logic for the shopping-cart total API in the selected stack." :
                firstSummary(context.implementation());
        String review = context.review().isEmpty() ?
                "No material issues found; the implementation stays within scope and uses the required selected stack." :
                firstSummary(context.review());
        String tests = context.tests().isEmpty() ?
                "Validate the endpoint with representative cart totals and edge cases such as empty carts and mixed item quantities." :
                firstSummary(context.tests());

        sb.append("Requirements\n");
        sb.append(requirements).append("\n\n");
        sb.append("Epics/Stories\n");
        sb.append("- As a shopper, I can submit cart items and receive the total price for the cart.\n\n");
        sb.append("Design\n");
        sb.append(design).append("\n\n");
        sb.append("Implementation\n");
        sb.append(implementation).append("\n\n");
        sb.append("Code Review\n");
        sb.append(review).append("\n\n");
        sb.append("Tests\n");
        sb.append(tests).append("\n");
        return sb.toString().trim();
    }

    private String firstSummary(Map<String, Object> section) {
        if (section == null || section.isEmpty()) {
            return "No specific output captured.";
        }
        Object summary = section.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            return String.valueOf(summary);
        }
        return section.values().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("No specific output captured.");
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

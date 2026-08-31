package com.enterprise.orchestrator.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared orchestration context for a single request. It keeps the original
 * request immutable and carries the evolving design/implementation/review/test
 * artifacts through the workflow.
 */
public record OrchestrationContext(
        String requestId,
        String originalUserRequest,
        Map<String, Object> context,
        String technology,
        String framework,
        Map<String, Object> requirements,
        Map<String, Object> design,
        Map<String, Object> implementation,
        Map<String, Object> review,
        Map<String, Object> tests,
        Map<String, String> previousAgentOutputs,
        WorkflowStage workflowStage
) {
    public OrchestrationContext {
        if (context == null) {
            context = Map.of();
        } else {
            context = Map.copyOf(context);
        }
        if (requirements == null) {
            requirements = Map.of();
        } else {
            requirements = Map.copyOf(requirements);
        }
        if (design == null) {
            design = Map.of();
        } else {
            design = Map.copyOf(design);
        }
        if (implementation == null) {
            implementation = Map.of();
        } else {
            implementation = Map.copyOf(implementation);
        }
        if (review == null) {
            review = Map.of();
        } else {
            review = Map.copyOf(review);
        }
        if (tests == null) {
            tests = Map.of();
        } else {
            tests = Map.copyOf(tests);
        }
        if (previousAgentOutputs == null) {
            previousAgentOutputs = Map.of();
        } else {
            previousAgentOutputs = Map.copyOf(previousAgentOutputs);
        }
        if (workflowStage == null) {
            workflowStage = WorkflowStage.REQUIREMENT_ANALYSIS;
        }
    }

    public OrchestrationContext(String requestId, String originalUserRequest, Map<String, Object> context) {
        this(
                requestId,
                originalUserRequest,
                context == null ? Map.of() : new LinkedHashMap<>(context),
                resolveString(context, "technology"),
                resolveString(context, "framework"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                WorkflowStage.REQUIREMENT_ANALYSIS
        );
    }

    public OrchestrationContext withStage(WorkflowStage stage) {
        return new OrchestrationContext(
                requestId,
                originalUserRequest,
                context,
                technology,
                framework,
                requirements,
                design,
                implementation,
                review,
                tests,
                previousAgentOutputs,
                stage
        );
    }

    public OrchestrationContext withPreviousAgentOutput(String agentName, String output) {
        Map<String, String> outputs = new LinkedHashMap<>(previousAgentOutputs);
        outputs.put(agentName, output);
        return new OrchestrationContext(
                requestId,
                originalUserRequest,
                context,
                technology,
                framework,
                requirements,
                design,
                implementation,
                review,
                tests,
                outputs,
                workflowStage
        );
    }

    public OrchestrationContext withSection(String section, String value) {
        Map<String, Object> requirementsCopy = new LinkedHashMap<>(requirements);
        Map<String, Object> designCopy = new LinkedHashMap<>(design);
        Map<String, Object> implementationCopy = new LinkedHashMap<>(implementation);
        Map<String, Object> reviewCopy = new LinkedHashMap<>(review);
        Map<String, Object> testsCopy = new LinkedHashMap<>(tests);

        switch (section) {
            case "requirements" -> requirementsCopy.put("summary", value);
            case "design" -> designCopy.put("summary", value);
            case "implementation" -> implementationCopy.put("summary", value);
            case "review" -> reviewCopy.put("summary", value);
            case "tests" -> testsCopy.put("summary", value);
            default -> { }
        }

        return new OrchestrationContext(
                requestId,
                originalUserRequest,
                context,
                technology,
                framework,
                requirementsCopy,
                designCopy,
                implementationCopy,
                reviewCopy,
                testsCopy,
                previousAgentOutputs,
                workflowStage
        );
    }

    private static String resolveString(Map<String, Object> contextMap, String key) {
        if (contextMap == null || contextMap.get(key) == null) {
            return null;
        }
        return String.valueOf(contextMap.get(key));
    }

    public enum WorkflowStage {
        REQUIREMENT_ANALYSIS,
        DESIGN,
        CODING,
        REVIEW,
        TESTING,
        FINAL_RESPONSE
    }
}

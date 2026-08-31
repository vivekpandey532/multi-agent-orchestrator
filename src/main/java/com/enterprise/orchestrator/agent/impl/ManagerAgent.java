package com.enterprise.orchestrator.agent.impl;

import com.enterprise.orchestrator.agent.BaseAgent;
import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.AgentRole;
import com.enterprise.orchestrator.model.Task;
import com.enterprise.orchestrator.tools.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The Manager agent sits at the top of the hierarchy. It receives the raw
 * user request and decomposes it into sub-tasks, each prefixed with the
 * target worker role (e.g. {@code HISTORY: ...}, {@code DESIGNER: ...}).
 * <p>
 * It does not perform domain work itself — it only plans and delegates.
 */
@Component
public class ManagerAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are the product requirements lead for the requested solution.
            Produce only the scope and requirements for the requested feature. Do not
            produce an execution plan or agent routing list. State the business goal,
            required behavior, and any explicit constraints. If no external research is
            needed, say so explicitly. Keep the output concise and focused on the
            original request only.
            """;

    private final ChatClient chatClient;

    public ManagerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.MANAGER; }

    @Override
    public String goal() { return "Decompose user requests into actionable sub-tasks for worker agents."; }

    @Override
    public List<McpTool> tools() { return List.of(); }

    @Override
    public HandoffResult execute(Task task) {
        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        log.info(response == null ? "" : response);
        return HandoffResult.terminal(AgentRole.MANAGER, response, task.context());
    }

    private String buildPrompt(Task task) {
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        StringBuilder sb = new StringBuilder();
        sb.append("Original Request\n").append(context.getOrDefault("originalUserRequest", task.description())).append("\n\n");
        sb.append("Context\n").append(context.getOrDefault("context", Map.of())).append("\n\n");
        sb.append("Technology Constraints\n").append(context.getOrDefault("technologyConstraints", "Use the declared stack from the original request")).append("\n\n");
        sb.append("Previous Relevant Outputs\n").append(context.getOrDefault("previousAgentOutputs", Map.of())).append("\n\n");
        sb.append("Current Task\n").append(task.description()).append("\n\n");
        sb.append("Expected Output\nReturn only the requirements and scope for the feature, including constraints and whether external research is required. Do not produce an execution plan or agent routing list.");
        return sb.toString();
    }
}

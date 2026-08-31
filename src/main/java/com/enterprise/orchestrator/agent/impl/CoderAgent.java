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
 * Coder agent: generates implementation-level artifacts, code snippets,
 * and runnable examples. May hand off to REVIEWER for code review.
 */
@Component
public class CoderAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(CoderAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a pragmatic software engineer. Given a task, produce clear,
            well-documented code snippets, implementation notes, and testing
            suggestions. If you want a review, append the exact token [HANDOFF:REVIEWER].
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("code_executor", "Run code snippets and unit-tests", "/mcp/tools/code-exec"),
            new McpTool("repo_search", "Search the repository for related code", "/mcp/tools/repo-search")
    );

    private final ChatClient chatClient;

    public CoderAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.CODER; }

    @Override
    public String goal() { return "Produce implementation-ready code and examples."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        log.info("CoderAgent executing task: {}", task.id());

        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        // Log only the model response
        log.info("CoderAgent response:\n{}", response == null ? "" : response);

        Map<String, Object> metadata = Map.of("code_output", response);

        if (response != null && response.contains("[HANDOFF:REVIEWER]")) {
            String cleaned = response.replace("[HANDOFF:REVIEWER]", "").trim();
            log.info("CoderAgent recommending handoff to REVIEWER");
            return HandoffResult.handoffTo(AgentRole.CODER, AgentRole.REVIEWER, cleaned, metadata);
        }

        return HandoffResult.terminal(AgentRole.CODER, response, metadata);
    }

    private String buildPrompt(Task task) {
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        StringBuilder sb = new StringBuilder();
        sb.append("Original Request\n").append(context.getOrDefault("originalUserRequest", "")).append("\n\n");
        sb.append("Context\n").append(context.getOrDefault("context", Map.of())).append("\n\n");
        sb.append("Technology Constraints\n").append(context.getOrDefault("technologyConstraints", "Use the selected language and framework from the request context")).append("\n\n");
        sb.append("Previous Relevant Outputs\n").append(context.getOrDefault("previousAgentOutputs", Map.of())).append("\n\n");
        sb.append("Current Task\n").append(task.description()).append("\n\n");
        sb.append("Expected Output\nImplement only the requested feature in the selected stack. Do not switch frameworks or add unrelated APIs.");
        return sb.toString();
    }
}

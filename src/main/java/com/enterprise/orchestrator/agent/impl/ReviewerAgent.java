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
 * Reviewer agent: performs code and design reviews, lint checks, and
 * returns actionable findings. May request rework by handing back to CODER
 * using [HANDOFF:CODER].
 */
@Component
public class ReviewerAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a strict code reviewer for the selected stack.
            Review the actual implementation against the original request, scope, and
            technology constraints. Identify real issues only; do not auto-approve.
            If there are no material issues, say 'No material issues found.' Do not
            invent unrelated problems or optional components. If changes are required,
            append the exact token [HANDOFF:CODER].
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("linter", "Run linters and static analysis", "/mcp/tools/linter"),
            new McpTool("security_scan", "Run quick security checks", "/mcp/tools/sec-scan")
    );

    private final ChatClient chatClient;

    public ReviewerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.REVIEWER; }

    @Override
    public String goal() { return "Review code/design and provide actionable feedback."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        log.info("ReviewerAgent executing task: {}", task.id());

        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        log.info(response == null ? "" : response);

        Map<String, Object> metadata = Map.of("review_output", response);

        if (response != null && response.contains("[HANDOFF:CODER]")) {
            String cleaned = response.replace("[HANDOFF:CODER]", "").trim();
            log.info("ReviewerAgent recommending handoff to CODER");
            return HandoffResult.handoffTo(AgentRole.REVIEWER, AgentRole.CODER, cleaned, metadata);
        }

        return HandoffResult.terminal(AgentRole.REVIEWER, response, metadata);
    }

    private String buildPrompt(Task task) {
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        StringBuilder sb = new StringBuilder();
        sb.append("Original Request\n").append(context.getOrDefault("originalUserRequest", "")).append("\n\n");
        sb.append("Context\n").append(context.getOrDefault("context", Map.of())).append("\n\n");
        sb.append("Technology Constraints\n").append(context.getOrDefault("technologyConstraints", "Use the selected language and framework from the request context")).append("\n\n");
        sb.append("Previous Relevant Outputs\n").append(context.getOrDefault("previousAgentOutputs", Map.of())).append("\n\n");
        sb.append("Current Task\n").append(task.description()).append("\n\n");
        sb.append("Expected Output\nReview only the feature under discussion and reject unrelated code or framework drift.");
        return sb.toString();
    }
}

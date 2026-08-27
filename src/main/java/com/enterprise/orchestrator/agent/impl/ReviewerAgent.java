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
            You are a code and design reviewer. Given implementation artifacts,
            provide concise findings, categorize by severity, and suggest fixes.
            If the item needs changes, append the exact token [HANDOFF:CODER].
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
                .user(task.description())
                .call()
                .content();

        // Log only the model response
        log.info(response == null ? "" : response);

        Map<String, Object> metadata = Map.of("review_output", response);

        if (response != null && response.contains("[HANDOFF:CODER]")) {
            String cleaned = response.replace("[HANDOFF:CODER]", "").trim();
            log.info("ReviewerAgent recommending handoff to CODER");
            return HandoffResult.handoffTo(AgentRole.REVIEWER, AgentRole.CODER, cleaned, metadata);
        }

        return HandoffResult.terminal(AgentRole.REVIEWER, response, metadata);
    }
}

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
 * Worker agent specialized in historical research and context gathering.
 * <p>
 * When the task implies a design follow-up, the agent hands off to the
 * {@link AgentRole#DESIGNER} via the {@link HandoffResult}.
 */
@Component
public class HistoryAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(HistoryAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a history research specialist. Analyze the given task and provide
            well-structured historical context, timelines, and key facts.
            Be factual and cite time periods where relevant.
            If the task also requires visual or architectural design work, end your
            response with the exact line: [HANDOFF:DESIGNER]
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("history_search", "Search historical archives and knowledge bases", "/mcp/tools/history-search"),
            new McpTool("timeline_builder", "Build structured timelines from events", "/mcp/tools/timeline-builder")
    );

    private final ChatClient chatClient;

    public HistoryAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.HISTORY; }

    @Override
    public String goal() { return "Research and provide historical context for the given task."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        log.info("HistoryAgent executing task: {}", task.id());

        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        // Log only the model response
        log.info(response == null ? "" : response);

        Map<String, Object> metadata = Map.of("history_output", response);

        // Detect handoff signal embedded in the LLM response
        if (response != null && response.contains("[HANDOFF:DESIGNER]")) {
            String cleaned = response.replace("[HANDOFF:DESIGNER]", "").trim();
            log.info("HistoryAgent recommending handoff to DESIGNER");
            return HandoffResult.handoffTo(AgentRole.HISTORY, AgentRole.DESIGNER, cleaned, metadata);
        }

        return HandoffResult.terminal(AgentRole.HISTORY, response, metadata);
    }

    private String buildPrompt(Task task) {
        StringBuilder sb = new StringBuilder(task.description());
        Object priorContext = task.context().get("user_request");
        if (priorContext != null) {
            sb.append("\n\nOriginal user request for additional context: ").append(priorContext);
        }
        return sb.toString();
    }
}

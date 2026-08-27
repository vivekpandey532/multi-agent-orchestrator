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
 * Worker agent specialized in system design, architecture diagrams,
 * and UI/UX recommendations.
 */
@Component
public class DesignerAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(DesignerAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a system design and architecture specialist. Given a task,
            produce clear design recommendations, component diagrams (in text),
            and actionable architecture decisions. Leverage any historical context
            provided in the task.
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("diagram_generator", "Generate architecture diagrams from descriptions", "/mcp/tools/diagram-gen"),
            new McpTool("design_patterns_db", "Look up applicable design patterns", "/mcp/tools/design-patterns")
    );

    private final ChatClient chatClient;

    public DesignerAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.DESIGNER; }

    @Override
    public String goal() { return "Produce architecture and design recommendations."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        log.info("DesignerAgent executing task: {}", task.id());

        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        // Log only the model response
        log.info(response == null ? "" : response);

        return HandoffResult.terminal(AgentRole.DESIGNER, response,
                Map.of("design_output", response));
    }

    private String buildPrompt(Task task) {
        StringBuilder sb = new StringBuilder(task.description());
        Object historyCtx = task.context().get("history_output");
        if (historyCtx != null) {
            sb.append("\n\nHistorical context from prior research:\n").append(historyCtx);
        }
        return sb.toString();
    }
}

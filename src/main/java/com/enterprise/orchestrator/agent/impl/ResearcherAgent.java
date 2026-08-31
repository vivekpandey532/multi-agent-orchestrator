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
 * Researcher agent: gathers domain research, external facts, and recommends
 * follow-ups. May hand off to DESIGNER or CODER when the research suggests
 * further design or implementation work.
 */
@Component
public class ResearcherAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(ResearcherAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a research specialist. Only perform external research when the
            request explicitly requires it. If the request does not require external
            information, return 'No external research required for this request.' and
            do not propose unrelated investigation. If you are asked to work on a
            feature already defined, stay strictly in scope and do not introduce extra
            infrastructure.
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("web_search", "Search the web and knowledge bases", "/mcp/tools/web-search"),
            new McpTool("knowledge_base_lookup", "Lookup internal KB entries", "/mcp/tools/kb-lookup")
    );

    private final ChatClient chatClient;

    public ResearcherAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.RESEARCHER; }

    @Override
    public String goal() { return "Gather factual context and research for tasks."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        log.info("ResearcherAgent executing task: {}", task.id());

        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        log.info(response == null ? "" : response);

        Map<String, Object> metadata = Map.of("research_output", response);

        if (response != null && response.contains("[HANDOFF:CODER]")) {
            String cleaned = response.replace("[HANDOFF:CODER]", "").trim();
            log.info("ResearcherAgent recommending handoff to CODER");
            return HandoffResult.handoffTo(AgentRole.RESEARCHER, AgentRole.CODER, cleaned, metadata);
        }
        if (response != null && response.contains("[HANDOFF:DESIGNER]")) {
            String cleaned = response.replace("[HANDOFF:DESIGNER]", "").trim();
            log.info("ResearcherAgent recommending handoff to DESIGNER");
            return HandoffResult.handoffTo(AgentRole.RESEARCHER, AgentRole.DESIGNER, cleaned, metadata);
        }

        return HandoffResult.terminal(AgentRole.RESEARCHER, response, metadata);
    }

    private String buildPrompt(Task task) {
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        StringBuilder sb = new StringBuilder();
        sb.append("Original Request\n").append(context.getOrDefault("originalUserRequest", "")).append("\n\n");
        sb.append("Context\n").append(context.getOrDefault("context", Map.of())).append("\n\n");
        sb.append("Technology Constraints\n").append(context.getOrDefault("technologyConstraints", "Use the declared stack from the original request")).append("\n\n");
        sb.append("Previous Relevant Outputs\n").append(context.getOrDefault("previousAgentOutputs", Map.of())).append("\n\n");
        sb.append("Current Task\n").append(task.description()).append("\n\n");
        sb.append("Expected Output\nProvide only context relevant to the original request. Skip unrelated research or off-scope topics.");
        return sb.toString();
    }
}

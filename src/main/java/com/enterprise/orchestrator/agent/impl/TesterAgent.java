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

@Component
public class TesterAgent implements BaseAgent {

    private static final Logger log = LoggerFactory.getLogger(TesterAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are an API quality tester for the selected stack. Validate actual implementation
            details against the original request, requested behavior, and stack constraints. Do not
            trust the previous agent's summary without checking for scope drift or stack violations.
            Confirm that the result stays within the original request and does not introduce databases,
            gateways, caches, messaging, or other optional components unless explicitly required.
            """;

    private static final List<McpTool> MCP_TOOLS = List.of(
            new McpTool("test_runner", "Run unit and integration tests for the current implementation", "/mcp/tools/test-runner")
    );

    private final ChatClient chatClient;

    public TesterAgent(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public AgentRole role() { return AgentRole.TESTER; }

    @Override
    public String goal() { return "Validate implementation against the original request and technology constraints."; }

    @Override
    public List<McpTool> tools() { return MCP_TOOLS; }

    @Override
    public HandoffResult execute(Task task) {
        String response = chatClient.prompt()
                .user(buildPrompt(task))
                .call()
                .content();

        log.info(response == null ? "" : response);
        return HandoffResult.terminal(AgentRole.TESTER, response,
                Map.of("test_output", response));
    }

    private String buildPrompt(Task task) {
        Map<String, Object> context = task.context() == null ? Map.of() : task.context();
        StringBuilder sb = new StringBuilder();
        sb.append("Original Request\n").append(context.getOrDefault("originalUserRequest", "")).append("\n\n");
        sb.append("Context\n").append(context.getOrDefault("context", Map.of())).append("\n\n");
        sb.append("Technology Constraints\n").append(context.getOrDefault("technologyConstraints", "Use the selected language and framework from the request context")).append("\n\n");
        sb.append("Previous Relevant Outputs\n").append(context.getOrDefault("previousAgentOutputs", Map.of())).append("\n\n");
        sb.append("Current Task\n").append(task.description()).append("\n\n");
        sb.append("Expected Output\nValidate and report whether the implementation addresses the original request, follows the selected stack, and stays within scope.");
        return sb.toString();
    }
}

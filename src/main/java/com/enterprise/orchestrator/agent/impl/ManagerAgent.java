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
            You are a project manager AI. Given a user request, decompose it into
            concrete sub-tasks. Output ONLY a newline-separated list where each line
            follows the format:
            
            ROLE: task description
            
            Available roles: HISTORY, DESIGNER, RESEARCHER, CODER, REVIEWER.
            Choose the most appropriate role for each sub-task. Be concise.
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
        log.info("Manager decomposing request: {}", task.id());

        String decomposition = chatClient.prompt()
                .user(task.description())
                .call()
                .content();

        log.info("Manager produced plan:\n{}", decomposition);
        return HandoffResult.terminal(AgentRole.MANAGER, decomposition, task.context());
    }
}

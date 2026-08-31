package com.enterprise.orchestrator.integration;

import com.enterprise.orchestrator.agent.impl.*;
import com.enterprise.orchestrator.handoff.HandoffResult;
import com.enterprise.orchestrator.model.AgentRole;
import com.enterprise.orchestrator.model.Task;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkflowIntegrationTest {

    @Test
    void managerAgent_buildsRoleSpecificWorkflowPlan() {
        ManagerAgent agent = new ManagerAgent(mockBuilder("DESIGNER: Create the shopping-cart total API\nCODER: Implement the endpoint\nREVIEWER: validate the result"));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.MANAGER, result.sourceRole());
        assertTrue(result.output().contains("DESIGNER:"));
        assertTrue(result.output().contains("CODER:"));
        assertTrue(result.output().contains("REVIEWER:"));
        assertTrue(result.isTerminal());
    }

    @Test
    void historyAgent_preservesOriginalRequestAndContext() {
        HistoryAgent agent = new HistoryAgent(mockBuilder("Historical context for the shopping cart total API. [HANDOFF:DESIGNER]"));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.HISTORY, result.sourceRole());
        assertEquals(AgentRole.DESIGNER, result.nextRecommended());
        assertTrue(result.output().contains("Historical context"));
        assertFalse(result.output().contains("[HANDOFF:DESIGNER]"));
    }

    @Test
    void designerAgent_usesOriginalRequestAndTechnologyConstraints() {
        DesignerAgent agent = new DesignerAgent(mockBuilder("Design a REST endpoint for the shopping cart total API in the selected stack."));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.DESIGNER, result.sourceRole());
        assertTrue(result.output().contains("selected stack"));
        assertTrue(result.output().contains("shopping cart"));
        assertTrue(result.isTerminal());
    }

    @Test
    void researcherAgent_relevantFindingsStayInScope() {
        ResearcherAgent agent = new ResearcherAgent(mockBuilder("Relevant pricing research for the shopping cart total API. [HANDOFF:CODER]"));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.RESEARCHER, result.sourceRole());
        assertEquals(AgentRole.CODER, result.nextRecommended());
        assertTrue(result.output().contains("shopping cart total API"));
        assertFalse(result.output().contains("[HANDOFF:CODER]"));
    }

    @Test
    void coderAgent_implementsRequestedFeatureInSelectedStack() {
        CoderAgent agent = new CoderAgent(mockBuilder("Implemented the cart total API in the selected stack. [HANDOFF:REVIEWER]"));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.CODER, result.sourceRole());
        assertEquals(AgentRole.REVIEWER, result.nextRecommended());
        assertTrue(result.output().contains("selected stack"));
        assertFalse(result.output().contains("[HANDOFF:REVIEWER]"));
    }

    @Test
    void reviewerAgent_reviewsOnlyRelevantImplementation() {
        ReviewerAgent agent = new ReviewerAgent(mockBuilder("Review passed for the shopping cart total API in the selected stack. [HANDOFF:CODER]"));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.REVIEWER, result.sourceRole());
        assertEquals(AgentRole.CODER, result.nextRecommended());
        assertTrue(result.output().contains("shopping cart total API"));
        assertFalse(result.output().contains("[HANDOFF:CODER]"));
    }

    @Test
    void testerAgent_validatesOriginalRequestAndStack() {
        TesterAgent agent = new TesterAgent(mockBuilder("Validated: selected stack, shopping cart total API only."));

        Task task = taskForShoppingCart();
        HandoffResult result = agent.execute(task);

        assertEquals(AgentRole.TESTER, result.sourceRole());
        assertTrue(result.output().contains("selected stack"));
        assertTrue(result.output().contains("shopping cart total API"));
        assertTrue(result.isTerminal());
    }

    private static Task taskForShoppingCart() {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("originalUserRequest", "Create a REST API to calculate the total price of items in a shopping cart.");
        context.put("context", new java.util.LinkedHashMap<String, Object>() {{
            put("technology", "Selected backend language");
            put("framework", "Selected web framework");
            put("database", null);
            put("externalServices", false);
        }});
        context.put("technologyConstraints", "Use the selected stack from the request context");
        context.put("previousAgentOutputs", new java.util.LinkedHashMap<String, String>());
        context.put("workflowStage", "REQUIREMENT_ANALYSIS");

        return new Task(
                "task-shopping-cart",
                "req-1",
                "Create a REST API to calculate the total price of items in a shopping cart.",
                context,
                java.time.Instant.now()
        );
    }

    private static ChatClient.Builder mockBuilder(String response) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(response);
        return builder;
    }
}

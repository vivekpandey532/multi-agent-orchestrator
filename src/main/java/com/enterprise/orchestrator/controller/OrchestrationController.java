package com.enterprise.orchestrator.controller;

import com.enterprise.orchestrator.model.OrchestrationRequest;
import com.enterprise.orchestrator.model.OrchestrationResponse;
import com.enterprise.orchestrator.orchestrator.AgentOrchestrator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * REST API for the Multi-Agent Orchestration Framework.
 * <p>
 * Provides both synchronous and SSE-streaming endpoints.
 */
@RestController
@RequestMapping("/api/v1/orchestrate")
public class OrchestrationController {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationController.class);

    private final AgentOrchestrator orchestrator;
    private final ExecutorService agentExecutor;

    public OrchestrationController(AgentOrchestrator orchestrator, ExecutorService agentExecutor) {
        this.orchestrator = orchestrator;
        this.agentExecutor = agentExecutor;
    }

    /** Synchronous orchestration — blocks until all agents complete. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<OrchestrationResponse> orchestrate(
            @RequestBody @Valid OrchestrationRequestBody body) {

        OrchestrationRequest request = new OrchestrationRequest(
                body.userRequest(), body.context() != null ? body.context() : Map.of());

        OrchestrationResponse response = orchestrator.orchestrate(request);
        return ResponseEntity.ok(response);
    }

    /** SSE streaming endpoint — sends step events as agents complete. */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter orchestrateStream(@RequestBody @Valid OrchestrationRequestBody body) {
        SseEmitter emitter = new SseEmitter(180_000L);

        agentExecutor.submit(() -> {
            try {
                OrchestrationRequest request = new OrchestrationRequest(
                        body.userRequest(), body.context() != null ? body.context() : Map.of());

                OrchestrationResponse response = orchestrator.orchestrate(request);

                for (var step : response.steps()) {
                    emitter.send(SseEmitter.event()
                            .name("step")
                            .data(step, MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(response, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception ex) {
                log.error("SSE orchestration error", ex);
                emitter.completeWithError(ex);
            }
        });

        return emitter;
    }

    record OrchestrationRequestBody(
            @NotBlank String userRequest,
            Map<String, Object> context
    ) {}
}

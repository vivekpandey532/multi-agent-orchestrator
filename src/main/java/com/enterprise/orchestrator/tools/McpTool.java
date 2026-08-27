package com.enterprise.orchestrator.tools;

/**
 * Represents a Model Context Protocol (MCP) tool that an agent can invoke.
 *
 * @param name        unique tool identifier
 * @param description human-readable description used for LLM function-calling
 * @param endpoint    REST/SSE endpoint the tool proxies to
 */
public record McpTool(
        String name,
        String description,
        String endpoint
) {}

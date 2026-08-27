# Multi-Agent Orchestration Framework

Enterprise-grade hierarchical AI agent orchestration using **Java 21 Virtual Threads** and **Spring AI**.

## Architecture

```
User Request
     │
     ▼
┌─────────────┐
│  REST API    │  POST /api/v1/orchestrate
│  Controller  │  POST /api/v1/orchestrate/stream (SSE)
└──────┬──────┘
       ▼
┌──────────────────┐
│ AgentOrchestrator │ ← Hierarchical Pattern
│                    │
│  1. Manager Agent  │ ← Decomposes request into sub-tasks
│  2. Fan-out to     │ ← Virtual threads (Project Loom)
│     Worker Agents  │
│  3. Handoff chains │ ← Agents can delegate to each other
│  4. Collect results│
└────────┬───────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌────────┐ ┌──────────┐
│History │ │ Designer │  ... more worker agents
│ Agent  │ │  Agent   │
└────┬───┘ └──────────┘
     │
     ▼
┌──────────────────┐
│ SharedBlackboard │ ← ConcurrentHashMap, session-scoped
└──────────────────┘
```

## Key Components

| Component | Description |
|---|---|
| `BaseAgent` | Interface every agent implements: `role()`, `goal()`, `tools()`, `execute()` |
| `AgentOrchestrator` | Central service: decomposes → routes → collects via virtual threads |
| `SharedBlackboard` | Thread-safe `ConcurrentHashMap`-backed short-term memory |
| `HandoffResult` | Record returned by agents to yield control or recommend next agent |
| `McpTool` | Model Context Protocol tool binding (name, description, endpoint) |
| `Task` | Immutable unit of work assigned to an agent |

## Running

```bash
# Set your OpenAI API key
export OPENAI_API_KEY=<your-key>

# Build
./mvnw clean package

# Run with preview features enabled (required for Java 21 virtual-thread usage)
./mvnw spring-boot:run
# or
java --enable-preview -jar target/multi-agent-orchestrator-1.0.0-SNAPSHOT.jar
```

## API

**Synchronous:**
```bash
curl -X POST http://localhost:8080/api/v1/orchestrate \
  -H "Content-Type: application/json" \
  -d '{"userRequest": "Design a scalable notification system"}'
```

**SSE Streaming:**
```bash
curl -X POST http://localhost:8080/api/v1/orchestrate/stream \
  -H "Content-Type: application/json" \
  -d '{"userRequest": "Design a scalable notification system"}'
```

## Adding a New Agent

1. Create a class implementing `BaseAgent` in `agent/impl/`
2. Add the role to `AgentRole` enum
3. Annotate with `@Component` — Spring auto-registers it with the orchestrator

## Tech Stack

- Java 21 (Virtual Threads)
- Spring Boot 3.4
- Spring AI (OpenAI)
- MCP tool protocol
- Micrometer metrics

# Architecture

As-built technical reference for `spring-ai-agents`. For a narrative walkthrough of
the underlying concepts, see [guide.md](guide.md); for the UI, see [ui-guide.md](ui-guide.md).

## System overview

The backend exposes a REST endpoint backed by an orchestrator agent (Claude, via
Spring AI), which can answer directly or delegate work to one of two
isolated-context sub-agents. A separate Spring Cloud Gateway can proxy API
requests to the backend. All agents share one persistent chat-memory store and
one sandboxed filesystem.

```mermaid
flowchart TB
    Browser["Browser\n(Claude Studio React SPA)"] -->|"POST /api/claude/chat"| Gateway["Spring Cloud Gateway\n(:8081, /api/**)"]
    Gateway -->|"forward /api/**"| Controller["ClaudeController\n(orchestrator ChatClient, :8080)"]

    Controller -->|"tool call: delegateToSubAgent"| Delegation["TaskDelegationTools"]
    Delegation --> SubAgents["SubAgentService"]
    SubAgents --> Research["research ChatClient\n(search + read-only file tools)"]
    SubAgents --> Code["code ChatClient\n(file + shell + search tools)"]

    Controller -->|"tool call: getCurrentDateTime"| AgentTools["AgentTools"]

    Research --> ToolLayer["FileTools / SearchTools / ShellTool"]
    Code --> ToolLayer
    ToolLayer -->|"sandboxed to"| Workspace["AgentWorkspace root\n(./agent-workspace)"]

    Controller -->|"ChatMemory reads/writes"| Memory["JdbcChatMemoryRepository"]
    Research -->|"ChatMemory reads/writes"| Memory
    Code -->|"ChatMemory reads/writes"| Memory
    Memory --> DB[("H2 file DB\n./agent-data/chat-memory\nAUTO_SERVER=TRUE")]

    Controller -->|"Anthropic API"| Claude["Claude Sonnet model"]
    Research -.->|"Anthropic API"| Claude
    Code -.->|"Anthropic API"| Claude
```

## API gateway architecture

The gateway ([gateway/](gateway/), port `8081`) has two faces: the proxied `/api/**` path used by pilot
users, and a loopback-only `/admin/**` console. Filter order matters: lower numbers run first.

```mermaid
flowchart LR
    subgraph Callers
        User["Pilot user / app\n(X-API-Key: gw_...)"]
        Admin["Operator browser\n(localhost only)"]
    end

    subgraph GW["Spring Cloud Gateway :8081"]
        direction TB
        subgraph Proxy["/api/** filter chain"]
            direction TB
            F1["1. RequestLogFilter (-3)\nrecords status, timing, user;\nmaps connect/timeout errors to 502/504"]
            F2["2. RateLimitFilter (-2)\nper-client-IP window, 429"]
            F3["3. ClientKeyFilter (-1)\npause (503), key check (401),\nper-user limit + daily cap (429),\nstrips X-API-Key"]
            F4["4. BackendUrlFilter\nrewrites target to current backend URL"]
            F1 --> F2 --> F3 --> F4
        end
        subgraph AdminSide["/admin/** (AdminAccessFilter: loopback + Host + X-Admin-Request)"]
            direction TB
            AC["AdminController\nstatus, settings, keys, client-keys,\nrequests, usage, report, test-call"]
            UI["Static admin UI\n(Overview, Users, Activity, Usage, Settings)"]
        end
        subgraph Stores["Stores (gateway-data/, files 0600)"]
            direction TB
            SS["SettingsStore\nsettings.json"]
            KS["KeyStore\nkeys.json + active-key.env"]
            CS["ClientKeyStore\nclient-keys.json (hashes only)"]
            RL["RequestLog\nrequests.jsonl (no bodies)"]
        end
    end

    Backend["spring-ai-agents backend :8080\n(/api/claude/chat)"]
    Anthropic["Anthropic API"]

    User --> F1
    F4 --> Backend
    Backend --> Anthropic
    Admin --> UI
    UI --> AC
    AC --> SS & KS & CS & RL
    AC -->|"health probe, Try it"| Backend
    F2 -.-> SS
    F3 -.-> SS
    F3 -.-> CS
    F4 -.-> SS
    F1 -.-> RL
    KS -. "source active-key.env, then restart backend" .-> Backend
```

Notes:

- The backend reads `ANTHROPIC_API_KEY` only at startup, so switching the active key in the admin UI means restarting the backend with `active-key.env` sourced (dotted line); the gateway never talks to Anthropic itself.
- Only metadata is stored: request logs hold method, path (no query), status, timing, user and client IP. Client keys are stored as SHA-256 hashes.
- Behaviour details are in [gateway-spec.md](gateway-spec.md) and [gateway-pilot-spec.md](gateway-pilot-spec.md).

## Components

| Component | File | Responsibility |
|---|---|---|
| API gateway | [gateway/](gateway/) | Standalone Spring Cloud Gateway service on port `8081`; forwards `/api/**` requests to the backend URL configured by `BACKEND_URL` (default `http://localhost:8080`), switchable at runtime. Also serves a loopback-only admin UI at `/admin/` (backend URL, rate limit, health, Anthropic key store, pilot users with per-user limits, persistent activity history, usage and report, pause switch; see [gateway diagram](#api-gateway-architecture), README and [gateway-spec.md](gateway-spec.md)). Settings and keys persist in `gateway/gateway-data/` (relative to the gateway's working directory). |
| Orchestrator | [ClaudeController.java](src/main/java/com/example/springaiagents/ClaudeController.java) | HTTP entry point (`POST /api/claude/chat`). Owns the top-level `ChatClient`, memory advisor, and system prompt that tells the model when to answer directly vs. delegate. Sets `conversationId` on `ConversationContext` for the duration of the request. |
| Demo tool | [AgentTools.java](src/main/java/com/example/springaiagents/AgentTools.java) | `getCurrentDateTime` — trivial `@Tool` available only to the orchestrator. |
| Task router / delegation tool | [agents/TaskDelegationTools.java](src/main/java/com/example/springaiagents/agents/TaskDelegationTools.java) | The orchestrator's only other tool: `delegateToSubAgent(agentType, task)`. The model itself decides whether/when to call it — there is no separate rule-based router. |
| Sub-agent runtime | [agents/SubAgentService.java](src/main/java/com/example/springaiagents/agents/SubAgentService.java) | Builds two isolated `ChatClient`s (`research`, `code`), each with its own system prompt and tool subset, sharing one `ChatMemory` instance. |
| Sub-agent type | [agents/SubAgentType.java](src/main/java/com/example/springaiagents/agents/SubAgentType.java) | `RESEARCH` / `CODE` enum with case-insensitive parsing from the tool argument. |
| Conversation propagation | [agents/ConversationContext.java](src/main/java/com/example/springaiagents/agents/ConversationContext.java) | Thread-local carrying the parent `conversationId` from the controller into whichever sub-agent the model delegates to within the same request thread. |
| Sandbox | [workspace/AgentWorkspace.java](src/main/java/com/example/springaiagents/workspace/AgentWorkspace.java) | Resolves all tool paths under one root directory; rejects any path that would escape it (`SecurityException`). |
| File tools | [tools/FileTools.java](src/main/java/com/example/springaiagents/tools/FileTools.java) | `readFile` / `writeFile` / `listFiles`, size-capped, sandboxed. |
| Search tools | [tools/SearchTools.java](src/main/java/com/example/springaiagents/tools/SearchTools.java) | `grep` (regex) / `glob`, result-capped, sandboxed. |
| Shell tool | [tools/ShellTool.java](src/main/java/com/example/springaiagents/tools/ShellTool.java) | `runCommand`, allow-listed commands only, no shell interpretation (`ProcessBuilder` arg list), timeout + output cap. |
| Frontend | [frontend/](frontend/) | React (Vite) SPA ("Claude Studio"), built by `frontend-maven-plugin` into `src/main/resources/static`; talks only to `/api/claude/chat`. |

## Request flow

```mermaid
sequenceDiagram
    actor User
    participant UI as Claude Studio (React)
    participant G as Spring Cloud Gateway (:8081)
    participant C as ClaudeController (:8080)
    participant Ctx as ConversationContext
    participant CC as Orchestrator ChatClient
    participant Tool as TaskDelegationTools
    participant Sub as SubAgentService
    participant Mem as JdbcChatMemoryRepository (H2)

    User->>UI: type prompt
    UI->>G: POST /api/claude/chat {prompt, conversationId}
    G->>C: forward request unchanged
    C->>Ctx: set(conversationId)
    C->>CC: prompt().user(...).advisors(CONVERSATION_ID=conversationId)
    CC->>Mem: load prior messages for conversationId
    CC->>CC: call Claude (tool-calling loop)
    alt simple question
        CC-->>C: direct answer
    else needs files/shell/search
        CC->>Tool: delegateToSubAgent(agentType, task)
        Tool->>Sub: run(type, task)
        Sub->>Ctx: get() parent conversationId
        Sub->>Mem: load/save under "{conversationId}:{type}"
        Sub-->>Tool: sub-agent result text
        Tool-->>CC: tool result
        CC-->>C: summarized answer
    end
    CC->>Mem: save new messages for conversationId
    C->>Ctx: clear()
    C-->>G: {"response": "..."}
    G-->>UI: {"response": "..."}
```

The gateway is an optional API entry point: the backend remains directly
reachable on port `8080`, and the built UI continues to be served by the backend.
The Vite development server routes `/api/**` through the gateway on port `8081`.

## Persistence

- **Store**: embedded H2, file-backed at `agent-data/chat-memory.mv.db`, accessed through Spring AI's `JdbcChatMemoryRepository` (single table `SPRING_AI_CHAT_MEMORY`). Schema auto-creates on startup (`spring.ai.chat.memory.repository.jdbc.initialize-schema=always`) using the Postgres-dialect DDL script, since H2 has no dedicated one and the repository's dialect resolver already treats unrecognized drivers as Postgres-compatible.
- **Sharing model**: the orchestrator and both sub-agents inject the *same* `ChatMemoryRepository` bean, so all conversation state lives in one table. Rows are namespaced by `conversation_id`: the orchestrator uses the caller-supplied id as-is; each sub-agent uses `"{conversationId}:research"` or `"{conversationId}:code"`.
- **Multi-process access**: the JDBC URL includes `AUTO_SERVER=TRUE`, so a second process can open the same DB file concurrently without lock errors (the first process transparently becomes a TCP server for the rest). For a fully standalone database instead of file-based embedded mode (e.g. shared Postgres/MySQL, or a dedicated H2 TCP server), override `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` / `_DRIVER` — no code changes required.
- **Config**: [application.yml](src/main/resources/application.yml).

## Security / sandboxing model

All filesystem and process access available to the LLM is confined to a single
directory tree and a small, explicit allow-list:

- `AgentWorkspace` resolves every relative path against one root (`agents.workspace.root`, default `./agent-workspace`) and throws `SecurityException` on any path that normalizes outside it (blocks `../` traversal).
- `ShellTool` only runs commands in `{ls, pwd, cat, wc, grep, find, echo, date}` — all read-only — via `ProcessBuilder` with an argument list (never `/bin/sh -c`), so there is no shell metacharacter injection surface. Commands run with a working directory pinned to the sandbox root, a 10s timeout, and a 4000-char output cap.
- `FileTools`/`SearchTools` cap read size and result counts to bound tool-output size.
- The chat-memory database directory (`agent-data/`) is intentionally kept outside the sandboxed workspace so the LLM's own file/shell/search tools can't read or tamper with conversation history.

## Configuration surface

| Property | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | (required) | Anthropic API credential |
| `ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Model id |
| `AGENTS_WORKSPACE_ROOT` | `./agent-workspace` | Sandbox root for file/search/shell tools |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./agent-data/chat-memory;AUTO_SERVER=TRUE` | Chat-memory datasource |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` / `_DRIVER` | `sa` / (empty) / `org.h2.Driver` | Chat-memory datasource credentials/driver |

## Known gaps / next steps

- No Agent Skills (Markdown + YAML front-matter) layer yet.
- No web-search tool; `research` sub-agent is limited to the local sandboxed workspace.
- Only two sub-agent types (`research`, `code`); no dedicated "data" agent.
- Routing is entirely LLM-driven (via the orchestrator's system prompt + tool description) — there is no deterministic/rule-based fallback router.

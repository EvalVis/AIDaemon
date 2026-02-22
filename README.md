# AI Daemon

A lightweight AI framework built on Spring Boot 4 and Spring AI. Connect any LLM provider, extend capabilities with skills and MCPs, schedule autonomous jobs, and interact through REST, Web UI, or terminal -- all from a single self-hosted service.

![Example use case](images/example.png)

## Architecture

```mermaid
graph TB
    User([User])

    subgraph Interface["Interface Layer"]
        REST[REST API]
        WebUI[Web UI]
        CLI[Interactive CLI]
    end

    User -->|HTTP / curl| REST
    User -->|Browser| WebUI
    User -->|Terminal| CLI
    WebUI --> REST

    subgraph Engine["AI Engine"]
        Chat[Chat Service]
        Conv[Conversation Manager]
        Providers[Provider Registry]
        Memory[Persistent Memory]
        Skills[Skills Reader]
        Shell[Shell Executor]
        Scheduler[Job Scheduler]
        MCPTools[MCP Tools]
    end

    subgraph Providers_Sub["LLM Providers"]
        OpenAI[OpenAI]
        Anthropic[Anthropic]
        Gemini[Gemini]
        Ollama[Ollama / Local]
    end

    subgraph External["External Integrations"]
        MCPRemote[Remote MCPs]
        MCPLocal[Local MCPs - stdio]
        Smithery[Smithery Skills Registry]
    end

    subgraph Storage["~/.aidaemon/"]
        ProvidersJSON[providers.json]
        MemoryJSON[memory.json]
        JobsJSON[jobs.json]
        ConversationsDir[conversations/]
        SkillsDir[skills/]
        McpsDir[mcps/]
    end

    REST --> Chat
    REST --> Conv
    CLI --> Conv

    Conv -->|multi-turn history| Chat
    Chat --> Providers
    Providers --> OpenAI & Anthropic & Gemini & Ollama

    Chat -->|tool calls| Memory & Skills & Shell & Scheduler & MCPTools

    Memory -->|read/write| MemoryJSON
    Skills -->|read| SkillsDir
    Scheduler -->|persist| JobsJSON
    Scheduler -->|executes via| Chat
    Conv -->|persist| ConversationsDir
    MCPTools --> MCPRemote & MCPLocal
    MCPRemote & MCPLocal -.->|config| McpsDir
    Smithery -->|install all files| SkillsDir
    Providers -.->|persist| ProvidersJSON

    Shell -->|guarded by flag| OS[Host OS]

    style User fill:#f9f,stroke:#333
    style Storage fill:#c9a800,stroke:#333,color:#fff
    style External fill:#1a7a7a,stroke:#333,color:#fff
```

### Delegation

When delegation is enabled, the main agent can spawn sub-agents to handle subtasks in parallel. Sub-agents run asynchronously; when they finish, the parent is woken with a status update and can synthesize results or send revision work.
Sub-agents can create their own subagents. The diagram shows one parent and only top subagents for simplicitly.

```mermaid
sequenceDiagram
    participant User
    participant Parent as Parent Agent
    participant DelegationService
    participant Sub1 as Sub-agent 1
    participant Sub2 as Sub-agent 2

    User->>Parent: Message
    Parent->>Parent: Estimates time > threshold
    Parent->>DelegationService: delegateToSubAgent x2
    DelegationService->>Sub1: Create and run sub-conversation
    DelegationService->>Sub2: Create and run sub-conversation
    Parent->>User: Delegated, will respond when ready
    Sub1->>DelegationService: Completed
    Sub2->>DelegationService: Completed
    DelegationService->>Parent: Delegation Status Update with sub results
    Parent->>Parent: Review, addWorkToSubAgent or synthesize
    Parent->>User: Final response
```

- **Tools:** `delegateToSubAgent(name, instruction)` creates a sub-conversation and queues it. `addWorkToSubAgent(subConversationId, instruction)` sends follow-up work to a sub-agent after reviewing its output.
- **Config:** `aidaemon.delegation-enabled=true`, `aidaemon.delegation-threshold-seconds=30`. The model is instructed to delegate when its estimated time exceeds the threshold.

## Features

- **Multi-provider** -- OpenAI, Anthropic, Gemini, Ollama. Add/remove providers at runtime with API key persistence.
- **Conversations** -- Stateful multi-turn sessions with full chat history, persisted under `~/.aidaemon/conversations/`. The provider (agent) is selected **per prompt**: set or change it for the conversation so the next message uses that provider. Conversations have creation timestamps and can be sorted by time.
- **Streaming** -- Conversation messages can be sent with `POST /api/conversations/{id}/messages/stream` (SSE). Chunks include `reasoning`, `answer`, and `tool` types so the UI can show thinking and tools in real time.
- **Delegation** -- Optional sub-agent delegation: the AI splits work into sub-conversations that run in parallel; when they complete, the parent is notified and can synthesize or request revisions. Enable with `aidaemon.delegation-enabled=true`.
- **Thinking / reasoning** -- Supported for providers that expose it (e.g. Anthropic extended thinking). Reasoning is streamed separately and shown in the Web UI; scheduled job results include thinking in the stored output.
- **Prompt caching** -- Conversation-history and prompt caching are used where supported (e.g. Anthropic, Gemini) to reduce cost and latency.
- **Context window** -- Optional `aidaemon.chars-context-window` (character limit) trims older messages so the prompt fits. The AI can use the `retrieveOlderMessages` tool to fetch older conversation content by index when context is trimmed.
- **Persistent memory** -- AI can save and recall information across sessions via `memory.json`.
- **Skills** -- Drop instruction files into `~/.aidaemon/skills/` or install from [Smithery](https://smithery.ai) via REST endpoint. The AI reads them for domain-specific context.
- **MCP support** -- Connect remote (Streamable HTTP, SSE) and local (stdio) MCP servers. Drop JSON configs into `~/.aidaemon/mcps/` and reload.
- **Scheduled jobs** -- AI creates cron jobs (recurring or one-time) that autonomously execute instructions on schedule. AI can also list and cancel them.
- **Shell access** -- Optionally allow the AI to execute host commands (git, docker, curl, etc.). Controlled by a global toggle endpoint.
- **Web UI** -- React app in `frontend/webui/` for providers, conversations, and streaming chat with reasoning and tool visibility.
- **Interactive CLI** -- Terminal-based chat with provider selection, conversation management, and shell toggle.

## Prerequisites

- Java 25+
- Maven (or use the included `mvnw` wrapper)

## Quick Start

```bash
# Clone and build
git clone <repo-url>
cd aidaemon
./mvnw spring-boot:run
```

On Windows PowerShell:
```powershell
.\mvnw.cmd spring-boot:run
```

The server starts on `http://localhost:8080`.

### 1. Register a Provider

```bash
curl -X POST http://localhost:8080/api/providers \
  -H "Content-Type: application/json" \
  -d '{"name":"my-gpt","type":"OPENAI","apiKey":"...","model":"gpt-4o"}'
```

Supported types: `OPENAI`, `ANTHROPIC`, `GEMINI`, `OLLAMA`

For Ollama (no API key needed):
```bash
curl -X POST http://localhost:8080/api/providers \
  -H "Content-Type: application/json" \
  -d '{"name":"local-llama","type":"OLLAMA","baseUrl":"http://localhost:11434","model":"llama3"}'
```

### 2. Chat (Stateless)

```bash
curl -X POST http://localhost:8080/api/chat/{providerId} \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"Hello!"}]}'
```

### 3. Conversations (Stateful)

Conversations are persisted under `~/.aidaemon/conversations/`. Each has an optional name. The provider is selected per prompt: set it when creating or via `PATCH`; the next message you send will use that provider.

```bash
# Create a conversation
curl -X POST http://localhost:8080/api/conversations \
  -H "Content-Type: application/json" \
  -d '{"name":"My chat","providerId":"<id>"}'

# Send messages (history is maintained server-side)
curl -X POST http://localhost:8080/api/conversations/{id}/messages \
  -H "Content-Type: application/json" \
  -d '{"message":"What did I just ask you?"}'

# Stream response (SSE: reasoning, answer, tool chunks)
curl -X POST http://localhost:8080/api/conversations/{id}/messages/stream \
  -H "Content-Type: application/json" \
  -d '{"message":"Explain X"}' \
  -N

# Set which provider to use for the next prompt(s)
curl -X PATCH http://localhost:8080/api/conversations/{id} \
  -H "Content-Type: application/json" \
  -d '{"providerId":"other-provider-id"}'
```

### 4. Interactive CLI

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=cli
```

On Windows PowerShell:
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=cli"
```

Commands inside CLI: `/quit`, `/new` (new conversation), `/shell` (toggle shell access).

### 5. Web UI

From the project root, build and run the React UI (proxy to the backend):

```bash
cd frontend/webui
npm install
npm run dev
```

Connect to the URL shown (e.g. Vite dev server). Configure providers and conversations, then chat; responses stream with reasoning and tool calls when available.

## Skills

Skills are folders containing a `SKILL.md` and optional resources (scripts, references, assets) that give the AI domain-specific knowledge.

**Manual install:** Drop a skill folder into `~/.aidaemon/skills/`.

**Install from Smithery:**
```bash
curl -X POST http://localhost:8080/api/skills/install/anthropics/skill-creator
```
This downloads all files (SKILL.md, scripts, references, etc.) from the skill's GitHub repository.

**Manage skills:**
```bash
# List installed skills
curl http://localhost:8080/api/skills

# List files in a skill
curl http://localhost:8080/api/skills/skill-creator/files

# Remove a skill
curl -X DELETE http://localhost:8080/api/skills/skill-creator
```

## MCP Servers

Create JSON config files in `~/.aidaemon/mcps/`.

**Remote (Streamable HTTP):**
```json
{
  "name": "github",
  "type": "REMOTE",
  "url": "https://api.githubcopilot.com/mcp/",
  "headers": {
    "Authorization": "Bearer <token>"
  }
}
```

**Remote (Legacy SSE):**
```json
{
  "name": "my-sse-server",
  "type": "SSE",
  "url": "https://example.com/mcp/sse"
}
```

**Local (stdio):**
```json
{
  "name": "aviation",
  "type": "LOCAL",
  "command": "npx",
  "args": ["-y", "aviationstack-mcp-server"],
  "env": {
    "AVIATIONSTACK_API_KEY": "<key>"
  }
}
```

```bash
# List connected MCPs
curl http://localhost:8080/api/mcps

# Reload after adding/editing config files
curl -X POST http://localhost:8080/api/mcps/reload
```

## Shell Access

Shell access is **disabled by default**. Toggle it at runtime:

```bash
# Enable
curl -X POST http://localhost:8080/api/shell-access/enable

# Disable
curl -X POST http://localhost:8080/api/shell-access/disable

# Check status
curl http://localhost:8080/api/shell-access
```

When enabled, the AI can execute arbitrary commands on the host system.

## Scheduled Jobs

The AI can create cron jobs via tool calls. You can also manage them through REST:

```bash
# List all jobs
curl http://localhost:8080/api/jobs

# View execution results
curl http://localhost:8080/api/jobs/results

# Cancel a job
curl -X DELETE http://localhost:8080/api/jobs/{id}
```

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/providers` | List registered providers |
| `POST` | `/api/providers` | Register a new provider |
| `DELETE` | `/api/providers/{id}` | Remove a provider |
| `POST` | `/api/chat/{providerId}` | Stateless chat |
| `POST` | `/api/conversations` | Create a conversation |
| `PATCH` | `/api/conversations/{id}` | Set provider for next prompt(s) (e.g. providerId) |
| `POST` | `/api/conversations/{id}/messages` | Send a message |
| `POST` | `/api/conversations/{id}/messages/stream` | Send a message (SSE stream) |
| `GET` | `/api/conversations` | List conversations |
| `DELETE` | `/api/conversations/{id}` | Delete a conversation |
| `GET` | `/api/skills` | List installed skills |
| `GET` | `/api/skills/{name}/files` | List skill files |
| `POST` | `/api/skills/install/{namespace}/{slug}` | Install from Smithery |
| `DELETE` | `/api/skills/{name}` | Remove a skill |
| `GET` | `/api/mcps` | List connected MCP servers |
| `POST` | `/api/mcps/reload` | Reload MCP configurations |
| `GET` | `/api/shell-access` | Shell access status |
| `POST` | `/api/shell-access/enable` | Enable shell access |
| `POST` | `/api/shell-access/disable` | Disable shell access |
| `GET` | `/api/jobs` | List scheduled jobs |
| `GET` | `/api/jobs/results` | View job execution results |
| `DELETE` | `/api/jobs/{id}` | Cancel a job |

## Data Directory

All persistent data lives in `~/.aidaemon/`:

```
~/.aidaemon/
├── providers.json     # Registered LLM providers and API keys
├── memory.json        # AI's persistent memory
├── jobs.json          # Scheduled job definitions
├── conversations/     # Conversation JSON files (stateful chats)
├── skills/            # Skill folders (SKILL.md + resources)
└── mcps/              # MCP server config JSON files
```

## Configuration

Optional `application.yaml` / env properties:

| Property | Default | Description |
|----------|---------|-------------|
| `aidaemon.config-dir` | `~/.aidaemon` | Data directory |
| `aidaemon.shell-access` | `false` | Allow AI shell execution |
| `aidaemon.delegation-enabled` | `false` | Enable sub-agent delegation |
| `aidaemon.delegation-threshold-seconds` | `30` | Estimated seconds above which the model should delegate |
| `aidaemon.chars-context-window` | `0` | Max characters of history sent to the model (0 = no trim). Use with `retrieveOlderMessages` for long chats |
| `aidaemon.system-instructions` | (see application.yaml) | System prompt for the model |

## Caution

- **API keys are stored in plain text** in `~/.aidaemon/providers.json`. Protect this file accordingly.
- **Shell access grants the AI full command execution** on your machine. Only enable it in trusted environments and disable it when not needed.
- **Scheduled jobs run autonomously** using your configured provider and consume API tokens. Monitor your jobs and cancel any you no longer need.
- **MCP servers may expose powerful capabilities** (file system access, API calls, etc.). Review MCP configurations before connecting them.
- This project uses Spring AI milestone releases which may have breaking changes between versions.

## Contributing

Contributions are welcome! Whether it's bug fixes, new provider integrations, additional tools, or documentation improvements -- all PRs are appreciated.

If you have ideas for new features or find issues, please open a GitHub issue to discuss before submitting a PR.

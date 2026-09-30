# Spring AI Anthropic Claude

This project exposes a small REST API backed by Anthropic Claude through Spring AI,
with a React chat UI ("Claude Studio") served from the same Spring Boot app.

## Run it

Set an Anthropic API key, then start the application:

```bash
export ANTHROPIC_API_KEY=your-api-key
mvn spring-boot:run
```

`mvn spring-boot:run` builds the [frontend/](frontend/) React app (via
`frontend-maven-plugin`) into `src/main/resources/static` before starting the
server, so no separate frontend build step is required.

Open http://localhost:8080/ for the browser chat interface.

Call Claude:

```bash
curl -X POST http://localhost:8080/api/claude/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Explain dependency injection in one sentence."}'
```

Pass a `conversationId` in the request body to keep multi-turn context across
calls (backed by `ChatMemory`); omit it to use the default conversation.

## Persistence

Chat memory (for both the orchestrator and any sub-agents it delegates to) is
stored in an embedded H2 database file at `agent-data/chat-memory.mv.db`, so
conversations survive restarts. The JDBC URL includes `AUTO_SERVER=TRUE`,
which lets a second process open the same file concurrently without lock
errors — the first process transparently becomes a server for the others.

For a true standalone database (e.g. a shared Postgres/MySQL instance, or a
dedicated H2 TCP server) instead of the file-based default, override the
datasource without touching code:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://host:5432/chat_memory'
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=secret
export SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
```

(add the corresponding JDBC driver dependency if you switch away from H2).

## Frontend development

For UI iteration with hot reload, run the React app on its own dev server
(proxies `/api` calls to the backend on port 8080):

```bash
cd frontend
npm install
npm run dev
```


The model and temperature are configured in `src/main/resources/application.yml`.
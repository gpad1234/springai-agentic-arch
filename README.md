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

## API gateway

The standalone Spring Cloud Gateway in [gateway/](gateway/) forwards `/api/**`
requests to the backend. Run the backend as above, then start the gateway in a
second terminal:

```bash
mvn -f gateway/pom.xml spring-boot:run
```

The gateway listens on port 8081 and preserves the request path. Use
`http://localhost:8081/api/claude/chat` as the API entry point; set `BACKEND_URL`
to change the backend address. The backend remains directly reachable on port
8080. The UI is still served by the backend, while the Vite development server
proxies API calls through the gateway.

### Gateway admin UI

Full design, use cases and endpoint reference: [gateway-spec.md](gateway-spec.md). Roadmap: [vision.md](vision.md); next-phase spec: [gateway-v2-spec.md](gateway-v2-spec.md).

Open http://localhost:8081/admin/ (local access only) to:

- see backend health/latency, the active key and the rate limit at a glance;
- switch the **backend URL** and set a per-client **rate limit** (requests per
  minute, `0` = off) — both apply immediately and persist;
- manage labelled **Anthropic API keys**: add, delete and mark one as active
  (the UI only ever shows the last 4 characters);
- watch the **recent request log** (method, path, status, duration).

The backend reads its Anthropic key at startup, so switching a key takes effect
when you restart the backend with the active key the UI writes to
`gateway/gateway-data/active-key.env` (the UI shows the exact command with the
full path):

```bash
. gateway/gateway-data/active-key.env && mvn spring-boot:run
```

Keys, settings and the env file live in `gateway/gateway-data/` when the gateway
is started with `mvn -f gateway/pom.xml spring-boot:run`, because the default
`./gateway-data` is relative to the gateway's working directory (override with
`GATEWAY_DATA_DIR`), are created readable only by your user, and are
git-ignored. They are stored in plain text — intended for local development.
`/admin/**` rejects non-loopback clients and non-local `Host` headers.

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

For UI iteration with hot reload, run the backend and API gateway as described
above, then run the React app on its own dev server. Vite proxies `/api` calls
to the gateway on port 8081:

```bash
cd frontend
npm install
npm run dev
```


The model and temperature are configured in `src/main/resources/application.yml`.
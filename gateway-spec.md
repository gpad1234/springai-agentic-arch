# API Gateway — Design Spec and Use Cases

As-built specification for the [gateway/](gateway/) module (Spring Cloud Gateway,
WebFlux, port `8081`). For setup and how to run it, see [guide.md](guide.md); for
the system context, see [architecture.md](architecture.md).

## 1. Purpose

A small front door for the `spring-ai-agents` backend that lets a developer, from
one local page:

- route API traffic to a chosen backend and change it without restarting,
- protect the backend from runaway callers with a rate limit,
- keep several labelled Anthropic API keys and pick the active one,
- see whether the backend is healthy and what traffic has passed through.

### Goals

- Forward `/api/**` to the backend unchanged (path, query, body).
- Runtime-editable settings that persist across gateway restarts.
- Simple, dependency-free admin UI (plain HTML/JS, no build step).
- Safe defaults for local development: admin reachable from the local machine only.

### Non-goals

- Production-grade authentication, multi-user admin, or audit logging.
- Encrypted secret storage (keys are plain text in a protected local file).
- Live key switching inside a running backend (see Decision D4).
- Load balancing, multiple routes, TLS termination, or shared rate limiting
  across several gateway instances.

## 2. Actors

| Actor | Description |
|---|---|
| **API client** | Browser UI, `curl`, or another service calling `/api/**` through the gateway. |
| **Developer/operator** | The person on the machine running the gateway who uses the admin UI. |
| **Backend** | The Spring Boot app (`ClaudeController`) the gateway forwards to. |

## 3. Use cases

### UC1 — Call the API through the gateway
- **Actor:** API client. **Trigger:** `POST http://localhost:8081/api/claude/chat`.
- **Flow:** the gateway logs the request, applies the rate limit, forwards to the
  current backend URL, and returns the backend's response unchanged.
- **Result:** same response as calling the backend directly; the call appears in
  the recent-request log.
- **Alternatives:** limit exceeded → `429` with `Retry-After: 60`; backend down →
  an error status (observed `500`) and a log entry with that status.

### UC2 — Check backend health
- **Actor:** Operator. **Trigger:** open the admin page (auto-refresh every 5 s).
- **Flow:** `GET /admin/status` probes `GET /` on the backend with a 2 s timeout.
- **Result:** green "up (HTTP n)" with latency, or red "unreachable"; also shows the
  active key label and rate limit.
- **Note:** any HTTP response (even 404) counts as reachable; only a connection
  failure or timeout counts as unreachable.

### UC3 — Switch the backend URL
- **Actor:** Operator. **Trigger:** edit *Backend URL* and Save.
- **Flow:** the value is validated, normalized to `scheme://host[:port]`, written to
  `settings.json`, and used for the next proxied request.
- **Result:** traffic goes to the new backend immediately; the choice survives a
  gateway restart.
- **Alternatives:** invalid value (non-http(s), path, query, credentials, blank) →
  `400` with an error message; nothing changes.

### UC4 — Limit traffic
- **Actor:** Operator. **Trigger:** set *Rate limit* (requests/min per client, `0` = off).
- **Flow:** each proxied request increments a per-client counter in a fixed
  one-minute window; over the limit returns `429`.
- **Result:** takes effect immediately; persisted.
- **Bounds:** 0–100000.

### UC5 — Manage Anthropic keys
- **Actor:** Operator. **Trigger:** add, activate, or delete a key in the UI.
- **Flow:** keys are stored with a label; the UI only ever receives a masked form
  (`••••` + last 4 characters). The first key added becomes active. The active key
  is mirrored to `active-key.env`.
- **Result:** `active-key.env` contains `export ANTHROPIC_API_KEY=<active key>`;
  deleting the active key removes the file.
- **Alternatives:** blank/long label or a key outside `[A-Za-z0-9_-]{8,256}` → `400`.

### UC6 — Switch the key used by the backend
- **Actor:** Operator. **Trigger:** *Make active*, then restart the backend.
- **Flow:** the UI shows `. <path>/active-key.env && mvn spring-boot:run`. The
  backend reads `ANTHROPIC_API_KEY` once at startup, so the new key applies after
  the restart.
- **Result:** the backend uses the newly active key.

### UC7 — Inspect recent traffic
- **Actor:** Operator. **Trigger:** view *Recent requests*.
- **Result:** newest-first list of the last 200 proxied calls: time, method, path
  (no query string), status, duration (ms), client IP. Held in memory only.

## 4. Interfaces

### 4.1 Proxied API
| Route id | Predicate | Target |
|---|---|---|
| `spring-ai-agents-api` | `Path=/api/**` | Current backend URL (default `BACKEND_URL`, `http://localhost:8080`) |

Everything outside `/api/**` and `/admin/**` is not routed.

### 4.2 Admin API (`/admin/**`)

All responses are JSON. Validation errors are `400 {"error": "<message>"}`.
Access rules are in §6.

| Method & path | Purpose | Request body | Success response |
|---|---|---|---|
| `GET /admin`, `GET /admin/` | Redirect to the UI | – | `302` → `/admin/index.html` |
| `GET /admin/index.html` | The admin UI | – | `200` HTML |
| `GET /admin/status` | Backend health + summary | – | `{backendUrl, reachable, httpStatus, latencyMs, rateLimitPerMinute, activeKeyLabel}` |
| `GET /admin/settings` | Current settings | – | `{backendUrl, rateLimitPerMinute}` |
| `PUT /admin/settings` | Update settings (either field optional; omitted = unchanged) | `{backendUrl?, rateLimitPerMinute?}` | `{backendUrl, rateLimitPerMinute}` |
| `GET /admin/keys` | List keys (masked) | – | `{keys: [{id, label, masked, createdAt, active}], activeKeyFile}` |
| `POST /admin/keys` | Add a key | `{label, key}` | `201 {id, label, masked, createdAt, active}` |
| `POST /admin/keys/{id}/activate` | Make a key active | – | same as `GET /admin/keys` |
| `DELETE /admin/keys/{id}` | Delete a key | – | same as `GET /admin/keys` |
| `GET /admin/requests` | Recent proxied requests | – | `[{time, method, path, status, durationMs, client}]` |

Example:

```bash
curl -X PUT http://localhost:8081/admin/settings \
  -H 'X-Admin-Request: 1' -H 'Content-Type: application/json' \
  -d '{"backendUrl":"http://localhost:8080","rateLimitPerMinute":30}'
```

## 5. Design

### 5.1 Request pipeline (proxied calls)

| Order | Component | Responsibility |
|---|---|---|
| -2 | `RequestLogFilter` | Times the request and records method, path, final status, duration, client. |
| -1 | `RateLimitFilter` | Per-client fixed-window counter; `429` + `Retry-After: 60` when exceeded; skipped when the limit is `0`. |
| after route-to-URL | `BackendUrlFilter` | Rewrites scheme/host/port of the matched route to the currently configured backend URL. |

The route's own URI is only the initial default; the filter makes settings changes
live without rebuilding routes.

### 5.2 Components

| Class | Role |
|---|---|
| `SettingsStore` | Holds and validates `backendUrl` and `rateLimitPerMinute`; persists `settings.json`. |
| `KeyStore` | Holds labelled keys and the active id; persists `keys.json`; mirrors the active key to `active-key.env`. |
| `RequestLog` | Bounded (200) in-memory deque of recent requests. |
| `AdminController` | The `/admin/**` endpoints in §4.2. |
| `AdminAccessFilter` | Enforces the admin access rules in §6. |
| `SecureFiles` | Atomic writes of owner-only (`rw-------`) files. |
| `static/admin/index.html` | The UI; builds the DOM with `textContent` (no HTML injection from stored values). |

### 5.3 Persistence

Directory: `GATEWAY_DATA_DIR` (default `./gateway-data`, relative to the gateway's
working directory, i.e. `gateway/gateway-data` with `mvn -f gateway/pom.xml`;
git-ignored).

| File | Content |
|---|---|
| `settings.json` | `{backendUrl, rateLimitPerMinute}`; an invalid or unreadable file falls back to defaults. |
| `keys.json` | All keys (plain text) and the active id. |
| `active-key.env` | `export ANTHROPIC_API_KEY=<active key>`; absent when no key is active. |

### 5.4 Configuration

| Variable | Default | Purpose |
|---|---|---|
| `BACKEND_URL` | `http://localhost:8080` | Initial backend (overridden by saved settings). |
| `SERVER_PORT` | `8081` | Gateway port. |
| `GATEWAY_DATA_DIR` | `./gateway-data` | Where settings/keys live. |
| `RATE_LIMIT_PER_MINUTE` | `0` | Initial rate limit (overridden by saved settings). |

### 5.5 Key decisions

| # | Decision | Rationale / consequence |
|---|---|---|
| D1 | Standalone module, backend unchanged | Backend and UI keep working without the gateway. |
| D2 | Backend URL applied by a filter, not by editing routes | Instant switching with no route reload. URL is normalized to `scheme://host[:port]` so it can't smuggle a path or credentials. |
| D3 | Rate limit keyed on the TCP peer address, fixed 1-minute window | Simple and predictable. `X-Forwarded-For` is deliberately ignored (spoofable). Behind another proxy all callers share one IP. |
| D4 | Key switching = write an env file + restart backend | Chosen for simplicity. The backend reads its key at startup, so no per-request key plumbing is needed. |
| D5 | Plain-text key store with `0600` permissions | Suitable for local dev only. Key charset is restricted so the env file can never contain shell metacharacters. |
| D6 | Masked keys only over the API | The full key never leaves the gateway process except in `active-key.env`. |
| D7 | Plain HTML/JS UI | No build step or dependencies; trivially served from the gateway jar. |

## 6. Security model

`/admin/**` (all methods) is allowed only when **all** of these hold:

1. The client address is loopback.
2. The `Host` header is `localhost`, `127.0.0.1`, or `::1` (blocks DNS rebinding).
3. For anything other than `GET`/`HEAD`, the `X-Admin-Request` header is present
   (blocks cross-site form posts; browsers can't add it cross-origin without CORS
   approval, which the gateway doesn't grant).

Otherwise the response is `403` with no body. `/api/**` is not authenticated by the
gateway.

Residual risks: any local user/process on the machine can use the admin API and
read the key files if file permissions allow; keys are not encrypted; the gateway
binds to all interfaces (admin is still blocked for non-loopback clients, but
`/api/**` is reachable from the network).

## 7. Limitations and possible next steps

- Rate limit state and request log are per-process and in memory (lost on restart,
  not shared between instances).
- A single route (`/api/**`) and a single backend.
- Backend connection failures surface as `500` to the caller rather than `502`.
- Key switching needs a backend restart; live switching would need the backend to
  accept a key per request (small backend change).
- No authentication for `/api/**`; per-client API keys would be a natural addition.
- Plain-text key storage; an OS keyring or encrypted file would be the upgrade path.

## 8. Verification

- Automated: `mvn -f gateway/pom.xml test` (store validation, rate limiter, routing,
  admin access rules, live backend switching with a fake backend, request log).
- Manual: the checklist in [guide.md](guide.md#manual-test-checklist-gateway-admin-ui).

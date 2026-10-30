# Vision — Spring AI Agents and the API Gateway

Where this project is heading. This is direction, not commitment: the as-built
behavior is in [architecture.md](architecture.md) and [gateway-spec.md](gateway-spec.md);
the detailed spec for the first phase is [gateway-v2-spec.md](gateway-v2-spec.md).

## North star

A small, safe, observable control plane in front of an agent backend: you can see
what is happening, change how traffic and credentials flow without downtime, and
hand the API to other people or services without handing them your Anthropic key.

## Where we are today

- Gateway proxies `/api/**` to one backend, with a runtime-editable backend URL
  and per-IP rate limit.
- Local-only admin UI: health, key list, recent requests.
- Key switching requires restarting the backend; keys are plain text on disk.
- `/api/**` has no authentication; request log and rate-limit state are in memory.
- Backend failures surface as `500` instead of `502`/`504`.

## Principles

1. **Local-first, safe by default.** Anything risky (admin, secrets) stays on the
   loopback interface unless explicitly opened up.
2. **No secrets in the browser or in logs.** The UI only sees masked values.
3. **Change without restarts.** Settings, keys, and routes are runtime data.
4. **Small surface.** Prefer a few well-tested features over a platform.
5. **Observable.** Every proxied call is attributable and countable.

## Themes and enhancements

### A. Trust and access control
| Enhancement | Why |
|---|---|
| Client API keys for `/api/**` (issue, label, revoke; `X-API-Key`) | Share the API without sharing the Anthropic key; know who is calling. |
| Per-client rate limits and quotas (by client key, not just IP) | Fair use; works behind another proxy. |
| Option to bind the gateway to loopback only | Close the network exposure of `/api/**` for pure local use. |
| Encrypted key storage (OS keyring or passphrase-encrypted file) | Remove plain-text secrets from disk. |
| Admin authentication (token or OIDC) | Needed before the admin UI can leave the local machine. |

### B. Operability
| Enhancement | Why |
|---|---|
| Correct error mapping (`502` unreachable, `504` timeout) | Callers and logs can tell a backend outage from a bug. |
| Live key switching without a backend restart | Removes the main friction in the key workflow. |
| Active health checks with automatic fail-over to a standby backend | Availability. |
| Configurable timeouts and retries for idempotent calls | Resilience. |
| Actuator health/metrics endpoint | Standard monitoring hook. |

### C. Visibility
| Enhancement | Why |
|---|---|
| Persistent request/usage history (file or embedded DB), searchable | Survives restarts; supports review. |
| Usage and cost view per key / client / day (tokens, calls) | Spend awareness; catches runaway agents. |
| Latency and error-rate charts in the UI | Spot regressions at a glance. |
| Export (CSV/JSON) and log retention settings | Reporting and housekeeping. |

### D. Routing and platform
| Enhancement | Why |
|---|---|
| Multiple routes and backends (e.g. `/api/claude`, `/api/other`) | Grow beyond one service. |
| Weighted/canary routing between two backends | Safe rollouts of backend changes. |
| Model selection / provider switch (Anthropic, OpenAI, Ollama keys already in `.env`) | Compare or fail over between providers. |
| Streaming (SSE) pass-through for chat responses | Token-by-token UI. |
| Shared state (Redis) for rate limits and logs | Run more than one gateway instance. |

### E. Developer experience
| Enhancement | Why |
|---|---|
| `.env` auto-loading and a `./run.sh` that starts backend + gateway | One command to start. |
| Dockerfile / Compose for backend + gateway | Reproducible setup. |
| OpenAPI description of `/admin/**` | Discoverable, testable admin API. |
| CI running both test suites | Catches regressions. |

## Phasing

| Phase | Theme | Contents | Status |
|---|---|---|---|
| **1** | Trust and correctness | Correct `502`/`504` mapping; loopback-bind option; client API keys with per-client rate limits; live key switching | Specified in [gateway-v2-spec.md](gateway-v2-spec.md) |
| 2 | Visibility | Persistent history, usage/cost view, charts, export | Not specified |
| 3 | Resilience and routing | Health checks and fail-over, timeouts/retries, multiple routes, canary, Actuator | Not specified |
| 4 | Platform | Provider switching, SSE streaming, Redis shared state, admin auth, encrypted storage, Docker/CI | Not specified |

Ordering rationale: phase 1 fixes the sharpest edges in today's behavior and is a
prerequisite for sharing the API; later phases build on the identity (client keys)
and storage it introduces.

## Open questions

- Should client keys identify people, services, or both (affects quota model)?
- Is live key switching worth a backend change, or is restart-based switching enough
  once the workflow is scripted? (See the risks in the phase 1 spec.)
- Will the gateway ever run off the developer machine? If yes, admin auth and
  encrypted storage move up from phase 4.
- Which providers beyond Anthropic matter first?

## Success measures

- Switching key or backend takes seconds with no failed in-flight requests.
- No secret value appears in the UI, logs, or API responses.
- A new teammate gets from clone to working chat in under ten minutes using only the docs.
- Every gateway behavior in the spec has an automated test.

# Gateway Pilot-Readiness Spec

Defines what must be true to run a **customer pilot of a working prototype**, with
the pilot running **locally** (on the presenter's machine, or on a machine the
customer's technical contact can reach on the same network). Supersedes
[gateway-v2-spec.md](gateway-v2-spec.md) where they differ (see §2). Direction is in
[vision.md](vision.md); current behavior is in [gateway-spec.md](gateway-spec.md).
Status: **partly implemented**. Built: P1 (502/504 mapping), P3 (user keys, per-user limits, daily cap,
Users tab), P4 (persistent, filterable Activity), P5 without tokens/cost (Usage tab, CSV). Also built: Pause pilot switch, Overview dashboard, first-run checklist (P7, in the UI), onboarding message, inline limit editing, printable report (P8, no tokens/cost). Not yet built: P2
(bind address), P6 (quick start scripts), P9 (data controls), tokens/cost
(§6 spike). History retention is a setting in `settings.json` (`historyRetentionDays`, default 30), not a property.

## 1. Pilot goals and success criteria

The pilot should let a small number of named users use the agent API for real work
while you can see, bound, and report on that use.

| Goal | Success criterion |
|---|---|
| Safe access | Each pilot user has their own key; you can revoke it instantly; the Anthropic key is never shared. |
| Accountability | Every call is attributable to a user and survives restarts. |
| Control | A runaway user or script is capped without affecting others. |
| Evidence | At the review you can show per-user calls, errors, latency, and (if available) estimated cost. |
| Supportability | A fresh clone reaches a working chat in about 10 minutes; failures are diagnosable from the UI. |

### Non-goals
Hosting, multi-tenancy, SSO/admin auth, encrypted storage, charts, provider
switching, live key switching, SLAs. Each is listed in §9 with a trigger for adding it.

## 2. Changes from the phase 1 spec

| Item in gateway-v2-spec | Pilot decision | Reason |
|---|---|---|
| F1 error mapping | **Keep** (P1) | Clear errors for users; separates outages from bugs. |
| F2 loopback bind | **Keep** (P2), default changed (see P2) | Pilot is local. |
| F3 client keys | **Keep, expanded** (P3) | Core pilot need. |
| F4 live key switching | **Drop** | Highest risk, no pilot value; restart-based switching stays. |
| Persistent history, per-key usage | **Add** (P4, P5) | Needed for accountability and the review. |
| Docker Compose / quick start | **Add** (P6) | Reproducible setup. |
| Guided first run, pilot report | **Add** (P7, P8) | Onboarding and evidence. |

## 3. Requirements

### P1 — Upstream error mapping
Same as F1 in gateway-v2-spec: connection failure → `502`, response timeout → `504`
(`gateway.response-timeout`, default `120s`), JSON body
`{"error":"upstream_unavailable"|"upstream_timeout"}`, mapped status recorded in the log.

### P2 — Network exposure
- `GATEWAY_BIND_ADDRESS` sets `server.address`.
- **Default for pilot configurations (`.env.example`, Compose): `127.0.0.1`.** The
  code default stays "all interfaces" so existing behavior is unchanged.
- When the customer's contact must reach the gateway from another machine, set it to
  the LAN address and **require client keys** (P3). The UI banner shows the current
  bind address and warns when it is non-loopback while *Require client key* is off.
- `/admin/**` stays loopback-only regardless (current rule).

### P3 — Pilot user keys (client keys)
Behavior as F3 in gateway-v2-spec (hash-only storage, shown once, `X-API-Key`,
`401` rules, header stripped before forwarding, constant-time compare), plus:

- **Fields:** `label` (user/team), `note` (free text, e.g. contact), `createdAt`,
  `lastUsedAt`, `revoked`.
- **Limits per user:** requests/minute (as before) **and** a **daily request cap**
  (`dailyCap`, `0` = unlimited); exceeding it returns `429`
  `{"error":"daily_cap_reached"}`. Daily window resets at local midnight.
- **Require client key** defaults to **on** in the pilot configuration; the code
  default stays off.
- **Admin API:** `GET/POST /admin/client-keys`, `PUT /admin/client-keys/{id}`
  (label, note, limits), `DELETE /admin/client-keys/{id}` (revoke). The raw key
  appears only in the `POST` response.
- **UI:** a *Pilot users* panel: create (key shown once with a copy button), edit
  limits, revoke, with last-used and today's call count per user.

### P4 — Persistent request history
- Every proxied call is appended to `requests.jsonl` in the data dir (owner-only
  `0600`): `{time, method, path, status, durationMs, clientLabel, clientIp, errorCode?, usage?}`.
- No request/response bodies, no query strings, no key material are ever written.
- On startup the gateway loads the file to rebuild the in-memory recent list (last
  200) and per-user counters. A truncated last line is ignored.
- **Retention:** setting `historyRetentionDays` (default `30`); older lines are
  dropped by a rewrite at startup and once per day.
- At pilot scale (thousands of calls/day) a flat file is sufficient; if it
  outgrows that, the store interface is replaced by an embedded database without
  touching callers.

### P5 — Per-user usage view
- `GET /admin/usage?from=&to=` → per user: calls, errors (4xx/5xx counted
  separately), `429`s, p50/p95 latency, first/last seen; plus totals.
- `GET /admin/usage.csv?from=&to=` → same data as CSV (for the pilot review).
- **UI:** a *Usage* panel with a date-range selector and a table per user, plus
  totals. No charts in the pilot.
- **Tokens and cost (optional, see §6):** when the backend reports usage, the table
  also shows input/output tokens and an estimated cost computed from a
  configurable price table (`gateway.pricing.*`, per million tokens). When usage is
  not reported the columns show "n/a" rather than zero.

### P6 — Quick start
- `./run.sh` (or documented equivalent) loads `.env`, starts backend then gateway,
  waits for both health checks, and prints the URLs.
- `docker-compose.yml` with `backend` and `gateway` services, a named volume for
  `agent-data` and `gateway-data`, `.env` for secrets, and ports bound to
  `127.0.0.1` by default.
- `.env.example` with placeholder values (committed; `.env` stays ignored).
- Acceptance: a fresh clone reaches a working chat by following only the guide,
  in about 10 minutes.

### P7 — Guided first run
When there are no keys, no users, and no traffic, the admin UI shows a checklist:
1. Backend reachable ✔/✘ (with the URL and a fix hint).
2. Anthropic key present (reports whether the backend answered a test call).
3. At least one pilot user created.
4. **Send test request** button: calls `POST /api/claude/chat` through the gateway
   with the first pilot key and shows the response and latency.
The checklist disappears once all four are done.

### P8 — Pilot report
`GET /admin/report?from=&to=` returns a printable HTML page (also linked from the
UI): per-user usage table, error summary (top error types), latency summary, and
the date range. Intended to be saved as PDF for the pilot review. Contains no
secrets and no request content.

### P9 — Safe handling of pilot data
- Request/response bodies are never stored by the gateway.
- The backend's chat memory (`agent-data/`) holds prompts and answers; the guide
  documents where it lives and how to delete a user's conversations or wipe it
  (`conversationId` namespaces are the only user separation there, so a user can
  read another's history by guessing the ID — see risk R3).
- A short "what the pilot stores" section is added to the guide for the customer.

## 4. Data and API summary

| File (data dir) | Purpose | New? |
|---|---|---|
| `settings.json` | + `requireClientKey`, `historyRetentionDays` | extended |
| `client-keys.json` | hashed pilot keys, limits, notes | new |
| `requests.jsonl` | persistent call history | new |

| Endpoint | Purpose |
|---|---|
| `/admin/client-keys` (GET/POST), `/admin/client-keys/{id}` (PUT/DELETE) | P3 |
| `/admin/usage`, `/admin/usage.csv` | P5 |
| `/admin/report` | P8 |
| `/admin/status` | + `bindAddress`, `requireClientKey`, setup checklist flags (P7) |

All `/admin/**` endpoints keep the existing access rules.

## 5. Configuration additions

| Variable / property | Default (code) | Pilot value |
|---|---|---|
| `GATEWAY_BIND_ADDRESS` | all interfaces | `127.0.0.1` |
| `gateway.response-timeout` | `120s` | `120s` |
| `gateway.require-client-key` / `REQUIRE_CLIENT_KEY` (initial) | `false` | `true` |
| `historyRetentionDays` (in `settings.json`) | `30` | `30` |
| `gateway.pricing.input-per-mtok` / `output-per-mtok` | unset | set to the model's published prices |

## 6. Token and cost reporting — dependency and spike

The gateway sees only `{"response": "..."}` from the backend, so it cannot know
tokens. To report them the backend must expose usage, for example by returning
`X-Usage-Input-Tokens` / `X-Usage-Output-Tokens` headers computed from the Spring AI
`ChatResponse` metadata.

- **Spike (before committing):** check that Spring AI 1.0.2 exposes usage on the
  orchestrator call and decide how to include **sub-agent** usage (delegated
  `ChatClient` calls are separate model calls and would otherwise be missed).
- If feasible: a small backend change adds the headers (summed across orchestrator
  and sub-agent calls for the request); the gateway records them in `requests.jsonl`.
- If not feasible in time: ship P5 with calls/errors/latency only and show tokens/cost
  as "n/a". The pilot is still valuable; cost can be derived from the Anthropic
  console for the pilot period in the meantime.

## 7. Acceptance criteria (summary)

- Backend down → `502` JSON; slow backend → `504`; backend 4xx/5xx pass through.
- With *Require client key* on: no/invalid/revoked key → `401`; valid key proxied;
  `X-API-Key` not seen by the backend.
- Raw pilot key appears only once (create response); never in `GET`, logs, files, or report.
- Per-user minute limit and daily cap each return `429` for that user only.
- Restart the gateway: history, per-user counts, users, and settings are intact.
- Usage and CSV totals equal the count of lines in `requests.jsonl` for the range.
- Report page renders with the right totals and contains no secrets.
- Fresh clone → working chat via `./run.sh` or Compose following only the guide.
- First-run checklist shows correct states and the *Send test request* round trip works.

## 8. Test plan

- **Unit:** key hash/verify, minute and daily windows (including midnight reset),
  JSONL write/replay with truncated last line, retention pruning, usage aggregation,
  percentile calculation, price-table cost.
- **Integration (gateway + fake backend):** 502/504 mapping, `401` rules and header
  stripping, per-user `429`s, history persistence across a context restart,
  `/admin/usage` and CSV output, report content.
- **Manual:** extend the checklist in [guide.md](guide.md) for P2–P8, including a
  second device on the LAN with a pilot key.
- **Dry run:** run the full pilot script (§10) once before any customer sees it.

## 9. Deferred, with triggers

| Deferred | Add when |
|---|---|
| Admin authentication | The admin UI must be reachable off the machine. |
| Encrypted key storage | Keys beyond a trusted laptop, or a customer security review. |
| Live key switching | Restart-based switching becomes a real operational pain. |
| Charts, agent trace view | After pilot feedback, for a demo or funding conversation. |
| Per-user conversation isolation in the backend | More than one pilot user shares a deployment (see R3). |
| Hosted deployment, TLS | The pilot moves off a local machine. |

## 10. Pilot runbook (outline, for the guide)

1. **Prepare:** copy `.env.example` → `.env`, add the Anthropic key; `./run.sh`.
2. **Verify:** first-run checklist all green; *Send test request* works.
3. **Onboard users:** create one key per user with limits; hand over the key, the
   base URL, and a one-page "how to call the API" with a `curl` example.
4. **During the pilot:** check *Usage* and the log weekly; revoke or adjust limits as needed.
5. **Review:** export the report and CSV; collect user feedback; decide next steps.
6. **Wrap-up:** revoke all pilot keys; archive or delete `gateway-data/` and
   `agent-data/` per the customer's data expectations.

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Token/cost data unavailable (sub-agent usage hard to capture) | Spike in §6; fallback is calls/latency only. |
| R2 | Flat-file history grows or corrupts | Retention, tolerate truncated tail, store behind an interface for later replacement. |
| R3 | Pilot users can read each other's conversations by guessing a `conversationId` | Pilot with a small trusted group; document it; gateway could namespace IDs by client label (small, optional follow-up). |
| R4 | Backend cost overrun from one user | Per-user minute limit plus daily cap; Anthropic console spend limit as a backstop. |
| R5 | Local machine is the single point of failure (laptop sleeps) | Docs state the pilot's availability expectations; Compose can later move to a server. |
| R6 | Anthropic key leaks via `.env` or a shared machine | `.env` is git-ignored and `0600`; use a dedicated pilot key with a spend cap. |

## 12. Definition of done

- All acceptance criteria in §7 pass as automated tests (manual for the LAN check).
- `mvn test` and `mvn -f gateway/pom.xml test` are green.
- A full dry run of the runbook succeeds on a clean checkout.
- Docs updated: [gateway-spec.md](gateway-spec.md) (as-built), [architecture.md](architecture.md),
  [README.md](README.md), [guide.md](guide.md) (quick start, manual test, runbook,
  "what the pilot stores"), and [vision.md](vision.md) phase table.

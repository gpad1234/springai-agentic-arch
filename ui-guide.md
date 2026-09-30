# Claude Studio — Frontend Guide

The `frontend/` directory is a React ([Vite](https://vite.dev)) single-page app
that provides the "Claude Studio" chat UI for the `spring-ai-agents` backend.

## Structure

```
frontend/
  index.html          # Vite HTML entry point (title, #root mount point)
  vite.config.js       # dev proxy (/api -> :8080) + production build output
  src/
    main.jsx           # React root render
    App.jsx            # Chat UI: conversation state, composer, suggestions
    App.css             # Claude Studio theme (paper/ink color palette)
    index.css           # Minimal global reset
```

## How it talks to the backend

`App.jsx` holds the conversation in component state and POSTs each prompt to
`/api/claude/chat`:

```js
fetch('/api/claude/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ prompt: text, conversationId: conversationId.current }),
})
```

- `conversationId` is generated once per browser tab (`crypto.randomUUID()`)
  and reused for every message, so the backend's `ChatMemory` keeps the
  conversation's context across turns.
- The response body is `{ "response": "..." }`; it replaces the pending
  "Claude is thinking..." placeholder message.
- Suggestion buttons just populate the composer textarea — they don't send
  automatically.

## Running it

### Option A — integrated with the backend (no separate dev server)

`frontend-maven-plugin` is wired into [pom.xml](pom.xml) on the
`generate-resources` phase, so any Maven lifecycle that reaches that phase
(`mvn compile`, `mvn test`, `mvn spring-boot:run`, `mvn package`) will:

1. Install a local Node/npm (if not already installed under `frontend/node`).
2. Run `npm install` in `frontend/`.
3. Run `npm run build`, which builds the SPA straight into
   `src/main/resources/static` (see `build.outDir` in
   [frontend/vite.config.js](frontend/vite.config.js)).

So `mvn spring-boot:run` alone serves the built UI at http://localhost:8080/.

### Option B — hot-reload frontend dev server

For UI iteration, run Vite's dev server directly against a running backend:

```bash
# terminal 1 — backend
export ANTHROPIC_API_KEY=your-api-key
mvn spring-boot:run

# terminal 2 — frontend with hot reload
cd frontend
npm install   # first time only
npm run dev
```

Vite prints a local URL (typically http://localhost:5173/). Requests to
`/api/**` are proxied to `http://localhost:8080` (configured in
`vite.config.js`), so the backend must be running for chat requests to work.

## Other npm scripts

Run from inside `frontend/`:

- `npm run build` — production build into `../src/main/resources/static`.
- `npm run preview` — serve the production build locally for a final check.
- `npm run lint` — run Oxlint over `src/`.

## Styling conventions

- Colors and fonts are defined as CSS custom properties at the top of
  [frontend/src/App.css](frontend/src/App.css) (`--ink`, `--accent`, etc.);
  change the palette there rather than hunting for hard-coded colors.
- Layout is plain CSS Grid/Flexbox — no CSS framework or component library is
  used, keeping the bundle dependency-free beyond React itself.

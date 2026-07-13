# SkillForge Dashboard

React dashboard for SkillForge. It covers login, chat, session replay, agents, tools, skills, memory, traces, model usage, teams, evals, channels, and hook methods.

## Stack

- React 19 + TypeScript 5.9
- Vite 8
- Ant Design 6
- TanStack Query
- Axios with Bearer-token injection
- Vitest and Playwright

## Development

```bash
npm install
npm run dev
```

The Vite dev server runs at `http://localhost:3000` and proxies API calls to the Spring Boot server on `http://localhost:8080`.

For iOS pairing with LAN-first and Tailscale fallback, copy `.env.example` to `.env.local`, replace both
addresses with reachable values for the current Mac, and restart Vite. A newly generated Mobile Devices QR
will then contain `window.location.origin` plus the configured endpoint candidates. Existing iOS pairings must
scan a new QR once because older Keychain records contain only the endpoint present when they were paired.

## Scripts

| Command | Purpose |
|---------|---------|
| `npm run dev` | Start Vite dev server |
| `npm run build` | Type-check and build production assets |
| `npm run lint` | Run ESLint |
| `npm run test` | Run Vitest tests |
| `npm run test:e2e` | Run Playwright tests |

## Main Routes

| Route | Page |
|-------|------|
| `/login` | Local token login |
| `/` | Dashboard overview |
| `/chat` / `/chat/:sessionId` | Chat workspace |
| `/agents` | Agent management |
| `/tools` | Java tool catalog |
| `/skills` | Skill catalog, drafts, evolution |
| `/sessions` | Session list |
| `/memories` | Memory lifecycle management |
| `/usage` | Model usage dashboards |
| `/traces` | Trace browser |
| `/teams` | Multi-agent collaboration |
| `/eval` | Eval runs and scenarios |
| `/channels` | Feishu / Telegram channel gateway |
| `/hooks` | Script and compiled hook methods |

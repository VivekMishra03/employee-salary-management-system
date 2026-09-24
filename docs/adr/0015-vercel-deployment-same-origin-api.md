# ADR-0015 — Vercel frontend reaches the API through a same-origin rewrite

- **Status:** Accepted — ⚠ deviates in method, not in intent, from NFR-4's CORS wording
- **Date:** 2026-09-24
- **Implements:** the frontend half of deployment (requirements.md section 8), NFR-4, NFR-6

## Context
The Angular app calls relative URLs such as `/api/v1/employees`. In development the dev server proxies them to
the API (`frontend/proxy.conf.json`). On Vercel there is no such proxy, so the same calls would reach the static
site and fail. ADR-0009 deferred CORS to deployment, and NFR-4 says CORS must be restricted to the deployed
frontend origin, never `*`.

## Decision
`frontend/vercel.json` rewrites `/api/*` to the Render service and sends every other path to `index.html` so
Angular's routes work on reload. The browser only ever talks to the Vercel origin, so **no CORS headers are
needed and none are added**. The frontend has **no environment variables**.

## Why
- **Same effect as the requirement, with less surface.** NFR-4's aim is that no other site can call the API from
  a browser. Without CORS headers a browser blocks every cross-origin call to the API already, which is stricter
  than allowing one origin. There is no allow-list to get wrong or to widen by accident.
- **No API address in the bundle.** The app keeps its relative URLs, so nothing about the backend location is
  compiled in, and the same build works locally, on previews and in production.
- **No secrets on Vercel.** Vercel offers every name in the repository's `.env.example` as an environment
  variable. Those are backend settings (database credentials, JWT secret, seed password), and none belongs on a
  static frontend project. They must be removed from the Vercel project's list.

## Consequences
- The Render address is written in `vercel.json`. It is a public address, not a secret; moving the API means
  changing that one file.
- Requests pass through Vercel's proxy. On Render's free plan the first request after idle takes about 30 s,
  and a proxy timeout could cut a very slow cold start short. Warm the service before a demo.
- Anyone calling the API directly with a tool (not a browser) is unaffected: authentication is still the JWT.
- The API itself still needs no CORS configuration. If a second frontend origin is ever added, decide then
  whether to add a CORS allow-list or another rewrite.
- Vercel project settings to use: root directory `frontend`, framework Angular; the build and output settings in
  `vercel.json` apply. Environment variables: none.

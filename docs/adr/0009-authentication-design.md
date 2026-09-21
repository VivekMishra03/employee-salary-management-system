# ADR-0009 — Authentication design: stateless HS256 JWT, generic failures, default-deny

- **Status:** Accepted
- **Date:** 2026-09-21
- **Implements:** FR-1.1, FR-1.2, FR-1.3 (server side), NFR-4

## Decision

| Concern | Choice |
|---|---|
| Token | JWT, **HS256**, signed with a secret from `JWT_SECRET` (min. 32 chars). Claims: `sub` (email), `uid`, `role`, `iat`, `exp` |
| Session | **Stateless** — no server session, no cookie. Client sends `Authorization: Bearer <jwt>` |
| Expiry | `JWT_EXPIRY_MINUTES`, default 60. Checked against the injected `Clock`, never the wall clock |
| Authorisation | **Default-deny**: only `POST /api/v1/auth/login` and `/actuator/health` are open; `anyRequest().authenticated()` covers everything else, including paths that do not exist |
| Login failures | One exception, one fixed message for unknown email / wrong password / disabled account |
| Errors | RFC 7807 `application/problem+json` with a stable `code` (`INVALID_CREDENTIALS`, `UNAUTHENTICATED`, `VALIDATION_FAILED`, `INTERNAL_ERROR`, or the HTTP status name) |

## Reasoning

- **HS256, not RS256.** One service both signs and verifies; there is no third party that needs to
  verify tokens without being able to mint them, which is the only thing asymmetric keys buy.
- **Stateless.** Matches the deployment (Render free tier sleeps; in-memory sessions would be lost)
  and removes the CSRF surface: nothing is sent automatically by the browser, so CSRF protection is
  disabled *deliberately* (commented in `SecurityConfig`). **If auth ever moves into a cookie, this
  must be revisited.**
- **Default-deny.** A new endpoint is protected unless someone adds it to the short whitelist on
  purpose. An unmapped path is also a 401 without a token, so the API does not reveal which paths
  exist; with a valid token it is an ordinary 404 (security and routing are separate concerns).
- **Indistinguishable login failures.** The response bodies are byte-identical (tested), and an
  unknown email still performs a BCrypt comparison against a precomputed hash so response *time*
  does not reveal whether the account exists.
- **The filter only establishes authentication, never rejects.** Rejection is the entry point's
  job. A stale token sent to login is therefore ignored rather than breaking sign-in. The filter is
  constructed in `SecurityConfig` and is deliberately **not** a `@Component`, which would register it
  in the servlet chain outside the security rules.
- **The catch-all handler re-throws Spring Security exceptions.** A blanket
  `@ExceptionHandler(Exception.class)` otherwise swallows an `AccessDeniedException` from a
  controller and turns a 403 into a 500 (tested).
- **Error bodies never echo input.** Validation errors name fields and messages, never values;
  unreadable-body errors use a fixed detail because Jackson's message quotes part of the request,
  which may be a password.

## Consequences and known limits

- **No revocation.** A token stays valid until it expires, including after an account is disabled or
  a password changes (up to `JWT_EXPIRY_MINUTES`). Accepted for a single-persona internal tool;
  the mitigation if it matters is a short expiry, or a token-version claim checked per request.
- **No refresh tokens.** The UI redirects to login on 401 (FR-1.3), so a session lasts one expiry.
- **CORS is not configured yet.** NFR-4 requires it restricted to the deployed frontend origin. It is
  deferred to when the Angular app first calls the API (M8) and the origin is known (M9); until then
  a browser on another origin cannot call the API, which is the safe default.
- **No user exists until the seed runs (M5).** Tests create users directly; the seed will create the
  HR Manager account with a BCrypt hash.
- **Test secret.** `src/test/resources/application.properties` holds a dummy signing secret so
  `@SpringBootTest` contexts can start. It is test-classpath only and protects nothing;
  `application.yml` commits none (`JwtPropertiesTest#applicationYml_hasNoCommittedSecret`).
- **Package placement.** ADR-0008 fixes the package set, so the JWT filter, entry point and security
  config live in `config/`, and the `AuthenticatedUser` principal in `dto/`.

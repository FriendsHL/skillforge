# Security Review Rules

Read this for auth, authorization, API endpoints, user input, file handling,
external calls, secrets, logs, webhooks, and dependency updates.

## High-Risk Patterns

- Hardcoded credentials, tokens, API keys, or connection strings.
- Secrets, tokens, passwords, PII, or session content in logs.
- String-concatenated SQL/JPQL/native queries.
- Shell command execution with user-controlled input.
- Path traversal through unchecked file paths.
- SSRF via user-provided URLs or configurable remote endpoints without allowlist.
- XSS via `innerHTML`, `dangerouslySetInnerHTML`, or unsanitized markdown/HTML.
- Missing auth checks on protected routes.
- Broken authorization when object ownership or user ID is trusted from client input.
- Insecure deserialization of untrusted payloads.
- Dependency CVEs introduced or ignored.

## SkillForge-Specific Notes

- Tool parameters and chat content are untrusted. Validate before invoking system
  tools, filesystem access, network fetches, or scripts.
- Single-tenant local auth still requires the Bearer-token interceptor on
  protected `/api/**` routes unless explicitly exempted.
- Do not expose stack traces, SQL errors, local paths, API keys, LLM payloads, or
  session transcripts to clients.
- MCP headers, provider keys, channel tokens, and webhook secrets must be
  resolved from environment/config and redacted in logs.

## Review Steps

- Search for credentials and `Bearer` literals.
- Inspect new endpoints for auth, validation, and error behavior.
- Inspect file path construction and external URL usage.
- Inspect logs near failures and auth/provider code for secret leakage.
- Run dependency audit commands when dependency changes are part of the task.

## Severity

- Blocker: any credential exposure, auth bypass, injection, SSRF, unsafe file
  access, or secret leak.
- Warning: weak validation, missing rate limit on public endpoint, or overly
  detailed client-facing error.

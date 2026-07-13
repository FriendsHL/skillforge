# IOS-ASSISTANT-COMPANION Tech Design

Date: 2026-07-09
Status: active / implementation-plan
Mode: Full

## Goal

Build a native iOS app that acts as the primary personal assistant entrypoint for
SkillForge. The app is not a mobile dashboard clone. It should let the user open
SkillForge from their phone, talk to the default assistant through chat, answer
human-in-the-loop prompts, receive task notifications, and send files or images
into a session.

The product direction is inspired by OpenClaw's iOS gateway pairing model:
SkillForge Server remains the control plane, while the iOS app pairs with the
user's own server and becomes a trusted companion client.

## Confirmed Decisions

- Product shape: personal assistant entrypoint, not mobile admin dashboard.
- User model: single-user-first MVP, with backend tables shaped for multi-user
  and multi-device expansion.
- Repository shape: keep V1 in the SkillForge monorepo as an independent
  `skillforge-ios/` Xcode project. Revisit repo split only after the app has a
  stable release cadence.
- Development install path: Xcode direct install to a real iPhone first.
  TestFlight and APNs certificate automation are follow-up once Apple Developer
  Program assets are available.
- Pairing model: SkillForge Dashboard/Server creates the QR/setup-code; the iOS
  app scans the Dashboard QR and claims the pairing.
- Dashboard placement: add a first-class `Mobile Devices` dashboard page rather
  than hiding iOS pairing under `Channels`.
- Endpoint model: QR payload contains an `endpoints` array. The app keeps the
  complete normalized set, selects reachable private LAN first, and falls back
  to Tailscale/public HTTPS without replaying business mutations.
- First screen after pairing: chat-first.
- iOS stack: native SwiftUI.
- Voice boundary: V1 relies on iOS system keyboard dictation. No custom audio
  recording, streaming voice, or transcription pipeline in V1.
- V1 approach: Assistant Companion.

## Non-Goals For V1

- No phone-as-tool-node protocol.
- No custom voice assistant or streaming audio conversation.
- No Apple Watch app.
- No cross-server failover, official relay, or blind retry of non-idempotent requests.
- No dashboard-grade management screens.
- No trace detail viewer.
- No access to contacts, calendar, location, photos library scanning, or other
  high-risk device capabilities beyond user-selected file/image upload.
- No SkillForge-operated relay service.

## Product Scope

V1 includes:

- QR pairing and manual setup-code fallback.
- Default assistant chat.
- Session list, session switching, message history, and foreground realtime
  updates.
- Running, idle, waiting-user, and error status display.
- Inline `ask_user` cards.
- Inline confirmation cards for engine and ACP permission confirmations.
- Image and file upload through the existing attachment path.
- APNs push registration and task notifications.
- Foreground state refresh after push or app resume.

V2 candidates:

- Share Extension for links, text, images, and files.
- Native voice input with app-owned recording and transcription.
- Recent tasks and notification inbox.
- Relay-backed connectivity when neither LAN nor user-managed Tailscale/public HTTPS is available.

V3 candidates:

- Device capability grants.
- Phone as a callable agent node.
- Apple Watch companion.
- Rich mobile-specific agent surfaces.

## Architecture Overview

```text
SkillForge Dashboard
  Mobile Devices page
  Pair iPhone QR/status polling
        |
        | dashboard Bearer token
        v
SkillForge Server
  Mobile Pairing API
  Mobile Device Auth
        ^
        | one-time QR claim
        |
iOS App
  Pairing
  Chat
  Sessions
  Pending Cards
  Attachments
  Push Registration
        |
        | device Bearer token over HTTPS + WebSocket/SSE
        v
SkillForge Server
  Chat REST APIs
  Chat Realtime Stream
  ask_user / confirmation bridge
  Attachment API
  APNs Push Dispatcher
        |
        v
Existing SkillForge Runtime
  SessionService
  ChatService
  ChatEventBroadcaster
  PendingConfirmationRegistry
  ChatAttachmentService
  Channel / ACP / Agent Loop
```

The iOS app should use new mobile authentication instead of the existing
dashboard pattern that passes `userId` in request bodies or query parameters.
This keeps the mobile surface ready for real device revocation and multi-user
hardening later.

## Pairing Flow

Dashboard or server creates a pairing request:

```text
User clicks Pair iPhone
  -> server creates one-time pairing request
  -> dashboard renders QR and setup code
  -> iOS scans QR
  -> app probes endpoints in order
  -> app claims pairing with pairingId + pairingSecret
  -> server creates mobile device token
  -> app stores selected endpoint and device token in Keychain
```

QR payload shape:

```json
{
  "type": "skillforge.mobile_pairing",
  "version": 1,
  "serverName": "SkillForge",
  "pairingId": "uuid",
  "pairingSecret": "short-lived-random-secret",
  "endpoints": [
    "https://skillforge.example.com",
    "https://skillforge.tailnet.example.ts.net",
    "http://192.168.1.10:8080"
  ],
  "expiresAt": "2026-07-09T12:00:00Z"
}
```

Runtime endpoint behavior:

- Persist the active endpoint, the complete normalized endpoint set, and the device token in Keychain.
- Rank RFC1918/private-LAN and `.local` candidates before Tailscale/public candidates while preserving
  relative order inside each class.
- Re-probe while the paired app is active. Publish a new active endpoint only after the target responds
  and accepts the existing device token.
- Endpoint changes preserve the current Agent, Session, transcript, composer draft, and optimistic state;
  Chat performs a preserving REST catch-up and reconnects WebSocket on the new address.
- Do not retry a Chat or other business mutation after an ambiguous transport failure. The monitor selects
  the endpoint before later requests, avoiding duplicate user messages.
- Dashboard includes its current origin and optional comma-separated
  `VITE_SKILLFORGE_MOBILE_ENDPOINTS` values in newly generated QR payloads.

Alternatives considered:

1. Keep one endpoint: smallest change, but cannot recover when Tailscale or LAN changes.
2. Retry every request over every endpoint: fast recovery, but can duplicate non-idempotent POST actions.
3. Central active-endpoint monitoring with state-preserving handoff: selected because it gives deterministic
   LAN-first behavior without replaying writes. A four-second foreground cadence bounds recovery while avoiding
   per-request probe latency.

Security constraints:

- `pairingSecret` is one-time and short-lived.
- Claiming a pairing invalidates it.
- Expired and used pairing requests cannot be claimed.
- QR never contains a long-lived API token.

## Backend Data Model

### `t_mobile_device`

Stores device identity and revocation state.

Suggested fields:

- `id` UUID primary key.
- `user_id` owner.
- `device_name` display name from iOS.
- `platform` fixed to `ios` for V1.
- `app_version`.
- `status`: `active`, `revoked`.
- `scopes_json`: JSON array of allowed scopes.
- `last_seen_at`.
- `created_at`.
- `revoked_at`.

### `t_mobile_pairing_request`

Stores short-lived setup requests.

Suggested fields:

- `id` UUID primary key.
- `user_id`.
- `secret_hash`.
- `server_name`.
- `endpoints_json`.
- `status`: `pending`, `claimed`, `expired`, `cancelled`.
- `expires_at`.
- `claimed_device_id`.
- `created_at`.
- `claimed_at`.

### `t_mobile_push_token`

Stores APNs registration for notifications.

Suggested fields:

- `id` UUID primary key.
- `device_id`.
- `apns_token_hash`.
- `apns_token_encrypted` or equivalent secret storage.
- `environment`: `development`, `production`.
- `status`: `active`, `disabled`.
- `last_registered_at`.
- `created_at`.

The implementation should choose the project's established secret-storage
pattern. If no encrypted storage is available yet, the design should gate
production push on a small secret-storage decision rather than scattering raw
tokens.

## Mobile Auth And Scopes

Mobile requests use:

```http
Authorization: Bearer <deviceToken>
```

MVP scopes:

- `chat:read`
- `chat:write`
- `confirmation:answer`
- `attachment:upload`
- `push:register`
- `schedule:read`
- `schedule:write`

The first implementation can store the token server-side as a hash and return
the raw token once at pairing time. iOS stores it in Keychain. Revoking a device
invalidates the token.

## Backend API Surface

Pairing:

- `POST /api/mobile/pairings`
  - Creates a pairing request for the current dashboard user.
  - Returns QR payload and setup code.
- `GET /api/mobile/pairings/{pairingId}`
  - Dashboard-side status polling for pending/claimed/expired/cancelled.
- `POST /api/mobile/pairings/{pairingId}/claim`
  - Called by iOS with `pairingSecret` and device metadata.
  - Returns selected user profile, device id, device token, and recommended
    default session/agent info.
- `GET /api/mobile/devices`
  - Dashboard-side device list for the current user.
- `POST /api/mobile/devices/{deviceId}/revoke`
  - Dashboard-side revoke endpoint.

Mobile bootstrap:

- `GET /api/mobile/client/me`
  - Validates token, returns user, device, default agent, feature flags.
- `GET /api/mobile/client/agents`
  - Returns mobile-safe configuration summaries for the authenticated user's owned
    conversational Agents plus selectable shared/default Agents.
  - Resolves the default through stable Agent id property
    `skillforge.mobile.default-agent-id` (default `3`), never through a
    non-unique display-name lookup.
  - Includes role, model id, status, visibility/source, execution mode and capability
    counts needed by the configuration roster.
  - Never exposes prompt bodies, provider credentials, lifecycle hooks, raw config,
    ownership identifiers, or persistence internals.
- `GET /api/mobile/client/agents/{agentId}`
  - Requires `agent:read` and derives the caller from `MobileDevicePrincipal`.
  - Returns a mobile-safe read-only detail DTO with overview, runtime policy,
    capability names/counts and prompt-presence metadata.
  - Reuses the same visibility predicate as the list; hidden, inactive, system and
    foreign-private Agents return 404 instead of revealing existence.
- `POST /api/mobile/client/push-token`
  - Registers or updates APNs token.

Chat:

- Mobile endpoints live under `/api/mobile/client/**` and derive `userId` from
  the device token. The iOS app never sends `userId` as an authorization input.
- The mobile facade delegates to existing `SessionService`, `ChatService`,
  pending-control, and attachment services rather than duplicating chat logic.
- Core V1 endpoints:
  - `GET /api/mobile/client/sessions`
  - `POST /api/mobile/client/sessions`
  - `GET /api/mobile/client/sessions/{sessionId}`
  - `GET /api/mobile/client/sessions/{sessionId}/messages`
  - `POST /api/mobile/client/sessions/{sessionId}/messages`
  - `POST /api/mobile/client/sessions/{sessionId}/answer`
  - `POST /api/mobile/client/sessions/{sessionId}/confirmation`
  - `POST /api/mobile/client/sessions/{sessionId}/attachments`

Realtime:

- V1 can use WebSocket if it already aligns with existing chat broadcaster.
- SSE is acceptable if it reduces iOS client complexity.
- App foreground receives realtime updates; app background relies on APNs and
  refreshes with REST on resume.

Controls:

- `POST /api/chat/{sessionId}/ask/{askId}/answer` can be reused if auth is
  adapted to device identity.
- `POST /api/chat/{sessionId}/confirmation` can be reused if auth is adapted to
  device identity.

Attachments:

- Reuse current chat attachment upload and binding flow.
- iOS only uploads user-selected files/images.

## iOS App Structure

SwiftUI modules:

- `Companion`
  - Paired-only `TabView` with `Chat / Control / Agents / Settings`.
  - Owns selected tab, selected Agent, and one-shot navigation requests into Chat.
  - Keeps the same Chat view identity while switching tabs.
- `Pairing`
  - QR scanner.
  - Manual setup-code entry.
  - Endpoint probing.
  - Pairing claim.
- `Auth`
  - Keychain token storage.
  - Device bootstrap.
  - Logout/revoke local device.
- `Chat`
  - Message list.
  - Composer.
  - Attachment picker.
  - Running state.
  - Pending cards.
- `Control`
  - Hub for schedules, concrete execution runs, and the existing Session browser.
  - Schedule list supports refresh, run-now, pause/enable, and recent run history.
  - A run may route to Chat through its `triggeredSessionId`; Schedule and Run never own
    a second transcript store.
- `Agents`
  - Searchable mobile-safe Agent configuration roster.
  - A row opens a read-only detail surface; it never creates a session or routes Chat.
  - Shows overview, runtime policy and capability sections without raw secrets or
    prompt bodies.
  - Agent selection for a new conversation remains inside Chat.
- `Settings`
  - Endpoint, connection, device, granted scopes, appearance, and diagnostics.
  - Confirmed local disconnect without displaying the raw device token.
- `Realtime`
  - WebSocket or SSE client.
  - Foreground subscription lifecycle.
  - REST catch-up on reconnect.
- `Push`
  - APNs registration.
  - Notification routing into session.
- `Networking`
  - Typed API client.
  - Endpoint base URL.
  - Token injection.
  - Retry policy for transient network errors.

Storage:

- Keychain for device token.
- UserDefaults or lightweight local store for selected endpoint, server name,
  device id, and last opened session id.
- No local durable message database in V1. The app can cache in memory and refetch
  on launch/resume.

Navigation state:

- `ChatView` remains the sole owner of transcript, composer, streaming, and WebSocket
  state. Control alone communicates through typed one-shot session navigation requests;
  Agents owns an independent configuration `NavigationStack` and cannot mutate Chat state.
- A failure in Control, Agents, or Settings must remain local to that tab and must not
  replace the paired root or clear Chat state.
- Pending controls remain inline in Chat. The Navigation Foundation slice does not
  introduce a global pending store or Push inbox.

## Control And Schedule Model

The original Navigation Foundation projected every Session into a Task row. That made
the two screens semantically identical and incorrectly implied that every conversation
was an execution. The corrected model follows the useful part of OpenClaw's Control/Cron
shape:

- `Session`: durable conversation and model context.
- `Schedule`: persistent cron or one-shot trigger definition.
- `Run`: one execution emitted by a Schedule. It may point at a Session.

The mobile facade is deliberately narrower than `/api/schedules`: it derives ownership
from `MobileDevicePrincipal`, exposes mobile-safe DTOs, and never accepts `userId`.

- `GET /api/mobile/client/schedules` requires `schedule:read`.
- `GET /api/mobile/client/schedules/{id}/runs?limit=N` requires `schedule:read`.
- `POST /api/mobile/client/schedules/{id}/trigger` requires `schedule:write`.
- `PUT /api/mobile/client/schedules/{id}/enabled` requires `schedule:write` and accepts
  only `{ "enabled": true|false }`.

New pairings receive both scopes. Existing active iOS devices are backfilled once so a
previously paired phone does not need to scan a new QR. Full create/edit/delete stays on
Dashboard for this slice; the mobile app is an operational control surface, not a second
schedule editor.

## Agents Configuration Foundation

### Product Boundary

Agents is a configuration-inspection domain, not a conversation launcher:

- `Agent`: durable behavior and capability configuration.
- `Session`: one conversation and its context, owned by Chat/Control.
- Opening an Agent never creates, selects, or resumes a Session.
- Chat may reuse the Agent catalog inside its explicit "new session" flow, but that
  selection state is owned by Chat and is not mutated by browsing Agents.

OpenClaw's useful pattern is a searchable Agent roster with operational metadata such
as active/default state, model, workspace, skills and runtime status. SkillForge adopts
that information hierarchy, but not OpenClaw's active-Agent switching behavior because
SkillForge already has explicit per-Session Agent ownership.

### Considered Paths

1. **Read-only mobile-safe configuration detail (recommended).** Add a summary roster
   and detail endpoint. Show meaningful runtime/capability configuration without prompt
   bodies, credentials or raw JSON. This fixes the information architecture with a
   bounded security surface.
2. **Full read including prompt bodies.** More complete, but system/soul/tool prompts can
   contain organization policy and operational details. It needs explicit authorization,
   redaction and screen-capture/privacy decisions before shipping.
3. **Full mobile CRUD.** Closest to Dashboard, but editing model, prompts, skills and tools
   creates validation, concurrency and accidental-production-change risks. This belongs
   in a separate `agent:write` requirement, not the companion foundation.

### Mobile DTOs

`MobileAgentListItemResponse` becomes a safe roster summary:

- `id`, `name`, `description`, `role`, `modelId`, `status`
- `source`: `owned | default | shared`
- `visibility`: `private | public`
- `isDefault`, `executionMode`
- `skillCount`, `toolCount`, `toolAccess`: `all | allowlist`
- `configurationAccess`: `detail | summary`

`MobileAgentDetailResponse` adds only normalized fields:

- Runtime: `maxLoops`, `thinkingMode`, `reasoningEffort`
- Capabilities: `skillNames`, `toolNames`, enabled system-skill count
- Prompt metadata: configured booleans and character counts for AGENT/SOUL/TOOLS;
  never prompt text

Owned and configured-default Agents receive `configurationAccess=detail`. Shared Agents
receive `summary`; capability names are omitted and only counts remain. The API never
serializes the persistence entity directly.

`toolIds` has established domain semantics: null/blank means all registered tools, not
an empty allowlist. The mobile DTO therefore returns `toolAccess=all`, the effective
registered-tool count and, for detail access, deterministically sorted names. A nonblank
malformed value fails closed as `toolAccess=allowlist` with zero tools; it must never
silently widen access.

### Authorization And Migration

- Add `agent:read` to new mobile pairings and backfill active devices with an idempotent
  migration so existing phones do not require re-pairing.
- Both roster and detail endpoints require `agent:read`.
- `MobileDevicePrincipal.userId` is the only ownership input.
- Hidden, inactive, system and foreign-private Agents return 404 from detail.
- No `agent:write` scope or mobile mutation endpoint is introduced in this slice.

### iOS Navigation And State

- `AgentsView` owns a `NavigationStack` with roster and `AgentDetailView`.
- Search covers name, description, role and model.
- Returning from detail preserves the roster search and scroll position.
- `CompanionTabView` removes `AgentsView.onSelect`; browsing Agents cannot write
  `AgentPreferenceStore`, select the Chat tab, or emit `ChatRoute`.
- Chat retains its own explicit Agent selection when creating a new Session.
- Loading/error/empty state remains local to Agents and cannot replace the paired root.

### Accessibility And Verification

- Agent rows expose name, model, role, status and source in one accessibility label.
- Configuration sections use native `List`, `Section` and `LabeledContent` so Dynamic
  Type does not compress values into unreadable cards.
- XCUITest asserts that opening/closing detail leaves Chat transcript and composer draft
  unchanged and does not create a Session request.
- API tests assert field allowlists, visibility filtering, 401/403/404 behavior and
  prompt/credential/raw-config absence.
- Contract tests cover null/blank unrestricted tools separately from malformed
  nonblank allowlists.

## Chat UX

Default post-pairing screen:

```text
Header: assistant name + connection status
Session switcher: recent sessions for the paired/default agent
Body: message stream
Inline cards: ask_user / confirmation / errors
Footer: text composer + attachment button
```

Behavior:

- If no session exists, create or open a default session for the default agent.
- The user can switch among recent sessions for the same/default agent without
  leaving the chat surface.
- If a session is running, show a compact running indicator.
- If a pending control exists, show it inline and keep it visible until answered.
- If a push opens the app for a session, navigate directly to that session.
- System keyboard dictation provides V1 voice input.

## Outbound Artifact Foundation

### Problem And Invariants

The current attachment path only covers user-to-Agent uploads. `SendChannelFile`
delivers a local file to a bound WeChat conversation and intentionally rejects an
ordinary Chat session. It does not create an assistant attachment message.

The new path must preserve these invariants:

- A tool cannot write a session message as a side effect. `ChatService` performs a
  final reconciliation from `LoopResult.messages`; an out-of-band row can be
  overwritten, duplicated, or trigger a prefix-divergence rewrite.
- `assistant(tool_use)` must be followed by the matching `user(tool_result)` before
  an artifact appears in a later assistant message.
- The Message broadcast to clients and the Message returned in `LoopResult.messages`
  must have the same JSON shape.
- Persisted attachment refs stay refs. Provider-only expansion never mutates the
  engine list or the stored message.
- Full history remains the source of truth for the trace UI. iOS realtime is an
  acceleration path and REST catch-up reconciles by stable message identity.

### Considered Paths

| Path | Benefits | Rejected risk |
| --- | --- | --- |
| Tool directly appends an assistant message | Small local change | Creates a DB row absent from the engine list; breaks final prefix reconciliation and can split a tool pair |
| Tool returns a JSON marker and `ChatService` scans tool results after the loop | Avoids direct DB message writes | Markers can be truncated or compacted away, and string parsing couples persistence to user-visible tool output |
| Typed artifact sidecar carried by `SkillResult` and Agent Loop | Structured, compact-safe, deterministic ordering, one final Message shape | Requires a focused core protocol extension and provider materialization review |

Use the typed sidecar path. This is a core change, but it removes the persistence
race instead of hiding it behind a post-loop parser.

### Publish Protocol

Add `PublishChatArtifact`, separate from `SendChannelFile`:

```json
{
  "file_path": "/resolved/workspace/report.pdf",
  "caption": "本次分析报告"
}
```

The tool imports the file through `ChatAttachmentService`, returns a normal concise
tool result to the model, and attaches typed metadata to `SkillResult`:

```json
{
  "attachmentId": "uuid",
  "blockType": "pdf_ref",
  "filename": "report.pdf",
  "mimeType": "application/pdf",
  "pageCount": 8
}
```

`SkillResult` text remains the provider-visible tool result. Artifact metadata is
an out-of-band typed field and is never recovered by parsing that text. Because the
current engine immediately converts `SkillResult` to `Message.toolResult`, extend
the internal execution return type explicitly:

```java
record ToolExecutionOutcome(
    Message toolResult,
    List<PublishedArtifact> artifacts
) {}
```

Sequential and parallel execution both store this envelope by original tool-call
index. The main loop appends every `toolResult` first and then collects successful
artifacts in that same deterministic order. Timeout, validation, rejection, and
truncation paths produce an empty artifact list. No shared unordered artifact list
or thread-local registry is allowed.

After all matching tool results are appended, the engine merges pending refs into
the next terminal assistant message. If the loop exits without terminal assistant
text because of waiting, cancellation, or a bounded runtime exit, it appends one
assistant attachment-only Message before building `LoopResult`. It never inserts
an artifact between a `tool_use` and its `tool_result`.

An assistant Message containing a newly published artifact is marked as a deferred
broadcast in `LoopResult`; `AgentLoopEngine` does not call `messageAppended` for that
Message. `ChatService` first reconciles `LoopResult.messages`, then broadcasts the
same Message object. This prevents a client from observing a ghost attachment that
failed to persist. Existing non-artifact streaming/text events remain unchanged.

Use the existing block types:

- `image_ref`
- `pdf_ref`
- `word_ref`
- `excel_ref`
- `csv_ref`

Do not add a mobile-only `file_ref` in this slice. Unsupported binary types fail
validation until their storage, preview, and provider behavior are designed.

### Persistence And Idempotency

- Reuse `t_chat_attachment`; do not create a parallel artifact table.
- Add a migration with `origin`, `source_tool_use_id`, `sha256`, and `caption`.
  `origin` is `user_upload` or `agent_generated`; existing rows backfill to
  `user_upload`. `origin` is `NOT NULL` with a check constraint;
  `source_tool_use_id` is a bounded nullable string. Add a partial unique index on
  `(session_id, source_tool_use_id)` where the tool ID is present and an index for
  `(origin, status, created_at)` cleanup/recovery queries.
- Derive idempotency from `sessionId + toolUseId`; the database unique index is the
  final concurrency guard so replaying the same tool call does not create a second
  row or file.
- A successful import starts as `uploaded`. Once the assistant ref is durably
  reconciled, mark it `published`. The persisted ContentBlock is the publication
  source of truth; `status=published` is a repairable projection, not an authorization
  boundary. Cleanup must scan/reference-check staged agent attachments before delete:
  a referenced `uploaded` row is repaired to `published`, while an unreferenced row
  beyond the TTL is removed. This closes the crash window between message commit and
  status update without requiring a cross-service pseudo-transaction.
- `sha256` is computed while streaming the managed copy and becomes the ETag source.
- `seq_no` remains optional for assistant-published artifacts. The durable relation
  is the attachment ID inside the persisted ContentBlock plus session/user ownership;
  compact/rewrite paths must not rely on a stale sequence number for reachability.
- The artifact Message is part of `LoopResult.messages` before the single
  `SessionService.updateSessionMessages` call. No tool calls `appendNormalMessages`.
- `ChatService` broadcasts deferred artifact Messages only after reconciliation
  returns successfully. It then updates the repairable status projection. REST
  history remains the authoritative recovery path if a connection drops.

When an assistant attachment ref appears in later history, a pure core helper
converts all five ref types to a short text placeholder such as
`[Previously delivered PDF: report.pdf]`. This conversion uses only block metadata,
cannot perform I/O, and cannot fail. It runs before provider serialization and is
also used by Full Compact text extraction. The engine's materializer error fallback
must still return this assistant-sanitized copy, never the raw assistant ref.
Assistant images are never expanded to base64 and assistant documents are never
re-extracted. User attachment refs keep the existing multimodal materialization
behavior. `Message.getTextContent` and Full Compact cover all five ref types.

### File Security

Treat `file_path` as untrusted LLM output:

- Resolve with `toRealPath`, require a regular file, and reject symbolic-link escape.
- Allow only a dedicated per-run artifact staging directory and the current
  session attachment directory. Do not allow process-wide temp, home, repository
  root, or the complete process/loop working directory.
- `ArtifactWorkspaceService` creates
  `${artifactStagingRoot}/{userId}/{sessionId}/{traceId}` at loop start, stores the
  canonical path on `LoopContext` and `SkillContext`, and injects it into the system
  prompt as the only deliverable output directory. Bash/ACP/worktree-based generators
  receive the absolute path and write or copy the final deliverable there before
  calling `PublishChatArtifact`.
- A resumed loop creates a new trace directory; already imported artifacts remain in
  managed storage. Staging directories are removed after successful publication and
  otherwise by a TTL cleanup that applies the same canonical containment checks.
- Stream into a deterministic attachment target through a random `.part` file under
  the canonical managed root, compute SHA-256 during copy, then atomically rename.
  A unique-index conflict loads and returns the existing matching attachment and
  deletes the losing `.part`; mismatched content for the same tool ID fails closed.
  Never expose the source path.
- Reuse magic-byte/MIME validation and existing per-kind size limits.
- Sanitize the displayed filename and avoid logging source paths or file content.
- Bind userId and sessionId from `SkillContext`; they are not tool inputs.

### Mobile Download API

```http
GET /api/mobile/client/sessions/{sessionId}/attachments/{attachmentId}/data
Authorization: Bearer <device-token>
```

The controller requires `chat:read`, derives userId from `MobileDevicePrincipal`,
then validates session ownership and attachment session/user ownership. System
session userId `0` is not a wildcard for attachment authorization. Missing and
unauthorized attachments return 404.

The response streams a Spring `Resource` rather than calling `readAllBytes`, supports
one HTTP byte range (`206`, `416`, `Accept-Ranges`), and sets the detected
`Content-Type`, safe UTF-8 `Content-Disposition`, `Content-Length`, SHA-256 `ETag`,
`X-Content-Type-Options: nosniff`, and `Cache-Control: private, no-cache`. A matching
`If-None-Match` returns `304`. One range returns a correct `206` with `Content-Range`
and ranged `Content-Length`; multiple or invalid ranges return `416`. A failed
`If-Range` falls back to full `200`. The app may
keep a file-protected local copy but must revalidate it with ETag before reuse; a 401
clears the paired-server cache. Images and
PDF may use `inline`; Office and CSV use `attachment`. No download URL or token is
stored in the message. The file read path revalidates canonical containment under
the managed storage root instead of trusting `storage_path` from the database.

### iOS Rendering And Download

`MobileContentBlock` decodes attachment ID, MIME, filename, page/sheet count, and
optional size/caption metadata in snake_case with compatibility aliases. Caption is
stored on the attachment ref and rendered inside its card; it is not duplicated as
assistant body text. `ChatMessage`
normalization collects refs from both user and assistant messages and retains a
message when it contains attachments but no text.

The UI uses production message identity throughout:

- Images render a stable-aspect placeholder, authenticated thumbnail load, retry,
  and full-screen preview.
- Documents render filename, type, optional size/unit count, progress, retry, and
  an open action backed by Quick Look.
- Download uses an actor-backed repository and `URLSession.download` with the
  device Bearer token. Cache keys include endpoint/device/attachment identity;
  cached files use iOS file protection, are excluded from backup, and revalidate
  with ETag before reuse.
- Share uses the system share sheet. Disconnect/server switch clears cached files.
- Attachment load state is local to the card and cannot replace the transcript or
  change the containing message ID.
- Only REST/WebSocket reconciliation mutates the transcript array. Download state is
  owned by the repository under `endpoint + device + attachmentId` and never writes
  back into `ChatMessage`.
- VoiceOver describes type, filename, size, and state; actions are at least 44pt.

Dashboard normalization and rendering must also retain assistant refs and pure
attachment messages. The existing user-only render guard is removed.

### Outbound Artifact Verification

Backend:

- typed `SkillResult` artifact serialization and engine ordering;
- sequential/parallel `ToolExecutionOutcome` ordering, timeout, truncation and retry;
- one attachment for repeated `sessionId + toolUseId`;
- one final assistant ref and no prefix mismatch;
- provider request uses assistant placeholders while persistence keeps refs;
- path traversal, symlink escape, cross-session path, unsupported type, MIME spoof,
  and size limit rejection;
- atomic copy, SHA-256/idempotency, and cleanup containment;
- crash recovery before/after file rename, DB insert, message commit, and status repair;
- mobile download 200/206/401/404/416, revoked token, cross-user and cross-session cases;
- ETag 304, If-Range fallback and multi-range rejection;
- light/full compact retains the full-history reference and tool pairing.

iOS:

- decode all five ref types and unknown types without losing the message;
- normalize pure attachment, mixed text/tool/attachment, and multiple attachments;
- authenticated download, cache isolation, cancellation, retry, 401 and 404;
- image preview, Quick Look, share, Dynamic Type, VoiceOver, and transcript stability.

Dashboard:

- assistant and pure attachment normalization/rendering;
- loading, error, and retry behavior without duplicate messages.

## Push Notifications

V1 notification types:

- Task completed.
- Task failed.
- User input required.
- Confirmation required.

Delivery rules:

- Push is best-effort and never drives core state.
- Server stores enough event state so the app can refresh from REST after tapping.
- If APNs send fails, log and disable token after repeated permanent failures.

## Interaction Polish

### New Conversation Ownership

- `CompanionTabView` owns the mobile-safe Agent catalog and passes it to Chat as read-only input.
- `ChatView` owns presentation of one shared `NewConversationView`; both the header `+` and the
  Session List `+` route to it.
- Creating a Session sends only the selected `agentId`. A successful response updates Chat's
  selected Agent through a typed callback and selects the returned Session. Cancellation or failure
  leaves the current transcript and composer state untouched.
- The header `+` is a create command. Session browsing remains on the sidebar button and local
  disconnect remains in Settings.

### Session List

- V1 filtering is local and bounded to the currently loaded Agent sessions; no new server query
  contract is introduced.
- Search matches title and runtime/status text. The status picker groups `running`, waiting/input,
  error, and other sessions without inventing Task semantics.
- Pull-to-refresh calls Chat's existing session reload path. The current Session id remains the
  selection source of truth.
- Rename/archive/delete/pin require explicit backend lifecycle contracts and are not part of this
  slice.

### Settings

- Appearance is a non-secret `@AppStorage` preference with `system`, `light`, and `dark`; the paired
  root applies the corresponding preferred color scheme.
- Notifications reads `UNUserNotificationCenter` settings and requests permission only from an
  explicit user action. Until APNs token registration is implemented, it reports system permission
  only and never claims server registration.
- Permissions & Privacy explains granted mobile scopes and explicitly states that contacts,
  calendar, and location are not requested.
- Diagnostics continues to validate `/api/mobile/client/me`; failures stay local to Settings and do
  not replace the paired root except for an actual 401.

### Connection Health

- Settings owns one connection-health state machine with `notChecked`, `checking`, `healthy`,
  `offline`, and `serviceIssue` phases. Opening Settings performs one check; foreground return and
  an explicit user action may check again, but there is no background polling.
- The existing authenticated `/api/mobile/client/me` request is the health probe. A successful
  response proves endpoint reachability, SkillForge service availability, and device authorization.
- `URLError` failures are classified as network/offline. HTTP 5xx, malformed responses, and other
  non-401 failures become service issues. User-facing diagnostics are normalized and never include
  the server response body.
- HTTP 401 keeps the established fail-closed behavior and routes back to pairing. Other failures
  remain local to Settings and preserve the endpoint and device credential.
- The summary row navigates to a native `List` diagnostics detail with endpoint, service,
  authorization, realtime lifecycle, last-check time, and a retry command. Realtime is described as
  foreground Chat-managed because Settings cannot truthfully prove a WebSocket handshake.
- A local deterministic fixture supplies healthy/offline states for XCTest/XCUITest without network
  access or device credentials.

Alternatives rejected:

- A continuous health timer was rejected because Settings does not need background monitoring and
  repeated authenticated probes waste battery and network resources.
- Treating `paired` as `healthy` was rejected because stored credentials do not prove reachability.
- Showing raw backend error bodies was rejected because they are noisy and can expose operational
  details without improving recovery.

### Realtime Transcript Handoff

- `ChatView` uses one remote-snapshot application path for initial load, send polling, foreground
  catch-up, and WebSocket-triggered reload. That path reconciles persisted messages, pending
  interactions, buffered deltas, transient text, and transient tools as one main-actor operation.
- Before applying a committed remote snapshot, pending throttled text is drained into
  `MobileRealtimeState`. Each stream records the highest persisted `seqNo` visible when that turn
  starts; only assistant rows beyond that boundary may consume the transient prefix. Persisted
  tool-loop iterations advance the consumed boundary incrementally, so one turn may hand off more
  than one assistant row without leaving an aggregate duplicate bubble.
- Remote snapshots are applied monotonically by their highest `seqNo`; a response older than the
  last applied snapshot cannot overwrite a newer transcript. A stale snapshot keeps transient
  output and a later committed snapshot completes the handoff. Different persisted `seqNo` values
  remain authoritative even when their visible text is equal; global content-based history
  deduplication is prohibited.
- Compatibility delta events select one canonical source per stream segment (`assistant_delta` or
  `text_delta`) instead of comparing adjacent chunk text. This keeps repeated legitimate chunks
  while discarding the server's mirrored compatibility event.
- `session_status=idle/error` triggers a bounded final catch-up instead of blindly clearing
  transient output before persistence is visible.

### Pairing Review UX

- Pairing is a small state machine: `ready -> scanning/pasting -> review -> checking -> claiming ->
  saving -> paired`, with explicit safe error states.
- The first screen contains one primary Scan action and a secondary Paste Pairing Payload action.
  Raw payload entry is collapsed by default and removed from visible/accessibility state immediately
  after successful decoding.
- Review displays only `serverName`, normalized endpoint host/scheme, and expiry. It never displays
  `pairingId`, `pairingSecret`, device token, or the full payload.
- Claim starts only after explicit review confirmation. Scanner cancellation, camera-unavailable,
  invalid/expired payload, unreachable endpoint, used secret, and credential-save failure each have
  a specific recovery action and normalized copy.
- The existing six-digit setup code is dashboard-side human verification only and is not accepted by
  the claim API. Until a rate-limited setup-code lookup protocol exists, the iOS UI must not render a
  setup-code input or describe it as a fallback authentication mechanism.

Alternatives rejected:

- Global visible-text dedup was rejected because two independent assistant turns may legitimately
  have identical text.
- Immediate claim directly from the scanner callback was rejected because it consumes a one-time
  secret before the user can verify the target server.
- Retaining the six-digit field as visual polish was rejected because the current server does not
  read that value and the UI would make a false security claim.

### Alternatives Rejected

- Keeping a generic Quick Actions sheet was rejected because it mixed creation, navigation, and a
  destructive disconnect command.
- Adding Schedule creation to `+` was rejected because the approved mobile Schedule API is
  operational only; create/edit/delete remain on Dashboard.
- Session destructive swipe actions were rejected until server-side lifecycle and ownership rules
  are specified.

## Error Handling

Pairing:

- Expired QR: show expired state and ask user to generate a new QR.
- No reachable endpoint: show endpoint list and allow retry/manual URL.
- Secret mismatch or already claimed: fail closed.

Auth:

- Revoked token: clear local credentials and return to pairing screen.
- Server unreachable: show offline state, keep local credentials.

Realtime:

- On disconnect, show reconnecting and poll/refresh when foregrounded.
- Do not keep background sockets alive in V1.

Chat:

- Send failures remain as retryable local UI states until app refetch confirms
  whether the server accepted the message.
- Confirmation answer failures show clear retry/error state and must not assume
  the decision succeeded.

## Testing Strategy

Backend tests:

- Pairing create/claim success.
- Pairing expiry, reused secret, wrong secret, revoked device.
- Device token auth maps to `user_id` and blocks cross-user session access.
- Push token registration.
- Mobile answer path for `ask_user` and confirmation.
- Attachment upload authorization through mobile token.

iOS tests:

- Pairing QR decode.
- Endpoint probe chooses first reachable endpoint.
- Token persistence in Keychain wrapper.
- Chat API client request shaping.
- Pending card state transitions.
- Chat reducer tests for streaming text, tool calls, and REST/WebSocket races.
- XCUITest for keyboard dismissal, scroll-to-bottom, session sheets, and other
  critical SwiftUI interactions.

Agent/local development mode:

- A `DEBUG`-only launch argument may load deterministic chat fixtures so UI
  tests do not depend on a real pairing token, personal transcript, or running
  backend.
- The fixture must reuse production views and must not exist in Release builds.
- Stable accessibility identifiers are required for critical controls and
  transcript assertions.
- Live-backend simulator tests remain a separate integration layer for REST,
  WebSocket, and streaming behavior; real-device checks remain mandatory for
  QR camera, signing, LAN/Tailscale, Keychain, attachments, and APNs.

Manual E2E:

- Generate QR in dashboard.
- Pair iPhone simulator/device.
- Create/open default session.
- Send a message and receive streaming update.
- Trigger `ask_user`, answer from iOS, verify loop continues.
- Trigger confirmation, approve/deny from iOS.
- Upload image/file.
- Register APNs token in development environment and receive a test notification.

## Rollout Plan

Phase 1: backend pairing and device auth.

Phase 2: SwiftUI pairing and chat shell.

Phase 3: realtime sync and pending cards.

Phase 4: attachments and APNs push.

Phase 5: polish, revoke UI, and E2E verification.

## Implementation Choices

These choices are fixed for the V1 plan:

- Realtime starts with REST catch-up plus foreground polling where needed; add
  WebSocket/SSE once the pairing and chat facade are verified on a real device.
- Pairing secrets and device tokens are generated as high-entropy random values;
  the server stores SHA-256 hashes and returns the raw token only once.
- Dashboard pairing UI lives in a new `Mobile Devices` page and follows the
  existing WeChat QR component lifecycle pattern: start, render QR, poll status,
  and clear timers on unmount.

## Acceptance Criteria

- A user can pair an iPhone with SkillForge by scanning a QR code.
- The iOS app securely stores a device token, active endpoint, and normalized endpoint candidates.
- The app opens directly to a chat-first assistant experience after pairing.
- The user can send text messages and see assistant responses.
- The user can answer `ask_user` prompts from iOS.
- The user can approve or deny confirmation cards from iOS.
- The user can upload an image or file into a chat session.
- The server can send APNs notifications for completion, failure, input required,
  and confirmation required events.
- A revoked device can no longer access sessions.
- With both LAN and Tailscale candidates in `endpoints[]`, a real device can move between them without re-pairing.

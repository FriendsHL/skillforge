# IOS-ASSISTANT-COMPANION Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL before implementation: use `superpowers:subagent-driven-development` for parallel workstreams, or `superpowers:executing-plans` when executing sequentially. Keep checkboxes updated as tasks complete.

Date: 2026-07-09
Mode: Full
Status: implemented; retained as the historical file-level plan. The current
delivery matrix and remaining scope live in `index.md`.

> The checkboxes below preserve execution history and were not maintained across
> every follow-up slice. Do not infer current product status from an unchecked
> box; use `index.md`, tests, and `delivery-index.md` as the source of truth.

## Goal

Build SkillForge iOS Assistant Companion V1 inside the SkillForge monorepo:

- `skillforge-ios/` native SwiftUI iOS app.
- Server-side mobile pairing, device token auth, session/chat facade, pending-control facade, attachment upload, and push token registration.
- Dashboard `Mobile Devices` page that generates the QR code the iOS app scans.
- First usable milestone: Xcode direct install to a real iPhone, scan Dashboard QR, pair with local/LAN/Tailscale SkillForge, and land in a chat-first UI.

## Fixed Decisions

- Repository: monorepo, add `skillforge-ios/`.
- iOS project generation: use XcodeGen because this workstation already has `/opt/homebrew/bin/xcodegen`; track `project.yml` and the generated Xcode project, but ignore `xcuserdata` and DerivedData.
- Pairing direction: Dashboard/Server generates QR; iOS scans QR.
- Token rule: QR contains only short-lived one-time secrets. Device token is returned once after claim; server stores only SHA-256 hashes.
- Mobile auth rule: `/api/mobile/client/**` derives `userId` from the mobile device token. The iOS app never sends `userId` as an authorization input.
- Realtime staging: Slice 2 uses REST catch-up plus foreground refresh first. Add WebSocket/SSE after device pairing and mobile chat facade are verified on real hardware.
- Push staging: APNs token registration is part of V1, but APNs delivery can be enabled after Apple Developer Program certificates/keys are available.

## Slice 2+ Chat UX And Realtime Addendum

Approved on 2026-07-10 after real-device feedback.

Scope:

- Render assistant Markdown as mobile-friendly blocks instead of one dense text run.
- Normalize `tool_use` / `tool_result` content blocks on iOS and render tool-call cards under assistant messages.
- Add a one-tap scroll-to-bottom affordance when the user is reading history.
- Add mobile device-token WebSocket auth and subscribe the iOS app to existing chat realtime events.
- Show `Agent 运行中` while a submitted message is being processed. Reserve `刷新中` for background catch-up only, not the primary running state.

Implementation notes:

- Reuse the existing chat broadcaster event shape where possible: `session_status`, `text_delta`, `reasoning_delta`, `tool_started`, `tool_finished`, `tool_use_delta`, `tool_use_complete`, `message_appended`, and `assistant_stream_end`.
- Do not reuse the dashboard WebSocket access token for iOS. Mobile WebSocket access must validate the mobile device token and session ownership.
- Keep REST catch-up as a fallback after stream completion, reconnect, foreground resume, and message send acknowledgement.

## Target Architecture

```text
Dashboard
  /mobile-devices page
  Pair iPhone button
  QR render + pairing status polling
        |
        | dashboard Bearer token
        v
SkillForge Server
  MobilePairingController
  MobilePairingService
  MobileDeviceService
  MobileAuthInterceptor
        ^
        | one-time QR claim
        |
iOS App
  Pairing scanner
  Endpoint probe
  Keychain device token
  Chat-first UI
  Session switcher
  Pending cards
  Attachment picker
        |
        | mobile device Bearer token
        v
SkillForge Server
  MobileClientController facade
  SessionService / ChatService
  PendingAskRegistry / PendingConfirmationRegistry
  ChatAttachmentService
```

## Workstream 0 - Branch And Dirty Tree Guard

- [ ] Run `git status --short` and note unrelated dirty files before implementation.
- [ ] Keep this work isolated from existing compaction/session-service changes. Use a separate branch or worktree if the main working tree remains dirty.
- [ ] Confirm the active requirement package stays at:

```text
docs/requirements/active/2026-07-09-IOS-ASSISTANT-COMPANION/
```

Verification:

```bash
git status --short
test -f docs/requirements/active/2026-07-09-IOS-ASSISTANT-COMPANION/implementation-plan.md
```

## Workstream 1 - Backend Schema

Add the first mobile migration:

```text
skillforge-server/src/main/resources/db/migration/V166__mobile_pairing_device_auth.sql
```

Schema:

```sql
CREATE TABLE t_mobile_device (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    device_name VARCHAR(128) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    app_version VARCHAR(64),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    scopes_json TEXT NOT NULL DEFAULT '[]',
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_device_platform CHECK (platform IN ('ios')),
    CONSTRAINT chk_mobile_device_status CHECK (status IN ('active', 'revoked'))
);

CREATE INDEX idx_mobile_device_user_status
    ON t_mobile_device(user_id, status, created_at DESC);

CREATE TABLE t_mobile_pairing_request (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    setup_code_hash VARCHAR(64) NOT NULL,
    server_name VARCHAR(128) NOT NULL,
    endpoints_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'pending',
    expires_at TIMESTAMPTZ NOT NULL,
    claimed_device_id UUID REFERENCES t_mobile_device(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_pairing_status
        CHECK (status IN ('pending', 'claimed', 'expired', 'cancelled'))
);

CREATE INDEX idx_mobile_pairing_user_status
    ON t_mobile_pairing_request(user_id, status, created_at DESC);

CREATE INDEX idx_mobile_pairing_expires
    ON t_mobile_pairing_request(expires_at)
    WHERE status = 'pending';
```

Add push-token schema in a separate migration when Slice 4 starts:

```text
skillforge-server/src/main/resources/db/migration/V167__mobile_push_tokens.sql
```

```sql
CREATE TABLE t_mobile_push_token (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES t_mobile_device(id),
    apns_token_hash VARCHAR(64) NOT NULL UNIQUE,
    apns_token_ciphertext TEXT NOT NULL,
    environment VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    last_registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disabled_at TIMESTAMPTZ,
    CONSTRAINT chk_mobile_push_environment
        CHECK (environment IN ('development', 'production')),
    CONSTRAINT chk_mobile_push_status
        CHECK (status IN ('active', 'disabled'))
);

CREATE INDEX idx_mobile_push_device_status
    ON t_mobile_push_token(device_id, status, last_registered_at DESC);
```

Tasks:

- [ ] Create `MobileDeviceEntity` and `MobilePairingRequestEntity` under `skillforge-server/src/main/java/com/skillforge/server/mobile/`.
- [ ] Create matching repositories under `skillforge-server/src/main/java/com/skillforge/server/mobile/`.
- [ ] Use `Instant` for persisted time fields.
- [ ] Keep JSON fields as `String` on entities and serialize through Spring's managed `ObjectMapper`.
- [ ] Add repository/integration tests for basic persistence and status filtering.

Verification:

```bash
mvn -pl skillforge-server -Dtest='*Mobile*Repository*Test,*Mobile*Repository*IT' test
```

## Workstream 2 - Backend Pairing And Device Auth

Create these backend classes:

```text
skillforge-server/src/main/java/com/skillforge/server/mobile/MobileTokenService.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobilePairingService.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobileDeviceService.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobileAuthInterceptor.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobileDevicePrincipal.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobilePairingController.java
skillforge-server/src/main/java/com/skillforge/server/mobile/MobileClientController.java
```

Token behavior:

- [ ] Generate pairing secrets and device tokens from `SecureRandom`.
- [ ] Encode raw tokens with URL-safe Base64 without padding.
- [ ] Store hashes as lowercase SHA-256 hex.
- [ ] Compare hashes with `MessageDigest.isEqual`.
- [ ] Return the raw device token only in the successful claim response.
- [ ] Never log raw pairing secrets or raw device tokens.

Controller split:

```text
POST /api/mobile/pairings                     dashboard token
GET  /api/mobile/pairings/{pairingId}         dashboard token
GET  /api/mobile/devices                      dashboard token
POST /api/mobile/devices/{deviceId}/revoke    dashboard token

POST /api/mobile/pairings/{pairingId}/claim   public path, one-time secret required

GET  /api/mobile/client/me                    mobile device token
GET  /api/mobile/client/sessions              mobile device token
POST /api/mobile/client/sessions              mobile device token
GET  /api/mobile/client/sessions/{sessionId}  mobile device token
GET  /api/mobile/client/sessions/{sessionId}/messages
POST /api/mobile/client/sessions/{sessionId}/messages
POST /api/mobile/client/sessions/{sessionId}/answer
POST /api/mobile/client/sessions/{sessionId}/confirmation
POST /api/mobile/client/sessions/{sessionId}/attachments
POST /api/mobile/client/push-token
```

`WebMvcConfig` target shape:

```java
registry.addInterceptor(authInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/**")
        .excludePathPatterns("/api/channels/*/webhook")
        .excludePathPatterns("/api/mobile/pairings/*/claim")
        .excludePathPatterns("/api/mobile/client/**");

registry.addInterceptor(mobileAuthInterceptor)
        .addPathPatterns("/api/mobile/client/**");
```

Pairing create response shape:

```json
{
  "pairingId": "uuid",
  "status": "pending",
  "setupCode": "842193",
  "expiresAt": "2026-07-09T12:00:00Z",
  "qrPayload": {
    "type": "skillforge.mobile_pairing",
    "version": 1,
    "serverName": "SkillForge",
    "pairingId": "uuid",
    "pairingSecret": "raw-short-lived-secret",
    "endpoints": ["http://192.168.1.10:8080"],
    "expiresAt": "2026-07-09T12:00:00Z"
  }
}
```

Pairing claim response shape:

```json
{
  "deviceId": "uuid",
  "deviceToken": "raw-device-token-returned-once",
  "serverName": "SkillForge",
  "user": { "id": 1 },
  "defaultAgent": { "id": 3, "name": "Main Assistant" },
  "features": {
    "attachments": true,
    "push": false,
    "realtime": false
  }
}
```

Tasks:

- [ ] Add `MobilePairingService#createPairing(userId, endpoints, serverName)`.
- [ ] Add `MobilePairingService#claimPairing(pairingId, rawSecret, deviceMetadata)`.
- [ ] Add `MobilePairingService#getPairingStatus(userId, pairingId)`.
- [ ] Add `MobileDeviceService#authenticate(rawToken)` returning an active `MobileDevicePrincipal`.
- [ ] Add `MobileDeviceService#revokeDevice(userId, deviceId)`.
- [ ] Update `WebMvcConfig` with the two-interceptor split.
- [ ] Make expired pending pairings fail closed during claim and status polling.
- [ ] Add tests for success, expired secret, wrong secret, reused secret, revoked device, and cross-user revoke denial.

Verification:

```bash
mvn -pl skillforge-server -Dtest='MobileTokenServiceTest,MobilePairingServiceTest,MobileDeviceServiceTest,MobileAuthInterceptorTest,MobilePairingControllerTest' test
```

## Workstream 3 - Mobile Chat Facade

The mobile facade must delegate to existing services rather than cloning chat logic.

Tasks:

- [x] Add DTO records under `skillforge-server/src/main/java/com/skillforge/server/mobile/`.
- [x] Implement `GET /api/mobile/client/me` with default user and Main Assistant metadata.
- [x] Implement mobile session list by delegating to `SessionService.listUserSessions(principal.userId())`.
- [x] Implement mobile session create by delegating to `SessionService.createSession(principal.userId(), agentId)`.
- [x] Return lightweight `MobileSessionResponse` DTOs for mobile session list/create/get; do not expose full `SessionEntity` internals.
- [x] Implement message history by delegating to `SessionService.getFullHistoryDtos(sessionId)` after ownership check.
- [x] Implement message send by delegating to `ChatService.chatAsync(sessionId, message, principal.userId(), attachmentIds)`.
- [x] Implement `ask_user` answer by delegating to `ChatService.answerAsk`.
- [x] Implement confirmation answer by delegating to `ChatService.answerConfirmation`.
- [x] Implement attachment upload by delegating to `ChatAttachmentService` after mobile ownership check.
- [x] Keep system-owned sessions (`userId=0`) behavior aligned with current `ChatController.requireOwnedSession` semantics.
- [x] Add controller tests proving client-sent `userId` is ignored when present.

Recommended controller helper:

```java
private SessionEntity requireMobileOwnedSession(String sessionId, MobileDevicePrincipal principal) {
    SessionEntity session = sessionService.getSession(sessionId);
    if (session.getUserId() != null && session.getUserId() == 0L) {
        return session;
    }
    if (!principal.userId().equals(session.getUserId())) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return session;
}
```

Verification:

```bash
mvn -pl skillforge-server -Dtest='MobileClientControllerTest,MobileChatFacadeTest' test
```

## Workstream 4 - Dashboard Mobile Devices Page

Add a first-class Dashboard page instead of placing iOS pairing under Channels.

Files:

```text
skillforge-dashboard/src/api/mobile.ts
skillforge-dashboard/src/pages/MobileDevices.tsx
skillforge-dashboard/src/components/mobile/MobilePairingPanel.tsx
skillforge-dashboard/src/components/mobile/MobileDeviceList.tsx
skillforge-dashboard/src/components/mobile/mobile.css
skillforge-dashboard/src/pages/__tests__/MobileDevices.test.tsx
skillforge-dashboard/src/api/__tests__/mobile.test.ts
```

Route/nav changes:

```text
skillforge-dashboard/src/App.tsx
skillforge-dashboard/src/components/Layout.tsx
```

UI requirements:

- [ ] Add primary nav item `Mobile` with route `/mobile-devices`.
- [ ] Page shows paired devices, last seen, status, app version, and revoke action.
- [ ] `Pair iPhone` opens or expands a pairing panel.
- [ ] Pairing panel calls `POST /api/mobile/pairings`, renders `qrPayload` with AntD `QRCode`, and displays setup code fallback.
- [ ] Poll `GET /api/mobile/pairings/{pairingId}` every 2 seconds while pending.
- [ ] Clear polling timers on unmount and when a new QR replaces the old one.
- [ ] On `claimed`, show paired device metadata and invalidate the devices query.
- [ ] On `expired` or `cancelled`, stop polling and show a regenerate action.
- [ ] The QR payload must encode the JSON string returned by the backend, not a long-lived token.

Testing:

- [ ] API tests for request paths.
- [ ] Component tests for QR generation, claimed status, expired status, revoke action, and cleanup.
- [ ] Browser check on desktop viewport for layout and no overlapping controls.

Verification:

```bash
cd skillforge-dashboard
npm test -- --run src/api/__tests__/mobile.test.ts src/pages/__tests__/MobileDevices.test.tsx
npm run typecheck
npm run build
```

## Workstream 5 - iOS Project Scaffold

Create the app under:

```text
skillforge-ios/
```

Add:

```text
skillforge-ios/project.yml
skillforge-ios/SkillForge/App/SkillForgeApp.swift
skillforge-ios/SkillForge/App/AppState.swift
skillforge-ios/SkillForge/App/SkillForgeInfo.plist
skillforge-ios/SkillForge/Auth/KeychainStore.swift
skillforge-ios/SkillForge/Networking/MobileApiClient.swift
skillforge-ios/SkillForge/Networking/EndpointProbe.swift
skillforge-ios/SkillForge/Pairing/PairingPayload.swift
skillforge-ios/SkillForge/Pairing/PairingView.swift
skillforge-ios/SkillForge/Pairing/QRScannerView.swift
skillforge-ios/SkillForge/Chat/ChatView.swift
skillforge-ios/SkillForge/Chat/SessionListView.swift
skillforge-ios/SkillForge/Chat/MessageBubbleView.swift
skillforge-ios/SkillForge/Chat/ComposerView.swift
skillforge-ios/SkillForge/Pending/PendingCardView.swift
skillforge-ios/SkillForge/Attachments/AttachmentPicker.swift
skillforge-ios/SkillForge/Push/PushRegistration.swift
skillforge-ios/SkillForgeTests/
```

XcodeGen requirements:

- [ ] iOS deployment target: iOS 17.0.
- [ ] Swift version: Swift 6 or the Xcode default supported by Xcode 26.6.
- [ ] Bundle identifier: `com.skillforge.companion.dev`.
- [ ] Camera usage description in Info.plist for QR scanning.
- [ ] File/document picker capability for user-selected attachment upload.
- [ ] No contacts, calendar, location, photo-library scanning, or background socket capability in V1.
- [ ] Add `.gitignore` entries for `xcuserdata`, `DerivedData`, and build products.

Initial app behavior:

- [ ] On launch, read endpoint/device token from Keychain.
- [ ] If missing, show `PairingView`.
- [ ] `PairingView` opens QR scanner and manual setup-code entry.
- [ ] QR scanner decodes `skillforge.mobile_pairing` JSON.
- [ ] Endpoint probe tests `/api/mobile/pairings/{pairingId}` or a lightweight health path against the QR `endpoints[]`.
- [ ] Claim pairing with the first reachable endpoint.
- [ ] Save selected endpoint and device token in Keychain.
- [ ] Transition to `ChatView`.

Verification:

```bash
cd skillforge-ios
xcodegen generate
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' build
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' test
```

If `generic/platform=iOS Simulator` cannot run tests on the local Xcode version, list devices and use an installed simulator:

```bash
xcrun simctl list devices available | rg 'iPhone'
```

## Workstream 6 - iOS Chat And Mobile API

Networking models:

- [x] `MobileApiClient.me()` as the bootstrap check.
- [x] `MobileApiClient.listSessions()`.
- [x] `MobileApiClient.createSession(agentId:)`.
- [x] `MobileApiClient.getMessages(sessionId:)`.
- [x] `MobileApiClient.sendMessage(sessionId:text:attachmentIds:)`.
- [x] `MobileApiClient.answerAsk(sessionId:askId:answer:)`.
- [x] `MobileApiClient.answerConfirmation(sessionId:confirmationId:decision:)`.
- [x] `MobileApiClient.uploadAttachment(sessionId:fileURL:mimeType:)`.
- [ ] `MobileApiClient.registerPushToken(token:environment:)`.

UI tasks:

- [x] Chat header shows selected session, selected endpoint host, and connection state.
- [x] Session switcher shows recent sessions for the paired/default user.
- [x] Message list renders user and assistant messages, with basic summaries for non-text events.
- [x] Composer supports text send and disabled/running states.
- [x] Attachment button presents user-selected file/image picker.
- [x] Pending card UI supports ask answer, confirmation approve, and confirmation deny.
- [x] Foreground app resume refreshes current session and message history.
- [x] Unauthorized response clears token and returns to pairing.

Verification:

```bash
cd skillforge-ios
xcodegen generate
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' build
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=26.5' test
```

UI regression gate:

- [x] Add a `DEBUG`-only deterministic chat launch mode for XCUITest.
- [x] Add stable accessibility identifiers for the transcript, composer,
  scroll-to-bottom button, and messages.
- [x] Add XCUITest coverage for tap-to-dismiss and keyboard-dismiss plus
  scroll-to-bottom without a blank transcript.

## Workstream 7 - Push Registration And Notifications

Server tasks:

- [ ] Create `MobilePushTokenEntity` and repository after adding `V167__mobile_push_tokens.sql`.
- [ ] Add `MobilePushTokenService`.
- [ ] Add APNs configuration properties under `skillforge.*` namespace.
- [ ] Add token registration endpoint at `POST /api/mobile/client/push-token`.
- [ ] Store APNs token ciphertext and hash; do not store raw APNs token in logs.
- [ ] Add event hooks for task completed, task failed, user input required, and confirmation required.
- [ ] Gate APNs delivery behind configuration so local development can register tokens without sending pushes.

iOS tasks:

- [ ] Request notification permission from a clear user action.
- [ ] Register APNs token and call the mobile endpoint.
- [ ] Route notification taps to the target session.
- [ ] On foreground/resume, fetch session state from REST.

Verification:

```bash
mvn -pl skillforge-server -Dtest='MobilePushTokenServiceTest,MobilePushControllerTest' test
cd skillforge-ios && xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' build
```

## Slice Order

### Navigation Foundation - Four-tab assistant shell

- [x] Add a paired-only native `TabView` with Chat selected by default.
- [x] Preserve Chat transcript, draft, streaming, attachments, and pending cards across tab switches.
- [x] Initial session-backed Tasks projection delivered, then superseded after product review found it duplicated Sessions.
- [x] Add `GET /api/mobile/client/agents` with reduced DTOs and owner/public active-user-Agent filtering.
- [x] Validate session creation against the same mobile-selectable Agent rule.
- [x] Add searchable Agent selection stored as a non-secret device-local preference.
  Superseded by Agents Configuration Foundation after product review; Chat will retain
  Agent selection only inside its new-session flow.
- [x] Add Settings connection/device/scopes/diagnostics and confirmed local disconnect.
- [x] Add deterministic XCTest/XCUITest coverage for navigation, state preservation, Task routing, Agent selection, and disconnect.

Acceptance:

- [x] A paired launch opens Chat and exposes exactly four accessible tabs in the approved order.
- [x] Visiting every tab and returning to Chat preserves the visible transcript and composer draft.
- [x] A Task row opens the intended session; the original Agents-to-Chat selection behavior
  was delivered but is superseded by Agents Configuration Foundation.
- [x] Hidden, foreign, inactive, and system Agents cannot be listed or used by the mobile session-create API.
- [x] The Settings screen never exposes a raw device token and does not mislabel local disconnect as server revocation.

### Control Foundation - OpenClaw-inspired schedules and runs

- [x] Rename the Tasks root tab to Control without changing Chat view identity.
- [x] Keep Session browsing as an explicit Control destination that routes back to Chat.
- [x] Add mobile-only Schedule DTOs and endpoints deriving user ownership from device auth.
- [x] Add `schedule:read` / `schedule:write` scopes for new and already-active paired devices.
- [x] Show schedule status, Agent, next run, run-now, pause/enable, and recent run history.
- [x] Route a run with `triggeredSessionId` to the single Chat owner.
- [x] Add backend controller/security tests, URLProtocol contract tests, and deterministic XCUITest navigation/action coverage.

### Agents Configuration Foundation - read-only mobile configuration

Plan gate: requirements and technical design must be approved before implementation
because this slice changes the mobile wire contract and root navigation behavior.

- [x] Add `agent:read` to pairing scopes and idempotently backfill active devices.
- [x] Expand the mobile Agent roster DTO with safe identity, model, runtime and capability summaries.
- [x] Preserve unrestricted-tool semantics with explicit `toolAccess=all|allowlist` and fail-closed malformed JSON.
- [x] Add `GET /api/mobile/client/agents/{id}` with owned/default/shared visibility rules.
- [x] Prove DTO allowlists: no prompt bodies, credentials, lifecycle hooks, raw config or owner id.
- [x] Replace Agents-to-Chat selection with roster-to-detail navigation.
- [x] Keep Agent choice for new sessions inside Chat and preserve existing Chat state ownership.
- [x] Add deterministic fixtures for owned, default and shared Agent configuration access.
- [x] Add backend contract/security tests, iOS decoding/presentation tests and XCUITest navigation coverage.

Acceptance:

- [x] Opening an Agent shows configuration detail and never creates or opens a Session.
- [x] Returning to Chat preserves the same Agent, Session, transcript and composer draft.
- [x] Owned/default details show normalized runtime and capability configuration; shared details are redacted.
- [x] Prompt bodies and sensitive/internal fields never appear in mobile JSON or the iOS view hierarchy.
- [x] Existing paired phones receive `agent:read` without re-pairing.

### Outbound Artifact Foundation - Agent to Chat/iOS delivery

Design gate:

- [x] Confirm direct tool-side message append conflicts with final engine reconciliation.
- [x] Compare direct append, tool-result marker parsing, and typed artifact sidecar paths.
- [x] Select typed `SkillResult` sidecar plus engine-owned assistant refs.
- [x] Obtain user approval for this design before editing core/runtime code (2026-07-11).

Delivery split A - backend core and server:

- [x] Add the attachment-origin/idempotency/hash/caption migration and entity mappings.
- [x] Add typed published-artifact metadata and ordered `ToolExecutionOutcome` envelopes.
- [x] Merge pending artifacts into a terminal assistant Message only after paired tool results.
- [x] Defer artifact-message broadcast until ChatService reconciliation succeeds.
- [x] Add shared provider/full-compact assistant-ref placeholders with fail-closed fallback.
- [x] Extract shared type detection and add streaming `ChatAttachmentService.importGeneratedFile`.
- [x] Add deterministic `sessionId + toolUseId` idempotency and `uploaded -> published` lifecycle.
- [x] Add referenced-row status repair and orphan/crash cleanup tests.
- [x] Add `ArtifactWorkspaceService`, per-run prompt/context exposure, and canonical cleanup.
- [x] Add and register `PublishChatArtifact`; keep `SendChannelFile` behavior unchanged.
- [x] Add streaming mobile download with Range, ETag/304, If-Range and secure headers.
- [x] Add focused engine, persistence, compact, tool security, and controller tests.

Delivery split B, after backend API/database verification:

- [x] Decode attachment reference metadata and retain assistant/pure attachment messages.
- [x] Add authenticated attachment download/cache repository.
- [x] Render image cards with full-screen preview, progress, error, and retry.
- [x] Render document cards with Quick Look and system share.
- [x] Preserve transcript/message identity during download, reconnect, and REST catch-up.
- [x] Remove Dashboard's user-only assistant attachment filtering and add retry states.
- [x] Add XCTest/XCUITest and Dashboard component/reducer coverage.

Acceptance:

- [ ] Agent publishes one image and one document from a normal non-channel Chat session.
- [ ] Dashboard and iOS show the same assistant attachments exactly once.
- [x] iOS previews and shares the image/document through authenticated downloads.
- [x] A revoked device and a different user/session cannot download the artifact.
- [x] Tool pairing, final persistence reconciliation, and light/full compact tests pass.
- [x] Reconnect, retry, keyboard, and scroll interactions do not blank the transcript.

Verification evidence (2026-07-11): backend reactor 3276 tests passed; iOS 72 unit tests
and outbound attachment UI tests passed; the keyboard/scroll stress case passed three
consecutive isolated iterations after one transient failure in the full serial UI run;
Dashboard focused tests, TypeScript compilation, and production build passed. Real-device
normal-session image/document publication and cross-client exactly-once remain manual gates.

### Slice 1 - Pairing MVP

- [ ] V166 schema.
- [ ] Pairing/device entities and repositories.
- [ ] Pairing create/status/claim/revoke backend.
- [ ] `MobileAuthInterceptor`.
- [ ] Dashboard Mobile Devices QR page.
- [ ] iOS pairing shell with QR scan, endpoint probe, claim, Keychain save.

Acceptance:

- [ ] User opens Dashboard `/mobile-devices`, clicks `Pair iPhone`, and sees QR.
- [ ] User installs app through Xcode, scans QR, and pairs successfully.
- [ ] DB shows one active `t_mobile_device` and one claimed pairing request.
- [ ] Revoking the device makes `/api/mobile/client/me` return 401.

### Slice 2 - Chat MVP

- [x] Mobile bootstrap endpoint.
- [x] Sessions list/create/get/messages endpoints.
- [x] Message send endpoint.
- [x] iOS ChatView, session switcher, message history, composer.
- [x] Foreground refresh on launch/resume.

Acceptance:

- [ ] Paired iPhone opens directly to Chat.
- [ ] User sends a text message from iOS.
- [ ] Assistant response appears after REST refresh.
- [ ] User can switch between sessions for the paired user.

### Slice 3 - Human-In-The-Loop And Attachments

- [x] Mobile answer endpoint.
- [x] Mobile confirmation endpoint.
- [x] Mobile attachment upload endpoint.
- [x] iOS pending cards and attachment picker.

Acceptance:

- [ ] `ask_user` can be answered from iOS and the agent loop continues.
- [ ] confirmation can be approved/denied from iOS and the agent loop continues.
- [ ] User-selected file/image uploads into a session and is included in send.

### Slice 4 - Push And Polish

- [ ] V167 push token schema.
- [ ] Push token registration.
- [ ] APNs configuration gate.
- [ ] Notification tap routing.
- [ ] Device revoke UI polish.
- [ ] Real device E2E pass over LAN/Tailscale.

## Runtime Connectivity And Transcript Hardening (2026-07-13)

- [x] Clear the composer synchronously before asynchronous send work starts; restore the submitted draft only
  when failure occurs and the user has not typed a replacement.
- [x] Keep the transcript container identity stable for the selected Session and retain that Session when a
  background list refresh temporarily omits it.
- [x] Persist the normalized QR endpoint set in Keychain alongside the active endpoint and device token.
- [x] Prefer a reachable private LAN endpoint, fall back to Tailscale/public HTTPS, and reconnect Chat without
  clearing Session, transcript, composer, or optimistic state.
- [x] Add `VITE_SKILLFORGE_MOBILE_ENDPOINTS` support so newly generated Dashboard QR payloads can contain both
  LAN and Tailscale candidates.
- [x] Add focused XCTest and Dashboard tests for composer clearing, stable transcript identity, session refresh
  retention, endpoint ordering, startup selection, and runtime switching; add XCUITest coverage for composer
  clearing plus keyboard/streaming transcript continuity.
- [ ] Real-device acceptance: pair from a QR containing LAN and Tailscale, verify LAN wins on Wi-Fi, disable
  Tailscale while staying on Wi-Fi, then leave Wi-Fi and verify Tailscale recovery.

Verification on 2026-07-13: all 116 `SkillForgeTests` passed; all 29 `SkillForgeUITests` passed,
including the composer, keyboard, transcript, and streaming handoff scenarios. The five focused Chat
keyboard/handoff UI tests also passed after the final attachment repository endpoint handoff. The Release
simulator build passed. Dashboard Mobile Devices tests passed 7/7 and the production build passed. Real-device
network-transition acceptance remains pending.

Acceptance:

- [ ] iOS registers an APNs token when permission is granted.
- [ ] Server stores encrypted/ciphertext APNs token and token hash.
- [ ] With APNs config enabled, task completion/input-required notifications reach the device.
- [ ] Without APNs config, the app remains fully usable through foreground refresh.

### Interaction Polish - New Conversation, Sessions, Settings

- [x] Replace Chat header Quick Actions with a shared New Conversation Agent picker.
- [x] Route Session List `+` through the same creation flow.
- [x] Preserve current transcript/draft when creation is cancelled or fails.
- [x] Add current-Agent Session search, status filter, pull-to-refresh, status and updated-time rows.
- [x] Move destructive disconnect exclusively to Settings.
- [x] Add persisted System/Light/Dark appearance selection.
- [x] Add truthful notification permission status and explicit permission request action.
- [x] Add Permissions & Privacy copy and retain redacted diagnostics.
- [x] Add policy XCTest and deterministic XCUITest coverage for both creation entry points, filtering,
  tab state preservation, appearance, and notification state.

Verification evidence (2026-07-12): 82 unit tests and 23 UI tests passed on the iPhone 17 Pro
iOS 26.5 simulator; the Release simulator build passed. Notification authorization and visual
layout still require a real-device pass before release.

### Connection Health - Settings

- [x] Add a testable connection-health state and safe error classification policy.
- [x] Replace static paired status with an actionable summary and last-check timestamp.
- [x] Add endpoint/service/device-auth/realtime diagnostic detail and retry action.
- [x] Preserve pairing on network/service failure and keep fail-closed 401 behavior.
- [x] Add deterministic XCTest/XCUITest coverage for healthy and failure presentation.

Verification on 2026-07-13: the complete iOS scheme passed 115/115 tests on the iOS 26.5
simulator before the final lifecycle hardening. After moving cancellation to the whole Settings
navigation lifetime and adding credential identity, all 92 unit tests plus the two Connection Health
UI tests passed; the final Release simulator build also passed.

### Realtime Handoff And Pairing Review

- [x] Add a failing regression for stale REST followed by committed REST while a throttled delta is pending.
- [x] Route initial load, foreground catch-up, send polling, and WebSocket reload through one snapshot handoff.
- [x] Preserve legitimate identical persisted turns while removing only the covered transient tail.
- [x] Replace the always-visible manual JSON/setup-code form with Scan plus collapsed Paste Payload.
- [x] Add server review and explicit confirmation before consuming the one-time pairing secret.
- [x] Normalize pairing errors and keep raw payload/secret out of the post-decode UI and accessibility tree.
- [x] Add focused XCTest/XCUITest coverage for duplicate handoff, review, paste, keyboard, and small-screen layout.

Verification on 2026-07-13: all 108 `SkillForgeTests` passed; all 28 `SkillForgeUITests`
passed on iPhone 17 Pro / iOS 26.5 before the final assertion-order correction; the corrected
handoff gate plus related policy tests passed 14/14. Pairing keyboard and maximum Dynamic Type also
passed on the compact iPhone 16e simulator. The Release simulator build passed. Real-camera scan,
permission denial, and rapid cancellation remain device acceptance checks because Simulator has no
camera.

## Security Review Checklist

- [ ] QR payload contains no long-lived API token.
- [ ] Pairing secrets expire and are single-use.
- [ ] Device token hash is unique and compared in constant-time style.
- [ ] Revoked device tokens cannot access `/api/mobile/client/**`.
- [ ] Mobile controllers never trust client-provided `userId`.
- [ ] Attachment upload validates session ownership through mobile principal.
- [ ] Push token raw value is never logged.
- [ ] Dashboard revoke endpoint verifies current user ownership.
- [ ] Claim endpoint reveals no cross-user pairing metadata on wrong secret.

## Full Verification

Run after each slice:

```bash
mvn -pl skillforge-server test
cd skillforge-dashboard && npm run typecheck && npm test -- --run && npm run build
cd skillforge-ios && xcodegen generate && xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' build
```

Run before claiming V1 complete:

```bash
mvn test
cd skillforge-dashboard && npm run typecheck && npm test -- --run && npm run build
cd skillforge-ios && xcodegen generate && xcodebuild -project SkillForge.xcodeproj -scheme SkillForge -destination 'generic/platform=iOS Simulator' build
```

Manual E2E before completion:

1. Set `VITE_SKILLFORGE_MOBILE_ENDPOINTS` to the reachable LAN URL followed by the Tailscale HTTPS URL,
   allow the Tailscale hostname in Vite, then restart the dashboard.
2. Start SkillForge backend and dashboard.
3. Open Dashboard `/mobile-devices` and generate a new QR.
4. Install app to a real iPhone through Xcode. Disconnect an older pairing before this test because legacy
   Keychain records contain only their original endpoint.
5. Scan the new QR from the iPhone and confirm pairing succeeds.
6. On Wi-Fi, verify the app selects the LAN endpoint and can send a chat message.
7. Disable Tailscale while remaining on Wi-Fi and verify chat continues through LAN without losing transcript
   or draft state.
8. Re-enable Tailscale, leave Wi-Fi, and verify the app switches to Tailscale HTTPS without re-pairing.
9. Trigger `ask_user` and answer it from iOS.
10. Trigger confirmation and approve/deny it from iOS.
11. Upload a file or image from iOS.
12. Revoke the device in Dashboard and verify the app returns to pairing.

## Completion Criteria

- [ ] All V1 acceptance criteria in `prd.md` pass or are explicitly scoped to APNs configuration availability.
- [ ] Requirement package links from `docs/README.md` and `docs/todo.md` resolve.
- [ ] Backend, Dashboard, and iOS verification commands pass in the current turn.
- [ ] Browser check confirms Dashboard Mobile Devices UI has no overlapping controls.
- [ ] Real iPhone pairing has been tested through Xcode direct install.
- [ ] Final commit excludes unrelated compaction/session-service changes unless the user separately asks to include them.

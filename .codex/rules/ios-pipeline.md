# Native iOS Pipeline

Read this with `pipeline.md` and `ios.md` for non-Solo work under
`skillforge-ios/**`. This file only adds iOS-specific triage, role routing, and
verification. Generic execution, review, and risk upgrades remain owned by
`pipeline.md`; this file does not require fixed agent roles.

## Runtime Mapping

Use the main session by default. When independent work or Full review benefits
from subagents, use bounded tasks with disjoint ownership and inherit the current
model. The main session integrates results and verifies evidence using available
runtime tools. Disclose when Full review could only be performed inline.

## Environment Evidence

Before choosing APIs or test strategy, record these separately:

- Xcode and Swift compiler versions.
- Selected SDK and installed simulator runtime.
- Effective `SWIFT_VERSION` language mode and iOS deployment target.
- Strict concurrency, approachable concurrency, and default actor isolation when
  the task touches concurrency.

The current compiler may be newer than the selected language mode. Every API
newer than the deployment target needs a compile-valid availability path and a
tested fallback when behavior differs.

## iOS Triage

Solo is limited to comments/copy, one-line constants or labels, mechanical moves,
and pure functions already locked by strong tests.

Mid includes isolated SwiftUI views, ordinary visible behavior, local reducers or
parsers, deterministic fixtures, and focused XCTest/XCUITest additions that do
not alter security, lifecycle, build capabilities, or wire contracts.

Do not classify iOS work as Full only because it touches a large or central view
file such as `ChatView.swift`. Classify by the behavior and failure boundary.
Ordinary local App interaction remains Mid even when its established
implementation lives in that file.

Mid includes these local-App changes when they do not alter a wire contract,
background lifecycle, security boundary, or reconciliation invariant:

- Chat layout, scroll/follow policy, animation, keyboard interaction, local
  unread counters, typing/running indicators, and deterministic UI hooks.
- Agent/Session search, filtering, grouping, detail actions, and explicit local
  navigation using existing APIs.
- Local presentation state shared by existing tabs when a stale value can cause
  only a recoverable UI inconsistency, not message loss, duplicate execution, or
  an auth/privacy failure.

Full is required for any of these red lights:

- Changes to `MobileRealtimeState.swift` or shared transcript/composer/session
  ownership that alter WebSocket/REST reconciliation, message identity,
  send/retry idempotency, cancellation, or persistence semantics.
- App entry/root navigation changes that alter authentication, pairing,
  notification/deep-link lifecycle, or broadly migrate cross-tab ownership.
- Pairing, endpoint selection, mobile auth, Keychain, token lifecycle, or device
  revocation.
- WebSocket/REST reconciliation, streaming identity, retry/idempotency, task
  cancellation, reconnect, foreground/background lifecycle, or shared concurrency.
- Any mobile wire-contract change, even iOS-only: DTO/event decoding, auth
  headers, endpoint construction, request/response shape, or error envelopes.
- `project.yml` target/dependency changes, signing, entitlements, capabilities,
  privacy strings, or background modes.
- Camera/QR, photos/files, attachment upload, APNs, deep links, or background work.
- A new third-party dependency, broad architecture/state migration, or any
  cross-stack backend/dashboard/iOS protocol change.

When one brief mixes Mid local interaction with a Full red-light capability,
split it into independently verifiable increments where practical. For example,
implement local Chat follow behavior and Agent/Session navigation as Mid, then
route APNs/background or protocol changes through a separate Full increment.

Mixed work inherits its highest risk. A visible SwiftUI change is Mid by default,
not Solo, because layout, focus, navigation, and scrolling need runtime evidence.

## Execution Additions

- Follow generic Solo/Mid/Full execution. Capture environment evidence when build,
  API availability, concurrency, or test execution depends on it; reuse established
  evidence from the same unchanged environment within the current task.
- Mid needs one focused review of Swift/SwiftUI behavior and test evidence, which
  the main session may perform. No separate planner, worker, or judge is required.
- Full review covers both checklists below. Use independent review when available;
  one reviewer may cover both. Add security/backend review only for affected boundaries.
- Split production and test work only when they can proceed independently. Shared
  production files and Debug fixture hooks have one integration owner; a test
  worker requests hooks instead of concurrently editing the same production files.
- Material architecture plans include API availability, state ownership, test
  layers, and simulator versus real-device acceptance. Planning/approval follows
  `think-before-coding.md`, not the number of agents or files involved.

## Reviewer Checklists

Code review checks:

- Swift correctness, actor isolation, Sendable safety, cancellation, and lifecycle.
- Stable message/view identity, bounded rendering work, and state ownership.
- API availability, network/auth/error contracts, memory ownership, and cleanup.
- SwiftUI data flow, navigation, layout, and rendering performance.

Product and test review checks:

- Spec behavior, loading/running/offline/error/empty states, and recovery paths.
- Dynamic Type, VoiceOver, Reduce Motion, contrast, hit targets, and focus return.
- Deterministic fixtures with no secrets, Keychain reads, or real network calls.
- XCTest/XCUITest behavior coverage, accessibility assertions, and flake risks.
- Honest simulator limits and explicit real-device acceptance gaps.

## External Skills And Tools

- Optional SwiftUI reviewer: `twostraws/SwiftUI-Agent-Skill` `swiftui-pro`, pinned
  to release `1.1.0` / commit
  `be297ff80dddec529af1f9b1f1f114aab6c9d11c` after user approval and source audit.
- Apply SkillForge overrides: minimum iOS 17, no unguarded newer APIs, preserve
  local architecture, and allow established UIKit/AVFoundation interop.
- Invoke it only for SwiftUI implementation self-review or independent review. If it
  is unavailable, use `ios.md` plus the reviewer checklists above.
- XcodeBuildMCP requires separate approval. Before enabling it, audit tool-schema
  cost and expose only `simulator`, `debugging`, and `ui-automation` workflows.
- External skills are advisory. They never override requirements, deployment
  target, architecture, security rules, or checked-in tests.
- Do not install broad iOS skill bundles by default. Existing XCTest stays the
  default; adopt Swift Testing per file only after project/CI compatibility proof.

## Verification Increment

1. Discover an installed destination; do not hardcode simulator UDIDs.
2. When project inputs change, run `xcodegen generate` and inspect its diff.
   `project.yml` is source of truth; `xcuserdata` is not a controlled artifact.
3. Mid: run relevant tests and a simulator build. Full: run the full scheme and
   Release simulator build. Do not repeat identical tests on an unchanged state;
   broaden checks when shared behavior, failures, or integration risk warrants it.
4. For critical UI behavior, run the named XCUITest with `-resultBundlePath` and
   use `xcresulttool` to prove non-zero execution and exact pass/fail counts.
5. For Full or build-configuration changes, use separate DerivedData for the
   Release simulator build.
6. Capture a stable simulator screenshot and assert critical controls through
   accessibility; screenshots alone are insufficient.
7. For wire changes, verify the live backend shape with secrets redacted and run
   the relevant backend/dashboard gates.
8. Report real-device items as `PASS`, `NOT_RUN`, or `BLOCKED`: signing, Keychain,
   camera, attachments, APNs, LAN/Tailscale, and background behavior.

Command template after choosing an installed destination:

```bash
cd skillforge-ios
xcodegen generate
DEST='platform=iOS Simulator,name=<installed-device>,OS=<installed-runtime>'
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -destination "$DEST" test
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -configuration Release -derivedDataPath /tmp/skillforge-ios-release \
  -destination 'generic/platform=iOS Simulator' build
```

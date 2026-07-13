# iOS Development Rules

Read this for changes under `skillforge-ios/**`, SwiftUI interaction work,
Xcode project generation, iOS tests, simulator checks, or real-device issues.
For non-Solo iOS work, also read `ios-pipeline.md`.

## Project And Build

- Treat `skillforge-ios/project.yml` as the Xcode project source of truth. Run
  `xcodegen generate` after target, source, build-setting, or scheme changes and
  keep the generated `SkillForge.xcodeproj` aligned.
- Use an installed simulator from `xcrun simctl list devices available`; do not
  assume `generic/platform=iOS Simulator` can execute tests.
- Keep credentials, pairing secrets, device tokens, and user transcripts out of
  launch arguments, fixtures, screenshots, and test logs.
- Swift concurrency warnings are defects. Keep UI automation and UI state on
  `@MainActor`; do not downgrade Sendable diagnostics globally.
- Treat compiler version, Swift language mode, SDK, and deployment target as
  separate constraints. Do not infer API availability from the Xcode version.

## Test Layers

Use the smallest layer that proves the behavior, then add the next layer when
the risk crosses a boundary:

1. XCTest for parsing, reducers, policies, and state transitions.
2. URLProtocol-backed tests for request/response and error contracts.
3. XCUITest for keyboard, focus, scrolling, sheets, navigation, accessibility,
   and other critical interactions.
4. Simulator plus live local backend for pairing, WebSocket, REST catch-up, and
   streaming integration.
5. Real-device verification for camera QR, LAN/Tailscale reachability, Keychain,
   signing, background behavior, attachments, and APNs.

Every user-reported interaction regression should get an XCUITest when it can be
reproduced without external hardware. A unit test alone cannot prove SwiftUI
layout, focus, keyboard, or scroll behavior.

## Deterministic UI Fixtures

- UI tests may select deterministic app state through a launch argument, but
  the hook must compile only in `DEBUG` and must not bypass production auth.
- Reuse production views and interactions in fixtures. Fixtures may replace
  network data, pairing, and timing only where needed for determinism.
- Give critical controls and assertions stable accessibility identifiers.
- Keep one focused fixture per workflow instead of building a second fake app.

## Interaction And Realtime Footguns

- Observe real keyboard show/hide state for keyboard-dependent scrolling; focus
  state alone is racy during tap and interactive-dismiss gestures.
- Do not animate `ScrollViewReader.scrollTo` while keyboard geometry is changing.
- Throttle streaming updates and preserve a stable message identity while text
  grows; do not rebuild the whole transcript for each delta.
- REST catch-up must not erase newer transient WebSocket text or tool state.
- WebSocket tasks, timers, and deferred UI tasks must be cancelled on session
  switch and view disappearance.

## Standard Verification

```bash
cd skillforge-ios
xcodegen generate
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro,OS=latest' test
```

For a critical UI flow, run its XCUITest explicitly and inspect the `.xcresult`
summary. Build success without executing the UI test is insufficient.

## Tools

- Prefer `xcodebuild`, `xcrun simctl`, XCTest, and XCUITest as the repeatable
  source of truth.
- Use Computer Use or Simulator screenshots for visual inspection after the
  automated assertions pass.
- An Xcode-focused MCP may wrap the same commands for convenience, but it does
  not replace checked-in tests or verification commands. Add one only when it
  reduces repeated work without materially increasing tool-schema context.

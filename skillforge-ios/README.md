# SkillForge iOS Companion

Native SwiftUI companion for a self-hosted SkillForge server. The app supports Dashboard QR pairing,
LAN-first/Tailscale fallback, chat and sessions, realtime updates, pending decisions, attachments, schedules,
read-only Agent configuration, and connection diagnostics.

## Requirements

- Xcode with an iOS 17 or newer SDK.
- XcodeGen.
- An iOS 17+ simulator for automated tests.
- A development team and a paired iOS 17+ iPhone for real-device installation.

## Generate the project

`project.yml` is the project source of truth. Regenerate after changing targets, sources, build settings,
capabilities, or Info.plist properties.

```bash
cd skillforge-ios
xcodegen generate
open SkillForge.xcodeproj
```

For a physical device, select your own Team in the app target's Signing & Capabilities settings. Use a unique
bundle identifier when required. Xcode-managed user data and DerivedData are intentionally ignored.

## Pair with SkillForge

1. Start the server and dashboard.
2. Configure `skillforge-dashboard/.env.local` from `.env.example` with reachable LAN and optional Tailscale URLs.
3. In Dashboard, open **Mobile Devices** and create a new QR.
4. In the app, scan the QR, compare the setup code, and confirm pairing.

The device token is returned once and stored in Keychain. The server stores only its SHA-256 hash. Revoking the
device in Dashboard invalidates the token immediately.

## Build and test

Discover an installed simulator before choosing a destination:

```bash
xcrun simctl list devices available
```

Then run the scheme and keep an xcresult bundle for inspection:

```bash
cd skillforge-ios
xcodegen generate
DEST='platform=iOS Simulator,name=iPhone 17 Pro,OS=latest'
xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -destination "$DEST" \
  -resultBundlePath /tmp/SkillForgeTests.xcresult test

xcodebuild -project SkillForge.xcodeproj -scheme SkillForge \
  -configuration Release \
  -derivedDataPath /tmp/skillforge-ios-release \
  -destination 'generic/platform=iOS Simulator' build
```

Use an installed device name/runtime in `DEST`; the example is not guaranteed to exist on every Mac.

## Simulator and real-device boundaries

The pairing screen includes a manual JSON/setup-code fallback for the simulator. A simulator result does not
prove camera QR, signing/provisioning, Keychain fidelity, LAN/Tailscale reachability, background behavior,
attachment sharing, or APNs. Record those separately during real-device acceptance.

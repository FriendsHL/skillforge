# IOS-LOCAL-SIGNING-STABILITY — 本机 iOS 签名选择持久化

> 状态：implemented / automated verified；待 iPhone 重连后完成 Xcode GUI no-prompt 验收
> 模式：Full（触及 `project.yml`、签名与 provisioning）
> 优先级：P1

## 用户问题

Xcode 构建到真机时反复要求选择 Apple 账号 / Development Team。此前通常不需要重复选择，希望恢复为一次配置、
后续 XcodeGen 重建和日常 Debug build 都能稳定复用。

## 根因证据（2026-07-18）

1. 修复前的 `skillforge-ios/project.yml` 只声明 `CODE_SIGN_STYLE: Automatic`，没有声明 Development Team 或本机配置入口。
2. 当时 `SkillForge.xcodeproj/project.pbxproj` 中的 Team 是 Xcode 手动选择后产生的未提交差异，不是 XcodeGen
   source of truth；删除/重建生成工程时可能再次丢失。
3. 本机存在一张有效 Apple Development certificate，以及匹配
   `com.skillforge.companion.dev` 的自动 provisioning profile；Team、证书 OU 和 profile TeamIdentifier 一致。
4. 使用当前手动选择结果对已连接 iPhone 执行 Debug device build：`BUILD SUCCEEDED`，Xcode 自动选中既有证书与
   profile。这排除了证书失效和 bundle ID 不匹配，问题集中在配置持久化。
5. Release 仍带 Push Notification entitlement；免费 Personal Team 不支持 APNs。这个限制与 Debug 反复选 Team
   是两件事，本需求不伪装解决 APNs 发布资格。

## 方案比较

### A. 本机可选 xcconfig（推荐）

- 版本控制 `Config/Signing.xcconfig`，由它使用 `#include?` 可选加载 `Signing.local.xcconfig`。
- `Signing.local.xcconfig` 写入本机 Team ID，并加入 `.gitignore`。
- XcodeGen 为 App、Unit Test、UI Test 的 Debug/Release configuration 绑定基础 xcconfig。
- 清理生成工程或再次执行 XcodeGen 后，本机 Team 仍可恢复；没有本地文件的机器继续正常进行 simulator build。

优点：个人 Team 不进入公开仓库；Xcode GUI 与命令行都生效；只需配置一次。缺点：新机器需要一次初始化。

### B. 把 Team ID 直接写进 `project.yml`

最少改动，但仓库是 public，且会把个人开发团队绑定给所有 clone；协作者仍需覆盖。**不推荐**。

### C. 每次通过环境变量或 `xcodebuild DEVELOPMENT_TEAM=...` 注入

不会写入仓库，但 Xcode GUI 不一定继承 shell 环境，仍会制造“命令行能 build、Xcode 又要选”的分叉。**不推荐**。

## 已实施方案 A

2026-07-18 已完成：

1. 新增 tracked `skillforge-ios/Config/Signing.xcconfig`，提供空默认值并 optional include 本机文件。
2. 新增 ignored `skillforge-ios/Config/Signing.local.xcconfig`，在当前机器写入已确认的 Team ID。
3. 在 `project.yml` 的三个 target 中为 Debug/Release 绑定基础 xcconfig。
4. 移除生成 `project.pbxproj` 中 Xcode 手工写入的 `DEVELOPMENT_TEAM`，避免 target setting 覆盖 xcconfig。
5. 文档提供新机器的一次性配置步骤，不存 Apple ID、证书私钥或 profile 内容。
6. Full gate 同时发现既有“iPhone 仅竖屏”策略没有进入 XcodeGen source of truth；现已显式写入
   `project.yml`，iPad 仍保留四方向，并在 UI Test launch helper 中隔离测试间设备方向状态。

Apple 官方支持 `.xcconfig` 分层和 `#include?` optional include；XcodeGen 官方 Project Spec 支持按 configuration
绑定 `configFiles`：

- [Apple：Adding a build configuration file](https://developer.apple.com/documentation/xcode/adding-a-build-configuration-file-to-your-project)
- [XcodeGen：ProjectSpec](https://github.com/yonaskolb/XcodeGen/blob/master/Docs/ProjectSpec.md)

## 验收标准

1. `xcodegen generate` 连续两次生成确定，且生成工程不包含手工 Team diff。
2. 未配置本机签名文件时 simulator test/build 正常。
3. 配置一次本机文件后，连接当前 iPhone 连续执行两次 Debug device build，均无需 UI 选择 Team。
4. App、Unit Test 与 UI Test target 的 effective `DEVELOPMENT_TEAM` 一致。
5. tracked diff 不包含 Apple ID、证书、profile、私钥或个人 Team ID。
6. Release/APNs 真机签名仍按付费 Team 能力单独验收，不因 Debug PASS 被误报完成。

## 当前验证结果（2026-07-18）

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| XcodeGen source of truth | PASS | 连续两次生成的 `project.pbxproj` SHA-256 均为 `bc903b7b354cabba1775d35227203bdc92f89f581d71ad3a6de313d220bce68c`；工程内无手工 Team setting |
| 无本机配置的 simulator build | PASS | 临时移除 local xcconfig 后 Debug simulator `BUILD SUCCEEDED`，随后恢复本机文件 |
| 本机签名解析 | PASS | App、Unit Test、UI Test 均为 Automatic、Team 非空且一致；local 文件由 `.gitignore` 命中 |
| 连续 device signing build | PASS（命令行） | 最终 XcodeGen 生成后连续两次 `generic/platform=iOS` Debug build 成功，自动复用现有 certificate/profile |
| 真实 iPhone Xcode GUI no-prompt | NOT_RUN | 修复后设备已断开；修复前真实 iPhone Debug build 成功，重连后仍需从 Xcode 连续 Run 两次确认不再弹 Team 选择 |
| iOS Full gate | PASS | iPhone 17 Pro / iOS 26.5 simulator，完整 scheme 332/332（265 XCTest + 67 XCUITest），0 failure、0 skipped；结果包 `/tmp/SkillForge-Signing-Full-Final-20260718.xcresult` |
| Release simulator | PASS | 最终配置下 Release build 成功 |
| 方向策略回归 | PASS | 旋转命名用例 1/1；旋转后紧接跨 Agent 导航 2/2；完整 gate 中同样通过 |
| APNs / 发布资格 | NOT_RUN | 免费 Personal Team 的 Push 限制不在本需求内，仍需付费 Team 与真实凭据 |

## 新机器一次性配置

1. 在 `skillforge-ios/Config/Signing.local.xcconfig` 写入一行 `DEVELOPMENT_TEAM = YOUR_TEAM_ID`。
2. 在 Xcode Settings 中登录对应 Apple 账号，并确保本机有可用的 Development certificate/profile。
3. 执行 `xcodegen generate` 后照常从 Xcode 或 `xcodebuild` 构建；不需要把 Team 写入 `project.yml`。

这个 local 文件不得提交。证书过期、profile 失效或 Apple 登录状态失效时，Xcode 仍可能要求重新认证，属于正常外部状态变化。

## 风险与边界

- `.xcconfig` target 层级必须避免被 Xcode 手工 target setting 覆盖。
- `Signing.local.xcconfig` 缺失应保持 simulator 可用；physical device build 应给出普通的缺 Team 诊断。
- provisioning profile 过期或证书撤销仍会要求 Apple 重新认证；本需求只消除项目配置反复丢失。
- 不运行 `-allowProvisioningUpdates` 作为日常隐式修复，避免构建时静默修改 Apple Developer Portal 状态。

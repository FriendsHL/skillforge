# IOS-PERSONAL-APP-LIBRARY — 跨 Session 的 Personal App 统一入口

> 状态：implemented / Full automated pipeline verified（2026-07-17）；待用户真机 dogfood
> 优先级：P1
> 模式：Full（跨 Session 查询、移动端 API、状态生命周期与 iOS 信息架构）

## 用户反馈

当 Agent 在多个 Session 中持续生成 Personal App 后，用户不能靠逐个进入 Session、向上翻聊天记录来寻找
历史页面。Personal App 必须有统一入口，否则生成数量越多，实际可发现性和复用价值越低。

## 用户目标

在 SkillForge iOS App 中打开一个 Personal Apps 页面，集中查看当前用户在所有 Session 中生成的交互页面，
能够快速搜索、筛选、收藏、再次打开，并能返回来源对话继续与 Agent 协作。

## 核心需求

1. 在 `Control → Workspace` 或等价稳定入口提供 `Personal Apps` Library，不占用 Chat 主流程。
2. 默认按最近生成或最近打开排序，跨当前用户的全部 Session 聚合。
3. 每条记录显示：标题、摘要、来源 Agent、来源 Session、生成时间、最近打开时间、离线状态和权限状态。
4. 支持搜索标题/摘要，以及按 Agent、Session、时间、收藏、已下载筛选。
5. 支持收藏、取消收藏、删除本地缓存；服务端 Artifact 删除需单独确认，不与清缓存混淆。
6. 点击记录直接进入现有安全 Viewer；关闭后返回 Library 原位置。
7. 支持从记录跳回来源 Session，并定位到对应 assistant message。
8. Artifact-scoped state 在从 Chat 或 Library 打开时必须一致，不能形成两份进度。
9. 被删除、过期、无权限或 device revoke 的记录显示明确状态，不打开空白页面。
10. Library 只展示当前用户有权限访问的 Artifact；跨用户、跨设备撤销与来源 Session 权限继续由服务端校验。
11. 首次加载时保留搜索、范围和筛选框架，并显示与最终卡片同构的骨架屏；不得先展示空白内容再突然跳变。
12. 卡片摘要需折叠多余空白并限制为 96 个字符、普通字号最多两行；搜索仍使用完整服务端摘要，不能因展示精简而丢失检索信息。

## 建议信息架构

- 最近使用
- 收藏
- 已下载
- 全部
- 搜索
- Agent / Session / 时间筛选

卡片操作：

- 打开
- 收藏
- 分享
- 查看来源对话
- 清除本地下载
- 删除 Artifact（需确认且仅在服务端允许时出现）

## 数据与 API 需求

- 服务端提供当前用户可见的 Interactive Artifact 分页列表，不从完整 transcript 在客户端扫描拼装。
- 返回稳定 artifact identity、来源 session/message、manifest 展示字段和可用性状态，不返回 HTML 正文。
- 最近打开、收藏和本地下载状态需要明确事实源；V1 可将最近打开/收藏保存在设备端，跨设备同步另行确认。
- 列表接口必须支持分页、排序和最小化查询，避免 Session/Attachment N+1。

## 验收标准

1. 用户无需打开任何来源 Session，即可看到跨 Session 的 Personal App 列表。
2. 生成 50 个 Artifact 后，搜索和分页仍可流畅使用。
3. 从 Library 与 Chat 打开同一 Artifact，保存状态完全一致。
4. 可以从 Artifact 返回来源 Session 并定位原消息。
5. device revoke、跨用户访问和已删除 Artifact 均被拒绝且 UI 可恢复。
6. VoiceOver、Dynamic Type、深色模式和窄屏下可完成搜索、收藏、打开与返回。
7. 有 API/数据库验证、iOS XCTest、URLProtocol contract test、XCUITest 和真机 dogfood 证据。
8. 冷缓存进入 Library 时立即看到页面框架和卡片骨架，加载完成后卡片布局稳定；长摘要不会让单张普通字号卡片占满大部分屏幕。

## 非目标

- V1 不做公开发布、Artifact marketplace 或多人协作。
- V1 不把普通 PDF、Word、Excel 等附件混入 Personal Apps；普通文件继续使用文件/附件入口。
- V1 不允许 Library 绕过来源 Session、用户或设备权限。

## 与当前需求的关系

- [IOS-INTERACTIVE-ARTIFACTS](../2026-07-16-IOS-INTERACTIVE-ARTIFACTS/index.md) 提供发布与安全 Viewer。
- [IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH](../2026-07-17-IOS-RUNTIME-RECOVERY-ARTIFACT-POLISH/index.md) 先收口卡片、模板、错误和工具契约。
- [IOS-PROTOTYPE-APP-PARITY](../2026-07-16-IOS-PROTOTYPE-APP-PARITY/index.md) 已有 Proposed Library 原型入口。

## 推荐顺序

已按“Runtime Recovery / Artifact Polish → Library API 与数据生命周期 → iOS Library/Viewer → 全量回归”顺序完成。

## 实施与验证记录（2026-07-17）

- V176、跨 Session Library API、签名 keyset cursor、收藏/最近打开、来源消息定位、权限隔离和级联清理已实现。
- iOS 已提供 `Control → Workspace → Personal Apps` 统一入口，支持搜索、范围与 Agent/Session 筛选、分页、收藏、
  打开、分享、清除离线缓存、返回来源对话，以及 offline/unavailable/revoked 的显式状态。
- Library 首次请求期间保留完整筛选框架，并显示两张与最终布局同构的卡片骨架；真实卡片改为紧凑缩略标识、
  两行/96 字符摘要、来源与时间事实、状态 badge 和一行操作区。摘要精简仅发生在 SwiftUI presentation 层，
  服务端原始 caption、搜索和筛选数据不被截断。
- Chat 与 Library 复用同一 Artifact 下载 metadata 和 artifact-scoped state；异步下载加入 identity 与 operation-token
  双重提交保护，切换 Session、清理或取消后不会由旧任务回写错误文件或状态。
- Library 对已发布历史 manifest 使用独立的列表安全投影校验：继续拒绝非空权限/网络能力和畸形展示字段，但不因
  旧 `stateSchema` 使用 `enum`、`minimum/maximum` 而静默隐藏整张卡片；新发布仍执行完整严格校验。
- 真实服务进程已应用 V173–V176；一次性移动设备凭据下完成 production/current-user 隔离、排序、搜索过滤、
  cursor 与 filter 绑定、收藏/最近打开、manifest、完整下载、Range 206、跨用户拒绝、Session cascade、索引命中和
  device revoke 后 401 的 API/数据库验证，测试数据已清理。
- 后端完整 reactor：3924 tests，0 failures，0 errors，180 skipped，5 个模块 `BUILD SUCCESS`。
- iOS 全量 XCTest：263/263；完整 XCUITest：60/60；合计 323/323，Release simulator build 通过。
- 完整结果包：`/tmp/SkillForge-Final-LoadingCards-20260717.xcresult`；新增首帧、Library skeleton、长摘要和紧凑卡片
  断言均在全量套件中通过。Release 包的 `Info.plist` 含 `UILaunchScreen` 命名资产，`Assets.car` 可解析到
  `LaunchBackground` 与 `LaunchMark`。
- XcodeGen 连续两次生成哈希一致；需求与代码交叉审查结论为 PASS，无遗留代码或规格 blocker。

代码与自动化开发门已经完成。仍待用户在真实设备执行 50 个 Artifact 的流畅度、缓存/重启、跨 Session 定位、
revoke 恢复与实际网络 dogfood；该人工验收不以模拟器结果代替，需求包在验收前继续保留于 `active/`。

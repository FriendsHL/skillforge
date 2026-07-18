# IOS-INTERACTIVE-ARTIFACTS — 手机端 Personal App / Interactive Artifact

> 状态：implemented / simulator-verified；待真机 dogfood
> 优先级：候选 P1
> 范围：V1 生产代码、测试与设计文档

## 用户意图

电脑端 Task 结果通常以 Markdown 或文件交付；个人手机端更适合把部分结果变成可触摸、可筛选、
可继续操作的小应用。用户希望先用个人手机验证这种体验，再决定是否进入 Full pipeline。

## 结论

方向可行，而且与 Claude Artifacts、Gemini Canvas、ChatGPT Apps SDK、MCP Apps 的共同演进方向一致。
但产品对象应是受管的 `InteractiveArtifact`，不是把裸 `.html` 文件当普通附件，也不应全面替代 Markdown。

推荐首版采用：

- 消息中保留 3–6 行 Markdown 摘要。
- 同一 assistant message 附带一个 Personal App 卡片。
- 点击后在 App 内全屏打开离线、自包含、默认无网络的 HTML/CSS/JS bundle。
- 首版只能操作 Artifact 自己的数据，不调用 SkillForge tool，不注入 device token。
- 用户操作状态保存在 artifact-scoped storage；返回聊天后可把结构化摘要交给 Agent 继续处理。

## 文档

1. [research-report.md](research-report.md) — 完整调研报告与可操作性结论
2. [prd-draft.md](prd-draft.md) — 待确认产品范围
3. [tech-options.md](tech-options.md) — 技术路径、安全模型和 Full pipeline 切分
4. [iOS 交互原型](../../../prototypes/ios-assistant-companion/index.html) — 新增 Personal App 卡片与全屏态
5. [tech-design.md](tech-design.md) — Full pipeline 实施基线
6. [threat-model.md](threat-model.md) — 信任边界、攻击面与恶意 fixture corpus

## 已批准边界（2026-07-16）

1. V1 离线、自包含、无 Tool Bridge。
2. 每条 assistant message 最多一个 Personal App；暂不做独立 App Library。
3. 用户原生确认后，可把 schema-valid 结构化状态作为 user message 发回 Agent。
4. V1 只支持受控、自包含资源，不支持任意 npm/远程依赖。

## 实施结果（2026-07-17）

- `PublishInteractiveArtifact` 发布 UTF-8 单文件自包含 HTML；支持当前 run 的自定义 `file_path` 与固定
  allowlist 的平台 `template_id` 两种互斥来源，普通附件工具仍不接受 HTML。
- V173 保存并约束 manifest metadata；V174 更新 active user-agent 显式 allowlist。
- assistant message 只保存 `interactive_artifact_ref`；HTML 与 manifest 不进入 transcript。
- iOS 提供 Personal App 卡片、鉴权下载、非持久化 WKWebView、document-prefix fail-closed CSP、one-shot
  navigation gate、popup 拦截与 secured process recovery。
- document-start 文件/Clipboard guard 抵抗 same-stack 与 prototype tampering；native delegate 拒绝 iOS 18.4+
  open panel、media capture 和 motion/orientation。Bridge 具备 preflight、分桶限速、first-wins confirmation 与
  cooldown。
- 支持 artifact-scoped state 保存/恢复、重置和系统分享；missing/valid/invalid 三态加载，invalid state 回退
  initialData 并显示诊断。
- `submitSnapshot` 经过 64 KiB 上限、state schema 校验和原生确认后，才作为普通 user message 发给 Agent；
  invalid submit 不弹确认、不发送消息，并在 Viewer 中显示诊断。

## 验证证据（2026-07-17）

- `mvn -pl skillforge-server -am`：Core 338 + Tools 67 + Server 3390 = 3795 tests，
  0 failure，0 error，177 skipped；Observability 在该 reactor 中无测试计数，Server module 单独 3390 tests。
- P1-C focused owner：80/80；主会话独立 focused：66/66。
- iOS 全量 unit：178/178；其中 Personal App security：28/28。
- Interactive Artifact 专属 XCUITest：15/15；其中 security/state 降级定向集：7/7。
- iOS Release simulator build：成功。
- XcodeGen 连续生成两次 project SHA-256 均为
  `acb07681007a6f3f5c5ebf7f239dc071492b562913cfa3e07822137781a0ca7d`；全仓 `git diff --check` 通过。

## 剩余外部验收

- 真机离线 dogfood，并让真实 Agent 生成一次预算规划器。
- 真机 VoiceOver、Dynamic Type 与深色模式人工验收。

这些属于设备验收，不阻塞代码实现完成；完成前不把 Proposed 原型合并进 Current。

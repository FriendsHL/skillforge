# DESKTOP-MACOS-PACKAGE — SkillForge macOS 桌面安装包

> 状态：tech-design 草拟（用户离线，goal 指令直推；所有「草拟」决策待用户 ratify，Phase Final 后回写）
> 档位：🔴 Full（红灯 #4：新增 npm 模块 + 跨模块新增）
> 日期：2026-06-10

## User Request（原话）

> 我想基于 skillForge 做一个可以 mac os 可以安装的一个包。安装之后 类似 claude code 或者 codex 的 desktop。

解读：产出一个 macOS 可安装的 `.dmg`，安装后得到独立桌面 App（自带运行时，不依赖用户装 JDK/Node/PG），打开即是 SkillForge dashboard，类似 Claude Code Desktop / Codex Desktop 的形态。

## 现状取证（实现依据）

| 事实 | 证据 |
|---|---|
| server 是自包含 fat jar | `skillforge-server/pom.xml` spring-boot-maven-plugin repackage，mainClass `SkillForgeApplication` |
| 数据库自带 | `EmbeddedPostgresConfig`：zonky embedded PG，数据 `~/.skillforge/pgdata`，端口 **15432 硬编码**，开关 `skillforge.embedded-postgres.enabled` 默认 true，Flyway 自动迁移 |
| FE 全相对路径 | `api/client.ts` `baseURL: '/api'`；WS `${proto}://${window.location.host}/ws/...` → 同源 serve 则 FE **零改动** |
| server 不 serve 静态 SPA | 无 `static/` 目录，`WebMvcConfig` 只有 AuthInterceptor（`/api/**`，排除 `/api/auth/**`） |
| 本机工具链 | JDK 17 homebrew（jlink/jpackage 可用）、Node 24、electron 生态可装、arm64 / macOS 26.5 |

## 技术方案（草拟）

### D1 桌面壳选型：Electron（推荐）✔

- **Electron + electron-builder**：与 Claude Code Desktop 同形态；进程生命周期管理成熟；出 `.dmg` 一条命令。
- 备选 Tauri：体积小但需 Rust 工具链（本机未确认）+ sidecar 管理复杂度相当。
- 备选 jpackage：能出 `.app` 但 UI 只能开系统浏览器 tab，不是"desktop app"体感。

### D2 前端交付：Spring server 直接 serve dashboard 静态文件 ✔

新增 `SpaStaticConfig`（`skillforge-server/config/`，**不触碰核心文件清单**）：

- `@ConditionalOnProperty("skillforge.spa.root")` —— 属性缺省时 bean 不生效，**dev 模式行为零变化**
- ResourceHandler `/**` → `file:${skillforge.spa.root}/`，`PathResourceResolver` fallback 到 `index.html`（SPA 深链接 / `/login` 整页跳转）
- Controller（`/api/**`）与 WebSocket handler（`/ws/**`）映射优先级天然高于资源 handler，不需要排除规则

### D3 运行时：jlink 精简 JRE（arm64）

- 构建时从本机 JDK 17 `jlink` 出精简 runtime 打进 app（curated Spring Boot module list + `jdk.unsupported` 等）
- 冒烟测试兜底：若 jlink 缺模块导致启动失败，fallback 整包 JDK runtime（体积换稳定）

### D4 数据目录与单实例（2026-06-10 修订：改为独立数据库）

> **修订原因**：原方案复用 `~/.skillforge` + 15432（与 dev 共享），导致 App 与 dev server 不能同时跑——用户常开 dev server / 多 Claude 会话，反复撞"15432 被占"。用户拍板改为**独立数据库 + 首次启动复制 dev 数据**，彻底消除冲突。

- **独立 PG**：桌面 App 用**独立端口 15433** + **独立数据目录 `~/.skillforge-desktop/pgdata`**（pgrun/backups 同样在 `~/.skillforge-desktop` 下），与 dev 的 15432 + `~/.skillforge` 完全隔离 → **App 与 dev server 可同时跑，互不抢端口/锁**。
- **首次启动 seed**：App 首次启动若 `~/.skillforge-desktop/pgdata` 不存在但 `~/.skillforge/pgdata` 存在 → **一次性拷贝**当前 dev 数据过去（跳过 postmaster.pid/.opts/epg-lock，crash-consistent，首启 crash recovery）。之后两边**完全独立、不同步**。
- **实现**（2026-06-10 落地：用 **base-dir** 单一 property 控制 pgdata/pgrun/backups，比 data-dir 更简洁、顺带覆盖 backups 目录）：`EmbeddedPostgresConfig` 的 PG 端口 + 数据根目录改为**可配置 property**：
  - `skillforge.embedded-postgres.port`（默认 15432）
  - `skillforge.embedded-postgres.base-dir`（默认 `~/.skillforge`）→ `pgdata=<base>/pgdata`、`pgrun=<base>/pgrun`、`backups=<base>/backups`
  - 缺省值 = dev 现行为零变化；`PostgresBackupService` 同步尊重 base-dir。
  - seed 逻辑放 Java 侧（新 property `skillforge.embedded-postgres.seed-from`，data-dir 空 + 无 backup 恢复时从该路径一次性拷贝，复用 copyDir，跳过 postmaster.pid/.opts/epg-lock）。优先级：已初始化 > backup 恢复 > seed-from > 全新空库。
  - 桌面 launcher 传 `--skillforge.embedded-postgres.port=15433 --skillforge.embedded-postgres.base-dir=~/.skillforge-desktop --skillforge.embedded-postgres.seed-from=~/.skillforge/pgdata`；SKILLFORGE_HOME + system-skills 也隔离到 `~/.skillforge-desktop`。
- 单实例：Electron `requestSingleInstanceLock` + 启动前探测 **15433**（而非 15432）被占则弹错误对话框；退出 sweep 重定向到 `~/.skillforge-desktop/pgrun` 的 PG（信号 SIGINT→SIGQUIT→SIGKILL 分级）。
- HTTP 端口：Electron 动态找空闲端口传 `--server.port=N`。

### D5 生命周期

```
Electron ready → 找空闲端口 → spawn {bundled JRE}/bin/java -jar server.jar
    --server.port=N --skillforge.spa.root=<Resources/web>
  → 轮询 http://127.0.0.1:N/（200 + body 含 `<div id="root">`，超时 120s）→ BrowserWindow 加载
退出：SIGTERM java child → 等 ≤20s 优雅退出（embedded PG 释放 epg-lock）→ 超时 SIGKILL
```

- server stdout/stderr 落 `~/Library/Logs/SkillForge/server.log`
- 启动等待期显示 loading 窗口/提示

### D6 打包产物与目录

```
skillforge-desktop/            # 新 npm 模块（不进 maven reactor）
├── package.json               # electron + electron-builder + 构建脚本
├── main.js / preload.js       # Electron 主进程
├── scripts/build-dist.sh      # 编排：mvn package → dashboard build → jlink → electron-builder
└── build/                     # icon 等资源
产物：skillforge-desktop/dist/SkillForge-<ver>-arm64.dmg
.app 内部：Contents/Resources/{server/skillforge-server.jar, web/<dashboard dist>, jre/}
```

### D7 签名

无 Apple Developer 证书 → ad-hoc 签名（electron-builder 默认），用户首次打开需右键 Open / `xattr -dr com.apple.quarantine`。正式分发签名+公证留 follow-up。

## 验收点

1. `skillforge-desktop` 一条命令产出 `.dmg`，挂载可拖入 Applications
2. 双击启动 App → 自动拉起 server（自带 JRE + embedded PG）→ 窗口显示 dashboard 登录页
3. 退出 App → java/PG 子进程干净退出，无孤儿进程，epg-lock 释放
4. dev 工作流零回归（spa 属性缺省 = 现行为；`mvn test` 绿）
5. FE 代码零改动

## 冒烟用例（QA 门禁，部署后逐条取证）

> 前置：先优雅停掉本机 dev server（kill + 等 20s epg-lock 释放），跑完恢复。

| # | 触发 | 期望 raw 证据 | 早失败检测 |
|---|---|---|---|
| S1 | `cd skillforge-desktop && npm run dist` | exit 0，`dist/SkillForge-*-arm64.dmg` 存在且 >100MB | mvn/npm build 失败属环境，先修再跑 |
| S2 | 启动打包后的 `SkillForge.app/Contents/MacOS/SkillForge`，读 `~/Library/Logs/SkillForge/server.log` 拿端口 N | ≤120s 内 `curl -s http://127.0.0.1:N/` 返回 200 且 body 含 `<div id="root">` | 日志含 `could not lock epg-lock` = dev server 没停干净，非功能 bug |
| S3 | `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:N/api/agents` | `401`（API 活 + auth 生效） | — |
| S4 | `curl -s http://127.0.0.1:N/sessions/whatever` | 200 + index.html 内容（SPA fallback） | — |
| S5 | 杀 Electron 主进程（SIGTERM）后 `lsof -nP -iTCP:15432 -iTCP:N` + `pgrep -f skillforge-server.jar` | 全空（无孤儿） | — |
| S6 | `mvn -pl skillforge-server -am test` | `Failures: 0, Errors: 0` BUILD SUCCESS | pre-existing failure 对照 git stash 基线 |

## Scope 边界

- ✅ 改：新建 `skillforge-desktop/`；`skillforge-server` 新增 1 个 config 类 + application.yml 注释级说明；docs
- ❌ 不改：FE 任何代码、EmbeddedPostgresConfig、核心文件清单、签名/公证、自动更新（Sparkle/electron-updater 留 follow-up）、Windows/Linux 包

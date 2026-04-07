# BrowserSkill 登录态持久化设计

## 背景

当前 `BrowserSkill` 使用 Playwright 的 `chromium().launch()` + `browser.newPage()`，每次启动都是无痕浏览器实例，Cookie / localStorage / Session 全部不保留。这导致：

1. LLM 无法访问任何需要登录的站点（内部系统、GitHub、需账号的 SaaS 等）
2. 即使通过 `type` + `click` 自动填表单成功，下次启动又丢失
3. 验证码、短信、MFA 等人工验证步骤 LLM 无法完成

## 设计目标

1. **持久化登录态**：用户登录一次，后续所有调用自动保持登录
2. **人工登录通道**：支持遇到需人工交互的站点时，用户打开可见浏览器手动登录
3. **对 LLM 透明**：LLM 无需感知登录逻辑，像访问无状态页面一样调用 `goto`
4. **最小改动**：尽量复用现有 action 和 schema

## 方案总览（方案 A + B 组合）

- **方案 A：持久化用户数据目录** —— 将 `launch()` 换成 `launchPersistentContext(userDataDir, options)`，所有 Cookie/Storage 落盘到固定目录，跨进程保持
- **方案 B：首次登录走 headed 模式** —— 新增 `login` action，启动可见浏览器窗口让用户手动登录，登录完毕后持久化目录已写入登录态

## 核心变更

### 1. 持久化目录

```
./data/browser-profile/       # 默认 profile，所有站点共享
```

- 路径通过 `skillforge.browser.profile-dir` 可配置，默认 `./data/browser-profile`
- 服务启动时自动创建
- 目录内容由 Chromium 管理（Cookies、Local Storage、IndexedDB 等）

### 2. Playwright API 切换

**旧实现：**
```java
Browser browser = playwright.chromium().launch(options);
Page page = browser.newPage();
```

**新实现：**
```java
BrowserContext context = playwright.chromium().launchPersistentContext(
    Paths.get(profileDir),
    new BrowserType.LaunchPersistentContextOptions().setHeadless(headless)
);
Page page = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
```

注意：`launchPersistentContext` 直接返回 `BrowserContext`，没有独立的 `Browser` 对象。字段要从 `Browser browser` 改为 `BrowserContext context`。

### 3. 新增 login action

Tool schema 新增 action 枚举值 `login`，参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| url | string | 要登录的站点 URL（可选，默认跳转到首页） |
| timeoutSeconds | number | 等待用户完成登录的超时，默认 300（5 分钟） |

**行为：**
1. 关闭当前 headless context（释放 profile 目录锁）
2. 用 `headless=false` 重新 `launchPersistentContext`
3. `page.navigate(url)` 打开登录页
4. **阻塞等待**用户关闭浏览器窗口（监听 `context.onClose` 或 `page.onClose`），或超时
5. 用户手动完成登录 → 关闭窗口 → 登录态已保存到 profile 目录
6. 返回结果给 LLM：`"Login completed. Session persisted."`

后续普通 `goto` 调用会自动带上登录态（因为 profile 目录相同）。

### 4. headless 模式切换逻辑

由于 `launchPersistentContext` 对同一 profile 目录有**进程锁**，不能同时打开 headed 和 headless。切换步骤：

```
当前 context 存在 && 新请求的 headless 与 currentHeadless 不同:
    closeContextQuietly()
    ensureContext(newHeadless)
```

已在原代码 `ensureBrowser` 里有类似逻辑，改造成 `ensureContext`。

### 5. 并发与生命周期

- Skill 本身是**单例**（Spring bean），`context` / `page` 为实例字段
- 同一 JVM 内多会话并发调用会共享同一 context。当前实现未加锁，LLM 多并行调用可能导致 page 状态冲突 —— 这是已有问题，不在本次改动范围
- 服务关闭时通过 `@PreDestroy` 调用 `closeContextQuietly()`（本次顺带补上）

## 新旧 action 对照

| Action | 变更 |
|--------|------|
| `goto` | 无感变更，自动带登录态 |
| `getContent` | 无变更 |
| `screenshot` | 无变更 |
| `click` | 无变更 |
| `type` | 无变更 |
| `evaluate` | 无变更 |
| `close` | 关闭 context（不删除 profile 目录） |
| `login` | **新增**：headed 模式 + 等待用户手动登录 |

## 用户使用流程

### 场景 1：访问无需登录的站点

LLM 直接调用 `goto` —— 行为与旧版一致。

### 场景 2：首次访问需登录的站点（以 GitHub 为例）

1. 用户在对话中说"帮我看下我在 GitHub 上最近的 PR"
2. LLM 先 `goto https://github.com/pulls`，发现跳转到登录页
3. LLM 调用 `login { url: "https://github.com/login" }`
4. 服务端打开**可见浏览器窗口**，跳转到登录页
5. 用户在窗口里输账号密码（可处理 2FA / 验证码）
6. 用户登录成功后，**关闭浏览器窗口**
7. Skill 返回 `"Login completed"`
8. LLM 继续 `goto https://github.com/pulls` —— 这次带上了登录态

### 场景 3：后续再次访问

Profile 目录已有 Cookie，直接 `goto` 即可，无需再 login。除非 Cookie 过期，此时 `goto` 后会再次跳到登录页，LLM 识别后再次调用 `login`。

## 配置项

`application.yml` 新增：

```yaml
skillforge:
  browser:
    profile-dir: ./data/browser-profile
    default-timeout-ms: 30000
    login-timeout-seconds: 300
```

## 实现步骤

1. 改造 `BrowserSkill.java`
   - 字段：`Browser browser` → `BrowserContext context`
   - `ensureBrowser` → `ensureContext`，改用 `launchPersistentContext`
   - 新增 `handleLogin(input)` 方法
   - `getToolSchema` 的 action enum 加 `login`
   - schema 新增 `timeoutSeconds` 参数
2. 配置化 profile 目录
   - 新增 `BrowserProperties`，读 `skillforge.browser.*`
   - `SkillForgeConfig` 注入到 `BrowserSkill`
3. `@PreDestroy` 清理 context
4. 更新 README / Skill 文档片段
5. 手动验证
   - 无痕访问（baidu.com）
   - 登录流程（GitHub）
   - 登录后再次访问（GitHub PR 列表）
   - Cookie 过期重新登录

## 风险与权衡

| 风险 | 应对 |
|------|------|
| profile 目录损坏 | 允许手动删除目录重置（记录在 README） |
| 同 profile 不能并发 headed/headless | ensureContext 自动切换，代价是关闭重开 |
| 多用户场景 | 当前单用户不考虑。后续多用户时 profile-dir 按 userId 隔离：`./data/browser-profile/{userId}/` |
| 敏感数据落盘 | profile 目录需加入 .gitignore |
| login 阻塞时间长 | 设置 5 分钟默认超时，避免永久挂起 |
| Playwright 版本依赖 | 当前依赖已存在，本次无升级 |

## 不在本次范围

- 多用户 profile 隔离
- Skill 并发锁
- 浏览器截图自动附加到对话（目前只返回文件路径）
- Cookie 导入导出 / 手动编辑
- 多 tab 页管理

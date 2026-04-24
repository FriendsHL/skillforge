# Design: ClawHub / SkillHub Install 用户授权流程

> **状态**:Phase 1 对抗循环通过(r1→r4,详见文末"审查记录"附录)。待 Phase 2 Dev 开工。
> **最后更新**:2026-04-24

## 设计决策原点(从 brief 摘)

**背景**:`SafetySkillHook.checkBashSafety` 在 Bash skill 执行前扫描命令,命中 `DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS`(clawhub install / skillhub install / skill-hub/cli install)后返回 null 拒绝执行,导致 Agent 无法装 skill。诉求是**拦截后把命令推给用户,用户显式授权才放行**。

**已拍板的设计决策**:
1. **授权记忆范围**:会话级 + **白名单继承**(方案 C)。Cache key = `(rootSessionId, toolName, installTarget)`,SubAgent / TeamCreate 成员通过 `parentSessionId` 递归共享根节点授权,但**相同 tool 且相同 target** 才继承;换 target 重新确认。
2. **授权 UI**:
   - **Dashboard**:chat 消息流 inline 卡片(授权 / 拒绝 按钮),复用 `waiting_user` 状态机
   - **飞书 channel**:Feishu Interactive Card + action button callback
3. **必须抽共用层**:`PendingConfirmation` 实体 + `ConfirmationPrompter` SPI,channel adapter 实现
4. **多人群聊鉴权**(飞书):只有触发 agent 的飞书 user_id 点按钮才算数

**注**:正文小节里的 `r1 §n` / `B1 修复` / `r3 新` 等标注是对抗循环的决策 trail,保留以便追溯"为什么这么设计";不影响实现阅读。

---
>
> Round 3(保留 r3 原序言以便回溯)。**增量**:把"白名单继承(方案 C)"补进 plan,覆盖 SubAgent / TeamCreate 场景。
> r2 PASS 的章节(§1 全部 / §3 prompter 主体 / §4.2-§4.5 engine 主线程分支 / §5 状态机 / §6.2 strict verifier / §0.B4 startup recovery / §7 migration / §11 安全自查)**原样保留**,本轮仅改方案 C 真正涉及的节:
> - §0 #5(key 粒度重定义)
> - §2(SessionConfirmCache 字段扩 + 新 InstallTargetParser)
> - §3(prompter 增 `resolveRootSessionId`)
> - §4.1(SafetySkillHook cache 短路用 root + target)
> - §6.1(飞书卡片增 Target 行)
> - §8(前端卡片增 target 字段 + 说明文案)
> - §9(测试新增 5 类:解析器 / root cache / SubAgent 继承 / 团队继承 / target 不匹配仍弹)
> - §10(Backend +2 子任务,Frontend +1)
> - **新附录 r3 vs r2 变更一览**

---

## 0. 决策总览（重写 r1 中受 blocker 影响的条目）

| # | 问题 | r2 决策 | 替换的是 r1 哪一条 |
|---|------|---------|-------------------|
| 1 | `PendingConfirmation` 是否落库？ | **不落库 + 启动恢复契约**(§9 单测锁死)。崩溃恢复:启动器扫 `runtimeStatus ∈ {running, waiting_user}` 的 session,识别 last-assistant message 中孤儿 `tool_use`,**补一条 fabricated `tool_result(isError=true, content="Install confirmation aborted due to server restart")`** 写入 SessionMessage 表,**再**置 `runtimeStatus="error"`。**此动作必须在允许 ChatService 接受该 session 任意 chatAsync 之前完成**(由 `@Order` 保证,详见 §0.B4) | r1 §0#1 增补 |
| 2 | Session 状态机:复用 `waiting_user` vs 新加? | **复用 `waiting_user`**;runtimeStep="waiting_confirmation";额外要求 finally 里**不无条件**回 "running"(W2/B3 联动,详见 §3 + §5) | r1 §0#2 不变,语义补强 |
| 3 | 拦截时 tool_use 怎么处理? | **engine 主线程同级新增"install confirmation"分支**(与 `handleAskUser` / `handleCompactContext` 同级),**不**在 SkillHook 内阻塞,**不**进 `supplyAsync` 路径,因此天然规避 `allOf(futures).get(120, SECONDS)` 超时;APPROVED 后 engine 主线程同步调 `executeToolCall(block, ...)` 跑 Bash → 1 个 toolUseId 始终对应 1 条 toolResult。详见 §1/§4 完整重写 | r1 §0#3 整段替换 |
| 4 | 飞书 card_action endpoint / 签名 | endpoint 仍 `POST /api/channels/feishu/card-action`;**不**复用 `FeishuWebhookVerifier` 的 "encryptKey 空则放行" 旁路。新建 `FeishuCardActionVerifier`(strict 模式),encryptKey 缺失 → boot-time fail / 请求级 401。**且** prompter 选 channel 阶段对 feishu 二次预检:`config.encryptKey` 缺失则不把 feishu 当可用通道,fall-back HookRejected("Confirmation channel unavailable: encryptKey not configured")。card_action 实际签名格式按飞书"互动卡片回传"官方文档(详见 §6.2.签名规格) | r1 §0#4 替换 |
| 5 | "会话级记忆" key 粒度 | **per-`(rootSessionId, toolName, installTarget)`(白名单继承,方案 C)**。`rootSessionId` 由 `SessionEntity.parentSessionId` 链向上追溯到根(递归深度硬限 10);`toolName ∈ {clawhub, skill-hub, skillhub}`;`installTarget` 是从命令解析出的 skill 名(如 `npx clawhub install obsidian` → `obsidian`);**只有相同 root + 相同 tool + 相同 target** 才命中 cache 走放行,任意维度不同(换 target / 换 tool / 跨 root)都重新弹卡。**安全边界论证**:覆盖 SubAgentSkill / TeamCreateSkill 派生子 session 与 collab member session(它们都通过 `setParentSessionId(parentSessionId)` 链回 root);prompt injection 即使诱使子 agent 派子任务,只要它换 install 目标(攻击 payload 必然是 attacker 想装的 *新* skill,target 必然不同)立即重新弹卡,用户能在卡片上看清"目标 = `<恶意 target>`"再做决定。**r4 追加**:目标解析拒绝以 `-` 开头的 token(把 `--force` / `-y` 之类的 flag 当作 target 是 cache key 跨包共享的后门),防止 flag 前缀污染导致不同 skill 共用同一 cache entry,详见 §2.B 归一化规则与 §9.1.r3 `InstallTargetParserTest` 新增用例 | r2 §0#5 修订 |
| 6 | 共用 DTO | `ConfirmationPromptPayload`(不变) | r1 §0#6 保留 |
| **7 新增** | 同 turn 并行 `ask_user` + `Bash(install)` 怎么互斥 | engine 新分支**入口预检**:若 `PendingAskRegistry.hasPendingForSession(sid) == true`(需在 PendingAskRegistry 新增此 API),**直接返回 error tool_result**(`"Install confirmation cannot start while ask_user is pending; LLM should re-emit after the ask is answered"`,isError=true),**不**注册 PendingConfirmation,**不**广播 waiting_user。tool_use ↔ tool_result 配对仍守恒。详见 §3/§5 | r1 §8.2 口头互斥升级到代码级 |

### §0.B4 启动恢复契约(B4 修复)

**位置**:新建 `skillforge-server/src/main/java/com/skillforge/server/init/PendingConfirmationStartupRecovery.java`,`@Component @Order(50)`,`implements ApplicationRunner`(早于 `SubAgentStartupRecovery@Order(100)`,因为子 session 恢复可能 chatAsync 启动 loop)。同时**改造**该 init 顺序:把任何"接受用户消息 / 重启 chatLoop"的入口 init 都放在 `@Order(>=100)`。

**为什么 ApplicationRunner @Order(50) 足够安全**:Spring Boot 启动顺序是 `ContextRefreshed → ApplicationRunner.run() (按 @Order 升序) → SpringApplication.run() 返回 → 报告 "Started in X seconds"`。在 Tomcat embedded 容器场景,`ServletWebServerApplicationContext` 在 ContextRefreshed 时启动 web server **但** Spring Boot 标准做法是"web server 启动 ≠ 接受业务 HTTP 请求",DispatcherServlet 上线在 onRefresh 内部完成。**风险点**:web server 监听端口理论上可在 ApplicationRunner 跑之前就接受 TCP 连接。**保险方案**:不依赖 ApplicationRunner @Order,改用 `ApplicationListener<ApplicationReadyEvent>` 不行(ReadyEvent 在所有 Runner 之后,反而更晚)。**采纳**:`PendingConfirmationStartupRecovery implements SmartLifecycle` + `getPhase() = Integer.MIN_VALUE + 100`(在 `WebServerStartStopLifecycle` 启动前的 SmartLifecycle 阶段执行,实测可阻塞 web server 真正接受请求)。Dev 阶段允许从 ApplicationRunner@Order(50) 起步,**实测验证 web server 在 ApplicationRunner 跑完前不响应 `/api/chat/*` 请求(curl + 启动时延打点)**;若实测看到 race,立即升级为 SmartLifecycle。Plan 锁死的是"在用户能 chatAsync 之前必须完成恢复"这条契约,实现路径允许 dev 二选一。

恢复算法:

```
对每个 session WHERE runtimeStatus IN ('running','waiting_user'):
   msgs = sessionMessageRepository.loadAll(sessionId)
   orphanIds = collectOrphanToolUseIds(msgs)        // tool_use 没匹配 tool_result 的
   if orphanIds.empty:
        // 无孤儿,只标 error
        session.runtimeStatus = 'error'
        session.runtimeError  = 'Server restarted while session was active'
   else:
        for id in orphanIds:                         // 每个孤儿补一条 fabricated tool_result
            sessionService.appendNormalMessages(sessionId,
                List.of(Message.toolResult(id,
                    "Install confirmation aborted due to server restart", true)))
        session.runtimeStatus = 'error'
        session.runtimeError  = 'Recovered from restart: ' + orphanIds.size() + ' orphan tool_use(s) repaired'
   sessionService.saveSession(session)
```

`collectOrphanToolUseIds`:遍历 messages 收集所有 tool_use id,扣除已有 tool_result 引用过的 id,剩余即孤儿。

> 这条恢复策略对 **所有 tool**(不只 install)生效,顺手治根 ChatService init hook 早就该有的清理。Plan §10 dev 任务把它单列为 P0。

---

## 1. 架构图(重写,主线程分支模式)

```
[chatLoopExecutor 线程,即 engine 主 loop 线程]                      [Tomcat HTTP 线程 / Feishu WS 线程]   [用户]
─────────────────────────────────────────────                       ─────────────────────────────────     ─────

(a) AgentLoopEngine.run() — line 722 后的 tool dispatch 循环
    └─ for ToolUseBlock block : toolUseBlocks:
        ├─ if (AskUserTool.NAME.equals(block.getName())) { handleAskUser(...) }       ← 现有,主线程
        ├─ else if (ContextCompactTool.NAME.equals(block.getName())) { handleCompactContext(...) }  ← 现有,主线程
        ├─ else if (isInstallRequiringConfirmation(block, loopCtx, sessionConfirmCache)) {
        │       Message result = handleInstallConfirmation(block, loopCtx);   ← **新增 §4.2**,主线程,不入 supplyAsync
        │       askResults.put(i, result);
        │   }
        └─ else { /* 现有 supplyAsync(executeToolCall(...)) → futures.add(...) */ }

(b) handleInstallConfirmation(block, loopCtx)  — 主线程同步执行:

    [b.1] 入口预检
          ├─ if (pendingAskRegistry.hasPendingForSession(sid)) {            ← B3 fix
          │     return Message.toolResult(block.id,
          │              "Install confirmation cannot start while ask_user is pending; LLM should re-emit after the ask is answered",
          │              true);
          │   }
          ├─ installTool = extractInstallTool(command, matchedPattern)
          └─ if (sessionConfirmCache.isApproved(sid, installTool)) {        ← 已授权直接放行
                return executeToolCall(block, loopCtx, toolCallRecords);    ← 主线程同步跑 Bash
            }

    [b.2] 选 channel & 飞书预检
          channel = resolveChannel(sid)                                     ← web / feishu / none
          if (channel == feishu && config.encryptKey blank) {               ← B2 fix: 不当作可用通道
              return Message.toolResult(block.id,
                       "Confirmation channel unavailable: feishu encryptKey not configured", true);
          }
          if (channel == none) {
              return Message.toolResult(block.id, "No confirmation channel available", true);
          }

    [b.3] 注册 + 推送
          pc = pendingConfirmationRegistry.register(buildPC(sid, installTool, command,
                       triggererOpenId /* 飞书才有 */, expiresIn /* 配置:30 min 默认,可被 yaml 覆盖 */));
          payload = buildPayload(pc);
          try {
              switch (channel) {
                  case web:
                      broadcaster.confirmationRequired(sid, payload);
                  case feishu:
                      DeliveryResult dr = feishuClient.sendInteractiveAction(chatId, payload, config);
                      if (dr.failed()) throw new IllegalStateException("send card failed: "+dr.reason());
              }
              broadcaster.sessionStatus(sid, "waiting_user", "waiting_confirmation", null);

              // [b.4] 主线程阻塞等待 latch (无 120s 上限,因为不在 supplyAsync 里)
              Decision d = pendingConfirmationRegistry.await(pc.confirmationId(), pc.timeoutSeconds());
              if (d == null) d = Decision.TIMEOUT;

              // [b.5] 决策处理
              if (d == APPROVED) {
                  sessionConfirmCache.approve(sid, installTool);
                  // sessionStatus 转回 running 在 [b.6] finally 统一处理
                  return executeToolCall(block, loopCtx, toolCallRecords);   ← 主线程同步跑 Bash → 真正 install
              } else if (d == DENIED) {
                  return Message.toolResult(block.id,
                           "User denied install of "+installTool+": "+truncate(command,120), true);
              } else /* TIMEOUT */ {
                  return Message.toolResult(block.id,
                           "Install confirmation timed out for "+installTool, true);
              }
          } catch (Exception e) {
              log.error("Install confirmation flow error", e);
              return Message.toolResult(block.id,
                       "Install confirmation failed: "+e.getMessage(), true);
          } finally {
              // [b.6] 清理 + status 回归(W2 修)
              pendingConfirmationRegistry.removeIfPresent(pc.confirmationId());
              if (!pendingAskRegistry.hasPendingForSession(sid)) {            // 只有当无其它 latch 时才回 running
                  broadcaster.sessionStatus(sid, "running", null, null);
              }
          }

(c) 用户行为                                                                [Tomcat / Feishu WS 线程]
    ├─ Web 用户点 Approve/Deny
    │   POST /api/chat/{sid}/confirmation { confirmationId, decision }
    │   ChatController.confirm() → requireOwnedSession → registry.complete(...) → 200
    └─ 飞书用户点卡片按钮
        POST /api/channels/feishu/card-action
        ChannelCardActionController.feishu()
            ├─ feishuCardActionVerifier.verifyStrict(ctx, config)             ← B2 fix: encryptKey 缺失抛异常
            │   (官方互动卡片回传:header X-Lark-Signature; 算法 SHA-256(timestamp+nonce+encryptKey+body),
            │    与 webhook 签名同源,**但** strict 模式必须 require encryptKey)
            ├─ parse: { open_id, action.value: { confirmationId, decision } }
            ├─ pc = registry.peek(confirmationId)
            ├─ if pc == null            → 200 + toast("授权请求已失效或已被处理")
            ├─ if pc.triggererOpenId != open_id  → 200 + toast("仅请求者可授权,你无权操作") + log.warn(unauthorized)
            └─ registry.complete(confirmationId, decision, open_id) → 200 + toast("✅ 已批准" / "❌ 已拒绝")

    Latch countDown → (b.4) 主线程恢复 → (b.5) → (b.6)。返回的 Message 写入 askResults[i] → engine line 850-862
    按原顺序统一 messages.add + broadcast。tool_use ↔ tool_result 始终 1:1 配对。
```

**为什么不再受 120s 限制**:engine 在第 728-833 行的循环里,只有 `else` 分支才会 `futures.add(supplyAsync(...))`。`isInstallRequiringConfirmation` 命中的 block **不进 futures**,因此第 837 行 `allOf(futures).get(120, SECONDS)` 等待集合里**根本没有这个 future**。主线程 `handleInstallConfirmation` 想等多久等多久(配置上限 30 分钟,与 `askUserTimeoutSeconds` 同级)。

**为什么不会"漏 tool_result"**:每条 install 的 toolUseBlock 一次 `askResults.put(i, result)`,line 851-862 的循环在 messages.add 时按下标取 `askResults.get(i)`,得到的就是主线程同步生成的 Message。tool_use ↔ tool_result 守恒。

**失败矩阵补强**

| 步骤 | 失败 | 处理 |
|------|------|------|
| (b.1) ask_user 已 pending | LLM 同 turn 并行 emit | 直接 error tool_result,LLM 下一轮自决 |
| (b.2) feishu encryptKey 未配 | 配置漏 | 不入 feishu channel,error tool_result(明示 reason) |
| (b.3) 飞书 sendInteractive 失败 | 网络 / 卡片 JSON | catch 后 error tool_result;**registry remove 在 finally** |
| (b.4) 等待 cancel | 用户 /cancel | `CancellationRegistry.cancel(sid)` 多调一步 `pendingConfirmationRegistry.completeAllForSession(sid, DENIED)` 唤醒 latch |
| (b.4) 超时 30min | 用户没看到 / 不响应 | TIMEOUT 分支,error tool_result;status 回 running(若无其它 latch) |
| (b.5) APPROVED 后 Bash 真失败 | install script 报错 | 走现有 SkillResult.error 路径,正常 error tool_result(executeToolCall 已处理) |
| 进程崩溃 | restart | §0.B4 启动恢复:补 fabricated error tool_result + 标 error |

---

## 2. Entity / DTO 定义(**保留 r2 §2 全部,新增 InstallTargetParser + SessionConfirmCache 字段扩**)

| 项 | 与 r2 差异 |
|----|-----------|
| `PendingConfirmation` (in-memory record) | **r3 增字段** `String installTarget`;其余同 r2 §2.1 |
| `PendingConfirmationRegistry` | 不变(同 r2) |
| `PendingAskRegistry` (现有) | 不变(同 r2,additive 加 sessionId 字段 + `hasPendingForSession`) |
| `SessionConfirmCache` | **r3 重写**:key 由单 `sessionId` 升为复合 `(rootSessionId, toolName, installTarget)`,详见 §2.A |
| **`InstallTargetParser`(r3 新)** | 工具类,从命令解析 `(toolName, installTarget)` 二元组,详见 §2.B |
| `ConfirmationPromptPayload` | **r3 增字段** `String installTarget`(用于前端卡片 / 飞书卡片显示授权范围) |
| WebSocket event `confirmation_required` | payload 自动带 installTarget(透传 ConfirmationPromptPayload) |
| REST endpoints | 不变(同 r2 §2.6) |
| 前端 TS 类型 | **r3 增字段** `installTarget: string` 在 `ConfirmationPromptPayload` |

### §2.A `SessionConfirmCache` 重写(白名单继承支持)

```java
// skillforge-core/src/main/java/com/skillforge/core/engine/confirm/SessionConfirmCache.java
public class SessionConfirmCache {

    // key = rootSessionId; value = set of "<toolName>::<installTarget>" 复合键
    // 用 ":: " 分隔避免 toolName / target 自身有冲突字符(install pattern 名空间已固定 3 个,target 是 skill 名)
    private final ConcurrentMap<String, Set<String>> approved = new ConcurrentHashMap<>();

    public boolean isApproved(String rootSessionId, String toolName, String installTarget) {
        if (rootSessionId == null || toolName == null || installTarget == null) return false;
        if ("*".equals(installTarget)) return false;          // 解析失败的保守 target 不允许命中,见 §2.B
        Set<String> set = approved.get(rootSessionId);
        return set != null && set.contains(compose(toolName, installTarget));
    }

    public void approve(String rootSessionId, String toolName, String installTarget) {
        if (rootSessionId == null || toolName == null || installTarget == null) return;
        if ("*".equals(installTarget)) return;                 // 不缓存 unparseable
        approved.computeIfAbsent(rootSessionId, k -> ConcurrentHashMap.newKeySet())
                .add(compose(toolName, installTarget));
    }

    public void clear(String rootSessionId) { approved.remove(rootSessionId); }

    private static String compose(String t, String tg) { return t + "::" + tg; }
}
```

> **clear 时机**:loop finally 调 `cache.clear(rootSessionId)`,但 root session 通常长寿;dev 阶段需在 `ChatService.runLoop` finally 评估 — **若当前 sessionId == rootSessionId(即真正的根 session)** 才 clear,否则只清自身缓存毫无意义(子 session 结束不应擦掉根 session 的授权)。子 session 不写 cache(只读 root),所以子 session 结束**不**触发 clear。

### §2.B `InstallTargetParser`(r3 新)

```java
// skillforge-core/src/main/java/com/skillforge/core/engine/confirm/InstallTargetParser.java
public final class InstallTargetParser {

    public record Parsed(String toolName, String installTarget) {}

    // 与 DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS 顺序对齐,带 capture group
    // 抽出 install target(允许 npm 包名风格的字符:字母/数字/-/_/@/)
    private static final Pattern CLAWHUB   = Pattern.compile("\\bclawhub\\s+install\\s+(@?[A-Za-z0-9._/\\-]+)");
    private static final Pattern SKILLHUB  = Pattern.compile("\\bskillhub\\s+install\\s+(@?[A-Za-z0-9._/\\-]+)");
    private static final Pattern SKILLHUB2 = Pattern.compile("\\bskill-hub/cli\\s+install\\s+(@?[A-Za-z0-9._/\\-]+)");

    /**
     * 解析出 (toolName, installTarget)。
     * 三个 pattern 任一命中提取 group(1) 作为 target;均未命中(理论上不应到此,只在 caller 已确认是 install
     * 命令时调用) → 返回 ("unknown","*")。
     * **target == "*" 是保守 fallback**,SessionConfirmCache 会拒绝缓存与命中,迫使每次都弹卡确认。
     */
    public static Parsed parse(String command) {
        if (command == null) return new Parsed("unknown", "*");
        // r3 安全加固:统计三种 install pattern 在命令里出现的总次数;
        // 若 > 1(例如 `clawhub install a && clawhub install b` 或跨 tool 混装),
        // 返回 target = "*" 强制每次弹卡,避免"用户只授权了 a,攻击者把 b 粘在 && 后"的绕过。
        int installCount = countMatches(CLAWHUB, command) + countMatches(SKILLHUB, command) + countMatches(SKILLHUB2, command);
        if (installCount != 1) {
            return new Parsed("multiple", "*");   // caller 据 target==* 走 fail-closed-ish(不命中 cache,每次弹卡)
        }
        Matcher m;
        if ((m = CLAWHUB.matcher(command)).find())   return normalize("clawhub",    m.group(1));
        if ((m = SKILLHUB2.matcher(command)).find()) return normalize("skill-hub",  m.group(1));
        if ((m = SKILLHUB.matcher(command)).find())  return normalize("skillhub",   m.group(1));
        return new Parsed("unknown", "*");
    }

    /**
     * r4 安全归一化:拒绝把 flag 当 target,避免"cache key = --force"跨包共享。
     * 例:  `clawhub install --force obsidian` 捕获到的 group(1) 是 "--force",
     *      若直接入 cache,攻击者后续 `clawhub install --force MALICIOUS_PKG` 也会命中。
     * 策略:target 以 "-" 开头 或 已是兜底 "*" 时,强制降级为 "*",让 caller 走"每次弹卡"路径。
     */
    private static Parsed normalize(String toolName, String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()
                || rawTarget.startsWith("-")            // flag 前缀(--force / -y / -g ...)
                || "*".equals(rawTarget)) {
            return new Parsed(toolName, "*");
        }
        return new Parsed(toolName, rawTarget);
    }

    private static int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    private InstallTargetParser() {}
}
```

> **为什么 `skill-hub/cli` 优先于 `skillhub`**:`skillhub` 子串可能误匹配 `skill-hub/cli install xxx`(后者也包含 `hub`?不会,`skill-hub` 中间有 `-`,`skillhub` 无;但顺序仍按命中优先,把更具体的 skill-hub 放前面更稳)。
>
> **target 解析失败 vs install pattern 失败**:caller(SafetySkillHook + engine handleInstallConfirmation)只在 `DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS` 命中后才调 parser,所以 toolName == "unknown" 实际不会发生(三类都覆盖到了);兜底是 defense in depth。target == "*" 出现在"`clawhub install` 后没跟参数 / 跟了奇怪字符 / 同命令内出现多条 install 连接 / **target token 以 `-` 开头(flag 前缀,r4 新增归一化)**"等异常输入,fallback 行为是**永不命中 cache、永不写入 cache**,等同于"每次都弹卡",最保守。

**为什么不直接 reject 多 install 命令**:LLM 偶尔会合成 `clawhub install a && clawhub install b`(合法意图场景);保守做法是让用户看到卡片明确 target(卡片 title 可标注 "multiple installs in one command"),用户判断;plan 不试图阻断,只确保**每条都过用户的眼**。前端 `InstallConfirmationCard` 在 target == "*" 且 installTool == "multiple" 时额外显示 warning banner "Command contains multiple installs — approve only if you trust all of them"。后端 `ConfirmationPromptPayload.description` 由 prompter 构造时根据 (tool, target) 组合拼文案。

---

## 3. SPI 接口签名(重写,主线程模型)

```java
// skillforge-core/src/main/java/com/skillforge/core/engine/confirm/ConfirmationPrompter.java
public interface ConfirmationPrompter {

    /**
     * 主线程同步调用。推送 prompt 给用户,阻塞等 Decision。
     * 调用方:仅 AgentLoopEngine.handleInstallConfirmation,不允许在 SkillHook 内调用。
     *
     * @return APPROVED / DENIED / TIMEOUT(永远不返回 null)。
     *         如推送渠道初始化失败(无 channel / encryptKey 缺) → 抛 ChannelUnavailableException(子类)
     *         caller catch 后转化为 error tool_result(详见 §1 (b.2)/(b.3))。
     */
    Decision prompt(ConfirmationRequest request);

    record ConfirmationRequest(
            String sessionId,
            Long userId,
            String installTool,
            String installTarget,            // r3:必填,展示给用户 + 写入 cache key
            String command,
            String triggererOpenId,
            long timeoutSeconds
    ) {}
}

public class ChannelUnavailableException extends RuntimeException { ... }
```

**关键差异 vs r1**:
- 不再让 SkillHook 调 prompt → SafetySkillHook 退化为 "防御性 fail-closed"(§4.1 重写)
- prompter 内部 await 是主线程,因此**不存在** chatLoopExecutor 线程被占满的问题(同一 session 的主 loop 本来就排队执行,与 ask_user 阻塞同性质)
- prompter 的 finally 只负责 registry remove,**不**做 sessionStatus("running")(由 engine 的 handleInstallConfirmation 在 finally 统一回拨,且**带 ask pending 互斥检查**)

### §3.A `resolveRootSessionId(...)`(r3 新,白名单继承的根识别)

**模块边界设计**:`SafetySkillHook` / `AgentLoopEngine` 在 **core** 模块,不能依赖 server 的 `SessionService`。因此引入 core 层小接口:

```java
// skillforge-core/src/main/java/com/skillforge/core/engine/confirm/RootSessionLookup.java
@FunctionalInterface
public interface RootSessionLookup {
    /** 返回根 sessionId;输入 null 返回 null;任意失败返回输入自身(保守 fall-back)。深度硬限 10,实现方保证。 */
    String resolveRoot(String sessionId);
}
```

`SafetySkillHook` / `AgentLoopEngine` 持 `RootSessionLookup` 字段,构造器注入;server 层 `RootSessionResolver implements RootSessionLookup` 由 Spring wire;core 的单元测试 mock `RootSessionLookup` 即可。**实现如下**:

```java
// skillforge-server/src/main/java/com/skillforge/server/engine/RootSessionResolver.java
@Component
public class RootSessionResolver implements RootSessionLookup {

    private static final int MAX_DEPTH = 10;
    private static final Logger log = LoggerFactory.getLogger(RootSessionResolver.class);

    private final SessionService sessionService;
    public RootSessionResolver(SessionService sessionService) { this.sessionService = sessionService; }

    /**
     * 沿 SessionEntity.parentSessionId 链上溯到根。深度硬限 10,防意外循环。
     * 任意节点找不到 → 返回当前 sessionId 作 root(保守:本地化授权,不污染其它 session)。
     */
    @Override
    public String resolveRoot(String sessionId) {
        if (sessionId == null) return null;
        String cur = sessionId;
        for (int i = 0; i < MAX_DEPTH; i++) {
            SessionEntity s;
            try {
                s = sessionService.getSession(cur);
            } catch (RuntimeException ex) {
                log.warn("RootSessionResolver: getSession({}) failed at depth {}, treating as root: {}",
                        cur, i, ex.getMessage());
                return cur;
            }
            String parent = s.getParentSessionId();
            if (parent == null || parent.isBlank()) return cur;
            if (parent.equals(cur)) {                          // 自环防御
                log.error("RootSessionResolver: parent==self loop at sid={}, treat as root", cur);
                return cur;
            }
            cur = parent;
        }
        log.warn("RootSessionResolver: depth >{} reached at sid={}, treating as root (data anomaly)", MAX_DEPTH, cur);
        return cur;   // 触顶仍 fall-back 到当前节点作 root,保证不空、不死循环
    }
}
```

> **缓存 root 的微优化**:可在 `LoopContext` 上加 `transient String rootSessionIdCache`,engine 一次解析后复用,避免每次 install 都查 DB 链。Dev 可选。

---

## 4. `SafetySkillHook` 改造(重写,B1 联动 — hook 不再阻塞)

### 4.1 新职责:仅做"防御 + cache 短路"

```java
private Map<String, Object> checkBashSafety(Map<String, Object> input, SkillContext ctx) {
    Object commandObj = input.get("command");
    if (commandObj == null) return input;
    String command = commandObj.toString();

    for (Pattern pattern : CONFIRMATION_REQUIRED_PATTERNS) {
        if (pattern.matcher(command).find()) {
            // r3:用 (rootSessionId, toolName, installTarget) 查 cache
            InstallTargetParser.Parsed parsed = InstallTargetParser.parse(command);
            String rootSid = (rootSessionLookup != null && ctx.getSessionId() != null)
                    ? rootSessionLookup.resolveRoot(ctx.getSessionId())
                    : ctx.getSessionId();
            // 主路径:engine 已在 dispatch 分支预先 prompt + cache.approve(rootSid, tool, target) 然后调 runInstallSyncWithBroadcast → executeToolCall.
            // SafetySkillHook 在那之后才被 executeSkill 调到,看到 cache 命中 → 放行。
            if (sessionConfirmCache != null && rootSid != null
                    && sessionConfirmCache.isApproved(rootSid, parsed.toolName(), parsed.installTarget())) {
                return input;
            }
            // 防御:engine 没走 install confirmation 分支但命令到了这里 — 不应发生,fail-closed
            log.error("[SafetyHook] install pattern reached SafetyHook without engine gate; rejecting fail-closed: rootSid={} tool={} target={} cmd={}",
                      rootSid, parsed.toolName(), parsed.installTarget(), truncate(command, 120));
            return null;
        }
    }
    for (Pattern pattern : DANGEROUS_PATTERNS) {
        if (pattern.matcher(command).find()) {
            log.warn("[SafetyHook] Blocked dangerous command: {}", command);
            return null;
        }
    }
    return input;
}
```

### 4.2 install confirmation 分支由 **engine** 持有(B1 fix 核心)

新方法 `private Message handleInstallConfirmation(ToolUseBlock block, LoopContext loopCtx)` 放在 `AgentLoopEngine` 中,完整流程见 §1 (b)。关键代码片段:

```java
private Message handleInstallConfirmation(ToolUseBlock block, LoopContext loopCtx) {
    String sid = loopCtx.getSessionId();
    String toolUseId = block.getId();
    Map<String,Object> input = block.getInput() != null ? block.getInput() : Collections.emptyMap();
    String command = String.valueOf(input.getOrDefault("command",""));

    // (b.1) 互斥
    if (pendingAskRegistry != null && pendingAskRegistry.hasPendingForSession(sid)) {
        return Message.toolResult(toolUseId,
                "Install confirmation cannot start while ask_user is pending; LLM should re-emit after the ask is answered", true);
    }

    InstallTargetParser.Parsed parsed = InstallTargetParser.parse(command);
    String installTool = parsed.toolName();
    String installTarget = parsed.installTarget();

    // r3:解析 root session(白名单继承点),所有 cache 操作都用 root
    String rootSid = rootSessionLookup.resolveRoot(sid);

    // 已授权直接执行(主线程同步;同 turn 其它 tool 仍在 supplyAsync 路径并行,不受影响)
    if (sessionConfirmCache.isApproved(rootSid, installTool, installTarget)) {
        return runInstallSyncWithBroadcast(block, loopCtx);
    }

    // (b.2 ~ b.6) prompter 同步阻塞
    try {
        Decision d = confirmationPrompter.prompt(
                new ConfirmationRequest(sid, loopCtx.getUserId(), installTool, installTarget, command,
                        /* triggererOpenId 由 prompter 内部解析 */ null,
                        installConfirmTimeoutSeconds));
        if (d == Decision.APPROVED) {
            sessionConfirmCache.approve(rootSid, installTool, installTarget);    // r3:用 root key
            return runInstallSyncWithBroadcast(block, loopCtx);   // 主线程同步,正本唯一
        }
        return Message.toolResult(toolUseId,
                d == Decision.DENIED
                        ? "User denied install of " + installTool + " target=" + installTarget + ": " + truncate(command, 120)
                        : "Install confirmation timed out for " + installTool + " target=" + installTarget, true);
    } catch (ChannelUnavailableException ce) {
        return Message.toolResult(toolUseId, ce.getMessage(), true);
    } catch (Exception ex) {
        log.error("Install confirmation flow error sid={}", sid, ex);
        return Message.toolResult(toolUseId, "Install confirmation failed: " + ex.getMessage(), true);
    } finally {
        if (!pendingAskRegistry.hasPendingForSession(sid)
                && broadcaster != null) {
            broadcaster.sessionStatus(sid, "running", null, null);   // W2 修:带条件回拨
        }
    }
}

private boolean isInstallRequiringConfirmation(ToolUseBlock block,
                                               LoopContext loopCtx,
                                               SessionConfirmCache cache) {
    if (!"Bash".equals(block.getName())) return false;
    Map<String,Object> input = block.getInput();
    if (input == null) return false;
    Object cmd = input.get("command");
    if (cmd == null) return false;
    Pattern matched = matchInstallPattern(cmd.toString());
    if (matched == null) return false;
    // cache 命中也走该分支 — 在 handler 内会直接走 executeToolCall, 但走主线程而非 supplyAsync,
    // 与未授权场景路径一致, 减少分支错配概率
    return true;
}

private Pattern matchInstallPattern(String command) {
    for (Pattern p : DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS) {
        if (p.matcher(command).find()) return p;
    }
    return null;
}
```

`extractInstallTool` 实现同 r1 §4.2(三 pattern → 三 tool 名静态映射,启动期 size 断言)。

**`runInstallSyncWithBroadcast` 实现**(B1 联动:补 toolStarted/toolFinished 广播,与现有 supplyAsync 分支等价 UX):

```java
private Message runInstallSyncWithBroadcast(ToolUseBlock block, LoopContext loopCtx) {
    String sid = loopCtx.getSessionId();
    long start = System.currentTimeMillis();
    if (broadcaster != null && sid != null) {
        broadcaster.toolStarted(sid, block.getId(), block.getName(), block.getInput());
    }
    Message r = null;
    String status = "success";
    String errorMsg = null;
    try {
        r = executeToolCall(block, loopCtx, toolCallRecords);
        if (r != null && r.getContent() instanceof java.util.List<?> blocks) {
            for (Object o : blocks) {
                if (o instanceof com.skillforge.core.model.ContentBlock cb && Boolean.TRUE.equals(cb.getIsError())) {
                    status = "error"; errorMsg = String.valueOf(cb.getContent()); break;
                }
            }
        }
        return r;
    } catch (Exception e) {
        status = "error"; errorMsg = e.getMessage();
        return Message.toolResult(block.getId(), "Tool execution error: " + e.getMessage(), true);
    } finally {
        long dur = System.currentTimeMillis() - start;
        if (broadcaster != null && sid != null) {
            broadcaster.toolFinished(sid, block.getId(), status, dur, errorMsg);
        }
        loopCtx.recordToolCall(block.getName());
    }
}
```

> 与 line 772-831 `supplyAsync` 分支的内层逻辑同源,只是去掉了 supplyAsync 包装、askResults synchronized put 与 trace span(install 自己的 INSTALL_CONFIRM span 已在 §4.3 dispatch 处发)。

### 4.3 engine line 728-833 dispatch 循环改写

```java
for (int i = 0; i < toolUseBlocks.size(); i++) {
    ToolUseBlock block = toolUseBlocks.get(i);
    if (AskUserTool.NAME.equals(block.getName())) {
        Message r = handleAskUser(block, loopCtx);                          // 已有
        askResults.put(i, r);
    } else if (ContextCompactTool.NAME.equals(block.getName())) {
        Message r = handleCompactContext(block, loopCtx);                   // 已有
        askResults.put(i, r);
    } else if (isInstallRequiringConfirmation(block, loopCtx, sessionConfirmCache)) {
        long start = System.currentTimeMillis();
        Message r = handleInstallConfirmation(block, loopCtx);              // **新增**
        askResults.put(i, r);
        if (traceCollector != null && rootSpan != null) {
            TraceSpan span = new TraceSpan("INSTALL_CONFIRM", "install_confirmation");
            span.setSessionId(sid); span.setParentSpanId(rootSpan.getId());
            span.setIterationIndex(loopCtx.getLoopCount());
            span.setStartTimeMs(start);
            span.setInput(block.getInput() != null ? block.getInput().toString() : "");
            span.setOutput(r.getTextContent());
            span.end();
            traceCollector.record(span);
        }
    } else {
        // 现有 supplyAsync 分支不变(line 765-832)
        ...
    }
}
```

第 837 行 `allOf(futures).get(120, SECONDS)` 一字不改。**install 分支不在 futures 内**,自然不受 120s 拘束。

### 4.4 engine catch hook 异常的处理(B1 联动)

不需要 r1 §4.4 的 `catch (HookRejectedException)` 分支了,因为:
- install confirmation 走主线程分支,**不再经过 SkillHook 阻塞**
- SafetySkillHook 在 cache miss 时仅 `return null`,沿用 line 1296 现有 "rejected by hook" 路径(返回 isError tool_result),不抛异常
- `HookRejectedException` 类**不再引入**(从 r1 §3 删除),减少 core 接口变化

> 这点也回应了 r1 reviewer W6:旧无参构造器陷阱→ 改造后 SafetySkillHook 即使 prompter==null(测试场景)也是 fail-closed 行为,不会"静默放行";engine gate 缺失才会触发 ERROR 级日志,运维可观测。

### 4.5 chatLoopExecutor 容量论证

主线程阻塞 30 min 等用户确认,与现有 `handleAskUser` 阻塞 30 min 同性质 — 同一个 chatLoopExecutor 配置 size 已经容忍这种场景(参考 `askUserTimeoutSeconds = 30 * 60L` line 62)。无新增风险。**唯一**新增建议:`installConfirmTimeoutSeconds` 默认 1800s,通过 `application.yml` 暴露给运维下调。

---

## 5. `ChatService` 状态机变更(transition table 更新,B3+B4 联动)

| from | to | trigger | 触发方 | 备注 |
|------|----|---------|--------|------|
| running | waiting_user (step="waiting_confirmation") | engine handleInstallConfirmation 发 sessionStatus | DefaultConfirmationPrompter / engine | runtimeStatus 仅 WS 广播,不写 DB(W1 已知限制) |
| waiting_user (step="waiting_confirmation") | running | latch 解 + finally 检查 **无其它 pending** | engine handleInstallConfirmation finally | **B3 修**:若 `pendingAskRegistry.hasPendingForSession(sid)` → 不广播 running |
| waiting_user (step="waiting_confirmation") | running | session 被 cancel | CancellationRegistry → completeAllForSession(DENIED) | engine catch 走错误 tool_result 路径 |
| running / waiting_user | error | **崩溃后启动恢复** | `PendingConfirmationStartupRecovery@Order(50)`(§0.B4) | 同时补孤儿 `tool_result(isError=true)`,在允许 ChatService 接受任何 chatAsync **之前**完成 |
| (任何) → idle / error | (现有) | loop 结束 | runLoop finally | 顺带 `sessionConfirmCache.clear(sid)` |

**禁止的转换**:`waiting_user(step=waiting_confirmation)` 直接 → `error`(不经 running)。所有出口走 finally 的"带条件 sessionStatus 回拨"。

---

## 6. 飞书 adapter 改造(§6.1 / §6.3 / §6.4 保留 r1,§6.2 重写)

### 6.1 `FeishuClient.sendInteractiveAction(...)`(r3 扩 card elements)

方法签名与 r1/r2 §6.1 不变。**Card JSON elements 增 1 个 markdown 行**,显示 install target + 白名单继承说明:

```jsonc
{
  "elements": [
    { "tag": "markdown", "content": "**Agent wants to run:** `clawhub install obsidian`" },
    { "tag": "markdown", "content": "**Tool:** clawhub\n**Target:** `obsidian`" },           // ← r3 新增行
    { "tag": "markdown", "content": "_Approving this will also allow sub-agents and team members to install the **same target** without re-prompting. Installing a different target will prompt again._" },   // ← r3 新增
    { "tag": "hr" },
    { "tag": "action", "actions": [ /* Approve / Deny buttons,同 r1 */ ] },
    { "tag": "note", "elements": [{ "tag": "plain_text", "content": "Only the requester can approve. Expires in 30 min." }] }
  ]
}
```

> 用户在飞书 card 上能清楚看到"授权范围 = obsidian",如果下一次弹出的 card target ≠ obsidian,视觉上立刻能感知"又问了 = 换目标了 = 需要警惕"。

### 6.2 新 controller + 新 verifier(B2 fix)

**新建** `FeishuCardActionVerifier`(同包,**不**修改现有 `FeishuWebhookVerifier`,避免影响现有 message webhook 行为):

```java
// skillforge-server/src/main/java/com/skillforge/server/channel/platform/feishu/FeishuCardActionVerifier.java
public class FeishuCardActionVerifier {

    private static final long TIMESTAMP_WINDOW_SEC = 300;

    /**
     * 互动卡片回传(action callback)签名校验,strict 模式:
     * encryptKey 缺失 → 直接 throw,不允许"测试租户兜底"。
     *
     * 飞书互动卡片回传签名规范(2024 公开开发者文档):
     *  - Header: X-Lark-Signature
     *  - Header: X-Lark-Request-Timestamp
     *  - Header: X-Lark-Request-Nonce
     *  - Algorithm: SHA256(timestamp + "\n" + nonce + "\n" + encryptKey + "\n" + body)
     *    (与 event webhook 同算法,但 strict)
     *  - 时间窗 300s
     *
     * Dev 阶段在 Read 飞书官方"消息卡片回传"页面(open.feishu.cn/document/uAjLw4CM/...)二次核对算法名称
     * (确认 SHA-256 还是 HMAC-SHA1)与 header 大小写后再实现。Plan 不臆断;若官方与 webhook 不同,则在
     * sign() 函数里按官方实现,但"encryptKey 缺失即拒绝"原则不变。
     */
    public void verifyStrict(WebhookContext ctx, String encryptKey) {
        if (encryptKey == null || encryptKey.isBlank()) {
            throw new WebhookVerificationException("feishu-card-action",
                    "encryptKey not configured; card-action callback strict-mode requires encryptKey");
        }
        // ... 后续与 FeishuWebhookVerifier.verify 同结构(timestamp/nonce/signature 提取 + SHA256 比对)
        // 抽公共 sign(timestamp,nonce,key,body) 静态方法到 FeishuSignatures 工具类共用,避免 verifier 之间漂移。
    }
}
```

**`ChannelCardActionController`** 改动 vs r1:

```java
@PostMapping("/feishu/card-action")
public ResponseEntity<?> feishuCardAction(@RequestBody(required=false) byte[] rawBody,
                                          HttpServletRequest req) {
    Optional<ChannelConfigDecrypted> cfgOpt = configService.getDecryptedConfig("feishu");
    if (cfgOpt.isEmpty()) return ResponseEntity.status(404).body(Map.of("error","feishu not configured"));
    ChannelConfigDecrypted cfg = cfgOpt.get();
    WebhookContext ctx = new WebhookContext(headerMap(req), rawBody == null ? new byte[0] : rawBody);

    try {
        feishuCardActionVerifier.verifyStrict(ctx, cfg.webhookSecret());      // ← 强制
    } catch (WebhookVerificationException e) {
        log.warn("Card-action verify failed: {}", e.getMessage());
        return ResponseEntity.status(401).body(Map.of("error","verification failed"));
    }

    // 解析 + complete + toast(同 r1 §6.2 步骤 4-5,不变)
    ...
}
```

**Prompter 内 channel 选择预检**(§1 (b.2) 已写):若 `cfg.webhookSecret()` blank,prompter **不**把 feishu 当推送通道,直接抛 ChannelUnavailableException → engine catch → error tool_result。这是双保险,即使 endpoint 未被攻击者发现,也不会先发了 card 再被卡在签名校验。

### 6.3 飞书 WS 路径

P0 仅实现 HTTP callback;WS 路径(`FeishuWsEventDispatcher` 转发 card_action 事件)列为 follow-up,与 r1 不变。

### 6.4 多人群聊 user_id 鉴权(同 r1 §6.4,代码级机制不变)

> W4(`ChatWebSocketHandler.getCurrentTriggererOpenId` 职责混杂)进 dev brief,r2 不变更接口形态。

---

## 7. Flyway migration

**无新增**(同 r1 §7)。in-memory 数据结构 + 启动恢复脚本由 Java 代码 in-place 修复持久化的 SessionMessage 表(已存在)。

---

## 8. 前端组件(**保留 r2 §8 全部,r3 扩卡片显示 target + 说明**)

仅强调 r1 §8.4 输入框 disable 条件:`runtimeStatus === 'waiting_user' && (pendingAsk != null || pendingConfirm != null)`。
B3 fix 已经在后端阻止"两个 latch 同时挂",所以前端**不会**同时收到 ask + confirm,但 disable 条件仍按 OR 写,作为防御。

### §8.A `InstallConfirmationCard.tsx`(r3 扩字段 + 文案)

在 r2 §8.1 的卡片 JSX 基础上,`.confirm-meta` 区域增一行显示 target,并在 `.confirm-desc` 下方(或平行)追加一条白名单继承说明:

```tsx
<div className="confirm-meta">
  tool: <strong>{payload.installTool}</strong> · target: <code className="confirm-target">{payload.installTarget}</code> · session-only
</div>
<div className="confirm-desc">{payload.description}</div>

{/* r4 W9:unparseable / 多 install 情形显式警示,告诉用户本次授权不会被 cache */}
{(payload.installTarget === '*' || payload.installTool === 'multiple' || payload.installTool === 'unknown') && (
  <div className="confirm-warn" role="alert">
    ⚠️ 未能解析具体安装目标,本次确认<strong>不会</strong>被缓存;下次 install 仍会弹卡。
  </div>
)}

<div className="confirm-scope-note">
  Approving this will also allow sub-agents and team members spawned from this session to install the <strong>same target</strong> without re-prompting.
  Installing a different target will prompt you again.
</div>
```

`.confirm-warn` CSS:`background: var(--warn-bg, rgba(255,170,0,.08)); border-left: 2px solid #f0a020; padding: 8px 12px; border-radius: 4px; font-size: 12px; color: var(--warn-fg, #c88000);`(对齐 §design.md 的"developer precision"方向,不用鲜艳红色,用克制的橙色 accent)。

对应 TypeScript `ConfirmationPromptPayload` 类型(r2 §2.7 扩):

```ts
export interface ConfirmationPromptPayload {
  confirmationId: string;
  sessionId: string;
  installTool: string;
  installTarget: string;       // r3 新
  commandPreview: string;
  title: string;
  description: string;
  choices: ConfirmationChoice[];
  expiresAt: string;
}
```

CSS 增 `.confirm-scope-note` 小字灰色 + `.confirm-target` 等宽背景块 — 与项目 design.md 的"developer precision"方向一致。

---

## 9. 测试与验证清单(扩 5 类,B1-B4 + W5)

### 9.1.r3 白名单继承(方案 C)新增测试(叠加在下面 r2 §9.1 全表之上)

| 文件 | 用例 | 对应点 |
|------|------|------|
| `InstallTargetParserTest`(新) | (a) `npx clawhub install obsidian` → `("clawhub","obsidian")`;(b) `skillhub install @org/pkg` → `("skillhub","@org/pkg")`;(c) `skill-hub/cli install foo-bar_1.0` → `("skill-hub","foo-bar_1.0")`;(d) `clawhub install` 无参 → `("unknown","*")`;(e) `clawhub install $(malicious)` 或包含 shell 元字符 → 解析到 `"$"`/`""`/fallback `"*"`,不 panic;**(f r4) `clawhub install --force obsidian` → `("clawhub","*")`(flag 前缀不作 target,防 cache key 污染);(g r4) `clawhub install -y pkg` → `("clawhub","*")`** | §2.B |
| `SessionConfirmCacheTest`(r3 扩) | (a) `approve(root, clawhub, A)` 之后 `isApproved(root, clawhub, A)=true`,`isApproved(root, clawhub, B)=false`;(b) `isApproved(other_root, clawhub, A)=false`;(c) `approve(*)`/`isApproved(*)` 均视为 miss;(d) `clear(root)` 抹掉该 root 所有 (tool,target) | §0#5 + §2.A |
| `RootSessionResolverTest`(新) | (a) 父 session 无 parent → 返回自身;(b) 子 session parentSessionId=X,X parent=null → 返回 X;(c) 深度 = 5 链正常;(d) 深度 > 10 → 触顶 fall-back 返回当前节点;(e) 链路出现 null / 自环 → 不爆,保守 fall-back | §3.A |
| `AgentLoopEngineInstallConfirmationTest`(r3 扩) | (a) 子 session 调 install 同 target → cache 命中直接 executeToolCall,不 prompt;(b) 子 session 调 install **不同 target** → 仍触发 prompt(卡片里 target 正确);(c) team 成员 A 授权后,成员 B 同 target install 放行,**换 target 仍弹** | SubAgent / TeamCreate 场景 |
| `InstallConfirmationScopeIT`(新集成测试) | 真跑:创建父 session,Agent 触发 `clawhub install obsidian` → 用户 Approve → `SubAgent` 派子 session,子 session Agent 再触发 `clawhub install obsidian` → 无弹卡,直接执行;子 session Agent 触发 `clawhub install another` → **弹卡**;用 `MockMvc` + WS 断言事件序列 | 端到端 |

### 9.1 单元测试(P0,**新增 / 强化**)

| 文件 | 用例 | 对应 blocker |
|------|------|------------|
| `AgentLoopEngineInstallConfirmationTest`(新) | (a) install Bash + APPROVED → executeToolCall 主线程跑,messages 末尾 1 条 tool_result(success);(b) install Bash + DENIED → 1 条 tool_result(error);(c) **同 turn ask_user + install Bash → install 直接 error("ask pending"),不挂 latch**;(d) **install Bash 阻塞 > 120s → 不被 allOf timeout 误标 error,主线程正常 await 30 min**(用 PendingConfirmationRegistry mock 控制 latch); (e) cache 已命中 → 直接 executeToolCall,无 prompt | B1 / B3 |
| `PendingAskRegistryTest`(扩) | `hasPendingForSession` 返回值正确;`PendingAsk.sessionId` 字段被 register 写入 | B3 |
| `PendingConfirmationRegistryTest`(新) | register/await/complete happy;await timeout;completeAllForSession DENIED;`hasPendingForSession` | B3 联动 |
| `SessionConfirmCacheTest`(新) | per-install-tool 隔离;clear 后 isApproved=false | — |
| `DefaultConfirmationPrompterTest`(新) | feishu encryptKey 空 → 抛 ChannelUnavailableException;web channel happy;send card 失败 → 抛 ChannelUnavailableException;timeout 返回 TIMEOUT | B2 / B1 |
| `FeishuCardActionVerifierTest`(新) | encryptKey 空 → throw;签名 mismatch → throw;happy → 通过 | B2 |
| `ChannelCardActionControllerTest`(新) | (a) verifyStrict 抛异常 → 401;(b) 过期 confirmationId → 200 toast("已失效");(c) **非 triggerer 200 toast + 不 complete**;(d) 成功 complete + toast | B2 |
| `PendingConfirmationStartupRecoveryTest`(新) | (a) **session 有 tool_use 无 tool_result → 启动后 messages 多一条 fabricated tool_result(isError=true, content="Install confirmation aborted due to server restart"),runtimeStatus=error**;(b) 多个孤儿全部补齐;(c) 无孤儿仅置 error;(d) 启动 Order < ChatService 接受 chatAsync 的 init Order | B4 |
| `SafetySkillHookTest`(扩) | install pattern + cache miss → return null(fail-closed);install pattern + cache hit → return input;非 install 命令不受影响 | B1 (4.1 防御) |

### 9.2 集成 / E2E(agent-browser 真打开 dashboard)

- 触发 install → 看到 `confirmation_required` 卡(含 target 行 + 继承说明)→ Approve → Bash 真跑 → tool_result success
- 同 session 第二次**相同** installTarget → 不弹卡(cache hit);**换 target** → 重新弹卡
- 飞书:**多人群聊里非 triggerer 点按钮 → toast "无权限"**
- **SubAgent 子 session 共享父 session 授权(相同 target 不再弹卡)**(r3 新)
- **杀进程 mid-pending → 重启 → session 进 error,messages 中孤儿被补齐;前端打开 session 不再 LLM-error**(B4 验证)

### 9.3 锁不变量回归

- `tool_use ↔ tool_result` 配对断言(在 AgentLoopEngineInstallConfirmationTest 收尾断言所有 tool_use id 都有相同 id 的 tool_result)
- 启动恢复执行后的 messages 配对断言

---

## 10. Dev task 拆分(**保留 r1 §10**;以下仅注明 B1-B4 修复落到哪步)

### 10.1 Backend Dev(单 dev,顺序)

1. **core 层**(§2 + §3):`PendingConfirmation` / `PendingConfirmationRegistry` / `SessionConfirmCache` **(复合 key: rootSessionId, toolName, installTarget)** / `ConfirmationPrompter` SPI / `ChannelUnavailableException` / `ConfirmationPromptPayload`(含 installTarget 字段) / **`InstallTargetParser`(r3 新)**
2. **PendingAskRegistry 扩**:加 sessionId 字段 + `hasPendingForSession` API(B3 fix 必需)
3. **engine 接线**(B1 fix 核心):`AgentLoopEngine` line 728-833 dispatch 循环新增 install 分支(§4.3);`handleInstallConfirmation` 私有方法(§4.2);`isInstallRequiringConfirmation` 静态助手;`installConfirmTimeoutSeconds` 字段 + setter
4. **SafetySkillHook 重构**(§4.1):仅做 cache 短路 + fail-closed,不阻塞、不抛新异常
5. **server 层**:`DefaultConfirmationPrompter`(主线程模型,encryptKey 预检);**`RootSessionResolver`(r3 新,深度 10 递归)**;`@Bean` 装配 `SessionConfirmCache` / `PendingConfirmationRegistry` / `RootSessionResolver`;`ChatEventBroadcaster.confirmationRequired` 接口方法 + `ChatWebSocketHandler` 实现
6. **ChatService 收尾**(r3 修):loop finally 条件调 `sessionConfirmCache.clear(rootSid)` **只在 `sid == rootSid`(真正根 session)时触发** — 子 session 结束**不**清 cache(§2.A 注释);cancel 路径调 `pendingConfirmationRegistry.completeAllForSession(sid, DENIED)`。子 session 的 cancel 也只操作**自身** pending latch,不影响根 cache
7. **Web REST**:`ChatController.confirm()` 端点
8. **Feishu adapter**(B2 fix):`FeishuClient.sendInteractiveAction(...)`;**新** `FeishuCardActionVerifier`;**新** `ChannelCardActionController`;`ChannelSessionRouter` / `ChatWebSocketHandler.registerChannelTurn` 增 triggererOpenId 字段
9. **启动恢复**(B4 fix 核心):新 `PendingConfirmationStartupRecovery@Order(50)` implements `ApplicationRunner`;扫 sessions、collectOrphanToolUseIds、补 fabricated tool_result、置 error
10. **测试**:§9.1 全部单元测试

### 10.2 Frontend Dev(独立,与 backend 部分并行)

`api/index.ts` + `InstallConfirmationCard.tsx` + `useChatWsEventHandler.ts` + `pages/Chat.tsx` + agent-browser 手测。

**r3 子任务(加)**:
- `ConfirmationPromptPayload` 类型加 `installTarget: string`(§2.7 扩字段)
- `InstallConfirmationCard.tsx` 渲染 Target + Scope-note(§8.A)
- agent-browser 手测覆盖"同 target 不弹卡 + 换 target 弹卡"两条断言(§9.2)

### 10.3 串行 / 并行关系

```
Backend 1+2 (core+PendingAsk扩) ── Backend 3+4 (engine+SafetyHook) ──┐
                                                                       ├─→ Backend 5-9 (server wiring/REST/feishu/启动恢复)
Frontend 1-3 (API+component+ws) ──────────────────────────────────────┤
                                                                       └─→ Backend 10 单测 + Frontend 4-5 联调
```

**严格依赖**:Backend 2(PendingAskRegistry 加 sessionId 字段)是 B3 修的核心,影响接口签名。Backend 3 依赖它。Frontend 1 仍只依赖 §2 DTO 字段稳定。**r3 新依赖**:Backend 1(`InstallTargetParser`)必须与 `DangerousCommandChecker.CONFIRMATION_REQUIRED_PATTERNS` 保持三 pattern 同步;Backend 3(engine install 分支)与 Backend 5(`RootSessionResolver`)可并行实现,但 engine 分支测试需等 resolver 就绪。

---

## 11. 安全 / footgun 自查(对照 `.claude/rules/`)

| 项 | 状态 |
|----|------|
| ObjectMapper JavaTimeModule | DTO 用 Instant,序列化复用 `ChatWebSocketHandler.objectMapper`(r1 不变) |
| `@Transactional` 只在 Service public 方法 | `PendingConfirmationStartupRecovery` 调 `sessionService.appendNormalMessages` / `sessionService.saveSession`,事务在 SessionService 内,recovery 类自身不加 `@Transactional` |
| LLM chatStream 不重试 | 不涉及 |
| WS useEffect cleanup | 沿用 r1 §8 |
| Flyway migration | 无新增 |
| 飞书签名 | **B2 修**:strict verifier;encryptKey 缺失双重拒绝(verifier + prompter 预检) |
| 多人鉴权 | r1 §6.4 不变,verifier 修后才有效 |
| **tool_use ↔ tool_result 守恒** | **B1 + B4 双修**:运行期由 engine 主线程分支保证 1:1;崩溃后由 startup recovery 补齐孤儿 |
| 命令注入 | commandPreview 仅 truncate 展示,不 eval |
| 日志泄漏 | 仅 SkillForge userId / 飞书 open_id,无 PII |
| chatLoopExecutor 容量 | 与 ask_user 同性质,不新增风险 |
| **状态机并行冲突**(W2 + B3) | finally 带条件回拨 + ask pending 入口预检,代码级互斥 |

---

## 附录:r3 vs r2 类与方法增改一览(仅列 r3 新增 / 扩字段,r2→r1 迁移清单见下一节)

| 状态 | 路径 / 变化 |
|------|------|
| 新 | `core/.../engine/confirm/InstallTargetParser.java`(§2.B) |
| 新 | `server/.../engine/RootSessionResolver.java`(§3.A,深度 10) |
| 改 | `core/.../engine/confirm/SessionConfirmCache.java`(§2.A,key 升为 `(rootSessionId, toolName, installTarget)`,复合字符串 `tool::target` 存 Set) |
| 改 | `core/.../engine/confirm/ConfirmationPromptPayload.java`(+`installTarget` 字段) |
| 改 | `core/.../engine/confirm/ConfirmationPrompter.java` → `ConfirmationRequest` record 加 `installTarget` 字段 |
| 改 | `core/.../engine/confirm/PendingConfirmation.java`(+`installTarget` 字段,payload 同源) |
| 改 | `core/.../engine/SafetySkillHook.java`(cache 短路改用 `(rootSid, tool, target)` + 解析器注入 + `RootSessionResolver` 注入;§4.1 重写) |
| 改 | `core/.../engine/AgentLoopEngine.java`(`handleInstallConfirmation` 用 parser + resolver;构造 ConfirmationRequest 带 target;`cache.approve(rootSid, tool, target)`) |
| 改 | `server/.../engine/DefaultConfirmationPrompter.java`(构造 payload 带 target,日志带 target) |
| 改 | `server/.../channel/platform/feishu/FeishuClient.java#sendInteractiveAction`(card elements 增 Target 行 + Scope-note,§6.1) |
| 改 | `dashboard/src/api/index.ts`(`ConfirmationPromptPayload` 类型加 `installTarget: string`) |
| 改 | `dashboard/src/components/InstallConfirmationCard.tsx`(§8.A:渲染 target + scope note + CSS `.confirm-scope-note` / `.confirm-target`) |
| 无变化 | §1 架构时序(整体结构) / §3 主干 SPI / §4.2 `handleInstallConfirmation` 外壳 + §4.4 / §4.5 / §5 状态机 / §6.2 strict verifier + `ChannelCardActionController` / §6.3 WS / §6.4 多人鉴权 / §0.B4 startup recovery / §7 migration / §11 自查 |

## 附录:r2 vs r1 类与方法增改一览

| 状态 | 路径 |
|------|------|
| 新 | `core/.../engine/confirm/{PendingConfirmation, PendingConfirmationRegistry, SessionConfirmCache, ConfirmationPrompter, ConfirmationPromptPayload, ChannelUnavailableException}.java` |
| 删除 r1 引入 | `HookRejectedException.java`(不再需要,§4.4) |
| 改 | `core/.../engine/PendingAskRegistry.java`(加 sessionId 字段 + `hasPendingForSession`) |
| 改 | `core/.../engine/SafetySkillHook.java`(改造为 cache 短路 + fail-closed,§4.1) |
| 改 | `core/.../engine/AgentLoopEngine.java`(line 728-833 增 install 分支 + 新方法 `handleInstallConfirmation` / `isInstallRequiringConfirmation` / `matchInstallPattern`) |
| 改 | `core/.../engine/ChatEventBroadcaster.java`(新 `confirmationRequired`) |
| 新 | `server/.../engine/DefaultConfirmationPrompter.java`(encryptKey 预检) |
| 改 | `server/.../service/ChatService.java`(finally clear cache + cancel 联动 completeAllForSession) |
| 改 | `server/.../controller/ChatController.java`(新 `/{sessionId}/confirmation`) |
| 新 | `server/.../channel/web/ChannelCardActionController.java`(B2:strict verify) |
| 新 | `server/.../channel/platform/feishu/FeishuCardActionVerifier.java`(B2) |
| 改 | `server/.../channel/platform/feishu/FeishuClient.java`(`sendInteractiveAction`) |
| 改 | `server/.../channel/router/ChannelSessionRouter.java`(透传 triggererOpenId) |
| 改 | `server/.../websocket/ChatWebSocketHandler.java`(`registerChannelTurn` 扩 triggererOpenId + `confirmationRequired` 实现) |
| 新 | `server/.../init/PendingConfirmationStartupRecovery.java`(B4:`@Order(50) ApplicationRunner`) |
| 新 | `dashboard/src/components/InstallConfirmationCard.tsx`(+ CSS) |
| 改 | `dashboard/src/api/index.ts`(`submitConfirmation` + 类型) |
| 改 | `dashboard/src/hooks/useChatWsEventHandler.ts` |
| 改 | `dashboard/src/pages/Chat.tsx` |
| 新(r3) | `core/.../engine/confirm/InstallTargetParser.java`(含 flag 前缀拒绝,B5 修复) |
| 新(r3) | `core/.../engine/confirm/RootSessionLookup.java` SPI |
| 新(r3) | `server/.../engine/DefaultRootSessionLookup.java`(parentSessionId 递归,深度限 10) |
| 改(r3) | `SessionConfirmCache`(字段扩 installTarget,复合 key) |

---

## Dev brief 补丁(Judge r2/r3 追加的 warning 处理意见)

Phase 2 Dev 开工前必须消化:

### Judge r2 追加(对 §0.B4 / §4.4 / §6.2 / `PendingAskRegistry` 的加强)
1. **W2**:`PendingConfirmationStartupRecovery` 直接用 `SmartLifecycle` + `getPhase() = Integer.MIN_VALUE + 100`,不要从 `ApplicationRunner @Order(50)` 起步。Spring Boot 3.2 embedded Tomcat 下 ApplicationRunner 对"阻塞 web server 接受请求"不够硬
2. **W3**:`AgentLoopEngine.handleInstallConfirmation` 形参补 `toolCallRecords`(与 `handleAskUser` 对称),避免 tool_result 写回后被主循环重复 append
3. **W5**:Backend Dev 开工前先 WebFetch 飞书"消息卡片回传"官方文档,锁定 header 名 / 签名算法 / 密钥字段,写入 `FeishuCardActionVerifier` 的 JavaDoc
4. **W7**:`PendingAskRegistryTest` 补 register/await/complete 带 sessionId 字段后的 happy-path / timeout / cancel 回归用例

### Judge r3 追加(对方案 C / `RootSessionLookup` / UX 的加强)
5. **W10**:`RootSessionResolver` 深度 > 10 保守回退到原 `sessionId`(而非触顶 `cur`),避免 cache 键不稳
6. **W11**:`LoopContext` 加 `transient String rootSessionIdCache`,单次 loop 内 `resolveRoot` 只查一次 DB;§9.1.r3 加断言
7. **W12**:`SessionConfirmCache.clear` 时机统一描述——§5 状态机表最后一行改为 `loop 结束 → 若 sid == resolveRoot(sid) 则 clear(rootSid)`;§11 footgun 自查加"进程重启 cache 全失效是预期行为"一行

Nit 全部折叠到 `/tmp/nits-followup-install-confirm.md`,Phase 4 commit message 引用。

---

## Phase 1 对抗循环审查记录

完整 trail(归档路径):

| 轮次 | 产物 | 结论 |
|------|------|------|
| Brief | `/tmp/install-confirm-brief.md` | — |
| **r1** plan | `/tmp/install-confirm-plan-r1.md` | 36KB |
| r1 review | `/tmp/install-confirm-plan-review-r1.md` | FAIL:4 blocker + 6 warning |
| r1 judge | `/tmp/install-confirm-plan-judge-r1.md` | FAIL(独立验证 4 blocker 成立) |
| **r2** plan | `/tmp/install-confirm-plan-r2.md` | 40KB,修 r1 4 blocker |
| r2 review | `/tmp/install-confirm-plan-review-r2.md` | PASS:0 blocker + 7 warning |
| r2 judge | `/tmp/install-confirm-plan-judge-r2.md` | **PASS**(独立验证 r1 4 blocker 均实质修复) |
| **r3** plan | `/tmp/install-confirm-plan-r3.md` | 43KB,增量补方案 C(白名单继承) |
| r3 review | `/tmp/install-confirm-plan-review-r3.md` | PASS:0 blocker + 5 warning(W8=flag 污染) |
| r3 judge | `/tmp/install-confirm-plan-judge-r3.md` | FAIL(独立把 W8 升级为 B5) |
| **r4** plan | `/tmp/install-confirm-plan-r4.md` | 45KB,surgical 修 B5(用户批准跳过 review/judge) |
| Nits followup | `/tmp/nits-followup-install-confirm.md` | Phase 4 commit 引用 |

**对抗循环修复的 blocker 链**:
- **r1 → r2(4 blocker 全部修复)**:
  - **B1**:SkillHook 同步阻塞机制与 `AgentLoopEngine.supplyAsync.get(120s)` 物理冲突 → 改为 engine 主线程新分支,不走 supplyAsync
  - **B2**:飞书 card_action 复用 `FeishuWebhookVerifier` 会继承 encryptKey 为空就放行的兜底 → 新建 strict verifier + prompter 双保险
  - **B3**:同 turn 并行 `ask_user + Bash(install)` 状态机无互斥 → `PendingAskRegistry.hasPendingForSession` 预检 + finally 条件回拨
  - **B4**:进程崩溃后孤儿 tool_use 无 recovery → `PendingConfirmationStartupRecovery` 启动期补 fabricated error tool_result
- **r3 → r4(B5 修复)**:
  - **B5**:`InstallTargetParser` 被 flag 前缀污染(`--force` / `-y` 被当 target),跨 team 成员 silent bypass → parser 拒绝 `-` 开头 token 归一为 `*`,强制每次弹卡

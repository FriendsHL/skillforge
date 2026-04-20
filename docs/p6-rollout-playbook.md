# P6 灰度与回滚演练手册

## 1. 目标

- 验证 `session_message` 行存储读写链路在灰度阶段可稳定工作。
- 验证双读校验日志可识别新旧存储不一致。
- 验证出现异常时可快速回退到 `messagesJson`。

## 2. 配置开关

`application.yml`：

```yaml
skillforge:
  session-message-store:
    row-read-enabled: true
    row-write-enabled: true
    dual-read-verify-enabled: false
    backfill-enabled: true
    max-backfill-sessions-per-startup: 300
```

含义：

- `row-write-enabled`：是否写 `t_session_message`。
- `row-read-enabled`：是否从 `t_session_message` 读取消息。
- `dual-read-verify-enabled`：是否在读取时做新旧一致性日志校验。
- `backfill-enabled`：是否在启动时执行 legacy 回填。
- `max-backfill-sessions-per-startup`：单次启动回填上限，避免阻塞过久。

## 3. 推荐灰度阶段

### 阶段 A（回填 + 双写预热）

- `row-write-enabled=true`
- `row-read-enabled=false`
- `dual-read-verify-enabled=false`

动作：

1. 启动服务，等待 `SessionMessageStoreBackfill` 执行完成。
2. 抽样检查：
   - `t_session` 中历史会话数量
   - `t_session_message` 中已回填会话数量

### 阶段 B（双读校验）

- `row-write-enabled=true`
- `row-read-enabled=true`
- `dual-read-verify-enabled=true`

动作：

1. 观察日志关键字：`Dual-read mismatch`
2. 抽样检查 UI 历史消息和数据库行数是否一致
3. 触发一次 full compact，确认生成 checkpoint

### 阶段 C（稳定运行）

- `row-write-enabled=true`
- `row-read-enabled=true`
- `dual-read-verify-enabled=false`

动作：

1. 连续观察 24h，无异常后保持该配置。
2. 再考虑移除 legacy 读路径（后续版本）。

## 4. 回滚方案

出现故障时，优先做“读回滚”，再做“写回滚”：

1. 读回滚（优先）
   - `row-read-enabled=false`
   - `row-write-enabled=true`
2. 若写链路也异常
   - `row-write-enabled=false`
   - 保留 `row-read-enabled=false`

说明：写回滚后，新增消息仅落 `messagesJson`，不再进入行存储。

## 5. 演练检查清单

- [ ] 服务启动日志出现 `SessionMessageStoreBackfill`，且无死循环告警
- [ ] Chat 页面消息正常加载（含 `SUMMARY` 展示）
- [ ] `GET /api/chat/sessions/{id}/checkpoints` 返回数据
- [ ] `POST /api/chat/sessions/{id}/prune-tools` 可返回 `prunedCount`
- [ ] 回滚到 `row-read-enabled=false` 后聊天可继续


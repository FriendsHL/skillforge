# Compaction 存储模型重构 — 完整设计（决策已锁定）

> 2026-06-19，architect(opus) 出 + 主会话审 + 用户拍板 Q1–Q5。根治"压缩问题"的存储模型重构(超出原 bug-fix 范围)。
> 核心改动红灯 Full + compact-reviewer。

## 0. 摘要

当前压缩把**模型视图物化成物理行**:每次 full compact 追加 `[COMPACT_BOUNDARY, SUMMARY(user), ...重复 young-gen]`,`getContextMessages` 只读最后 boundary 之后 → young-gen 每次物理复制(9d3eff0f:562 行/156 distinct/406 dup)+ tool-heavy boundary 退化 reclaim≈0。

**重构三视图分离**:
- **存储** = 全量真实历史,append-only,压缩**永不写消息行/不删/不复制**。
- **用户视图** = 全部真实行,压缩不可见(看完整原文)。
- **模型视图** = 读时**派生**:`[滚动摘要] + 最近未覆盖尾巴`。

新增 `t_session_summary`(范围摘要表,模型注入、用户不见)+ `t_session_message.compacted_by_summary_id`(标记被覆盖行,用户看得到、模型跳过)。难点(派生视图下对账)用 **provenance 来源标签 + 只持久化新真实轮次** 解决。

## 1. 现状(as-is)
- full compact 写(`CompactionService.persistCompactResult:586-671`):boundary 行 + SUMMARY 行(role=USER)+ **每条 young-gen 当新 NORMAL 行重追加** + 可选 RECOVERY_PAYLOAD;**不删任何行** → 膨胀根因。
- 模型视图 `getContextMessages:464-484` = 最后 boundary 之后所有行。
- 用户视图 `getFullHistoryRecords` 读全部(含重复)→ 前端 `useChatMessages.ts:115-228` 按 msg_type 渲染。
- 对账 `updateSessionMessages:692-801`:commonPrefixSize+messageEquals 字节比 + b2c7039 兜底。
- checkpoint/restore/branch replay full history(含重复行)。

## 2. 目标(to-be)

### Schema
```sql
CREATE TABLE t_session_summary (
  id BIGSERIAL PK, session_id VARCHAR(36), start_seq BIGINT, end_seq BIGINT,
  summary_text TEXT, level VARCHAR(16), source VARCHAR(32),
  tokens_before INT, tokens_after INT, compacted_message_count INT,
  recovery_payload TEXT,            -- Q4: 恢复信息存这里(非消息行),重启直接读不重算
  superseded_by BIGINT,             -- Q3: 合并时旧摘要指向新摘要;NULL=active
  created_at, FK session ON DELETE CASCADE);
CREATE INDEX idx_ss_session_active ON t_session_summary(session_id,start_seq) WHERE superseded_by IS NULL;
ALTER TABLE t_session_message ADD COLUMN compacted_by_summary_id BIGINT;  -- denormalized 非 identity
CREATE INDEX idx_session_message_compacted ON t_session_message(session_id,compacted_by_summary_id)
  WHERE compacted_by_summary_id IS NOT NULL;
```

### 新 full compact 写
1. 算覆盖真实范围 `[startSeq,endSeq]`(策略上报;`endSeq` 用 `findSafeBoundary` 保证 pair-complete,守 INV-1)。
2. **【Q3 合并】** 用"上一条 active summary 文本 + 新窗口"LLM 总结 → 生成覆盖 `[0,endSeq]` 的单条新 summary;旧 active summary `superseded_by=newId`。
3. INSERT t_session_summary(含 recovery_payload)RETURNING id;UPDATE 被覆盖行 `compacted_by_summary_id=newId`。
4. **不 append 消息行、不删、不重追加 young-gen。** checkpoint 行指向 summary.id + 范围(同一事务)。

### getContextMessages 派生
线性扫全部行:遇 active summary 覆盖的连续段 → emit 一条 `Message.user(summary_text)` 跳过该段;未覆盖行原样 emit(跳 SYSTEM_EVENT)。合并模型下通常 = `[单条滚动摘要] + 尾巴`。精确重建喂 LLM 的内容。

### 用户视图
读所有 NORMAL/SYSTEM_EVENT 行 + 暴露 `compacted` 布尔;**不读 summary 表**。前端新会话去掉 boundary/summary 特判,compacted 行可折叠/正常显示。

## 3. 难点:派生视图下的对账(INV-4)—— provenance 标签(Q5)
engine 内存=派生视图,存储=全量真实历史,位置不对齐。方案:
- `getContextMessagesWithProvenance` 返回 `{messages, long[] provenance}`:`>=0`=真实行 seq;`-1`=注入摘要;`NEW_SENTINEL`=本轮新真实消息。
- ChatService/engine 全程 messages 与 provenance 同步(append→NEW_SENTINEL;压缩替换→`CompactCallbackResult` 带新 provenance 一起换)。
- loop 后 `persistLoopResult`:**只落 NEW_SENTINEL 的真实新轮次**;摘要(-1)永不落库;已存在行(>=0)跳过。
- **永不重建持久化列表** → 历史字节漂移无法再致 dup-append(根治 bdb0453 类)。
- 兜底:provenance 完整性断言,违反 log.warn + 安全重建,不静默 dup。
- **【Q5 已定】用 provenance 数组(贴来源标签),不用"本轮新增几条"单 int**——后者假设新消息永远规规矩矩加末尾,压缩动了尾巴会算错/丢/重;数组更稳。

## 4. checkpoint/restore/branch
全量真实历史无重复 → 简单。restore:rewriteMessages 到真实行 + 删 start_seq>endSeq 的 summary + 按 range 重算 marker。branch:复制 end_seq≤endSeq 的 summary 重映射 id。

## 5. 迁移 —— 【Q1 已定:不清存量】
- Schema 前向迁移(建表+加列+索引)。
- **存量膨胀 session 走 (A) 双模式读**:有 boundary 行的老 session 仍走老 slice 路径,有 summary 的新 session 走派生。**不写去重脚本、不动 9d3eff0f 的 406 行**(零风险;新做法上线后不再产生重复)。(B) 去重 job 不做,真嫌占地方将来再单独提。

## 6. 已发代码回滚评估(/tmp/compact-dev-diff.patch)—— **无一回滚**
- **① 负 gap**:KEEP(新模型下以 summary.end_seq 表"新真实轮次",语义更干净;先保留原样防 `ratio=1.72 but skipped`)。
- **② 退化 guard**:KEEP 但**改注释**(新模型无重追加,"snowball/re-append"理由失效;guard 作"不值当压缩"过滤保留)。
- **③ 总结输入窗口分块**:KEEP 原样(与存储正交,合并后窗口可能更大,更需要)。
- **附带 resolveContextWindow per-model map**:KEEP。

## 7. 不变量
- **INV-1**:summary 边界复用 `findSafeBoundary`(不切 tool 对)→ 构造性满足;摘要内 pending tool_use args verbatim(已在 SUMMARY_SYSTEM_PROMPT)。
- **INV-4**:见 §3;摘要不落消息行、历史行压缩不重写 → 无两视图字节比对;新轮次字节一致不变。
- **INV-5**:`compacted_by_summary_id` denormalized → rewrite 后**从 range 重算**(优于 patch 对齐)+ `SessionServiceCompactedMarkerPreservationIT`。

## 8. 边界
- SubAgent/Team 注入:普通真实行,NEW_SENTINEL 落库,可被未来 range 覆盖。
- **light compact**:不建 range;**Rule 1 截断改为派生时做,Rules 2-4 dedup/fold/drop 改为模型视图层计算、不动存储**(Q2:用户看完整历史)。
- 并发:沿用 stripe lock + fullCompactInFlight;summary INSERT + marker UPDATE + checkpoint 同一事务。
- eval/evolve 读 `t_llm_trace` 独立表,不受影响;eval session 跳过压缩。
- **RECOVERY_PAYLOAD【Q4 已定】**:存在 `t_session_summary.recovery_payload` 字段(持久化、重启不重算、不污染消息存档),派生模型视图时注入。

## 9. 测试
unit(派生组装 / rightEdge→end_seq / provenance 只落新轮次 / 合并 superseded);IT(CompactionRangeModel:0 新消息行;ReconciliationDerivedView:无 dup + 两次加载字节一致;CheckpointRestoreBranch;LightCompactStorage;INV1Pairing;CompactedMarkerPreservation;合并滚动摘要;并发)。

## 10. 分期 / 风险

**分期**:P0 已发(保留,② 注释小修)→ P1 schema+写路径(flag,读不变)→ **P2 派生读+对账+合并(核心最高危,红灯 Full+compact-reviewer)** → P3 light 语义(Q2)+recovery(Q4)→ P4 仅双读(不做去重 job)。

**风险**:① P2 provenance 对账 bug → 兜底断言+安全重建+重 IT ② light 语义改动 token 回收回归 → benchmark+缓存派生视图 ③ 分期期双码路(老 boundary-slice vs 派生)收窄到"有 boundary 行且无 summary 行"。

**决策(全部已拍 2026-06-19)**:Q1 不清存量(双读)/ Q2 light 水分仅模型视图层、存档留全 / Q3 合并滚动摘要(对齐 CC,但留全量原文不丢)/ Q4 recovery 存 summary 表字段 / Q5 provenance 数组。

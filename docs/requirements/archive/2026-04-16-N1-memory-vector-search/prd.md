# N1 PRD

---
id: N1
status: done
owner: youren
priority: P1
risk: Full
mrd: ./mrd.md
tech_design: ./tech-design.md
created: 2026-04-16
updated: 2026-04-29
---

## 摘要

实现 Memory embedding、混合检索和可按需查看全文的工具层。

## 目标

- 支持 pgvector + FTS 混合检索。
- Memory 写入后异步生成 embedding。
- 提供 MemorySearchSkill 和 MemoryDetailSkill。
- 在 pgvector / embedding 不可用时降级。

## 非目标

- 不在本需求中实现 Memory v2 的写入/淘汰闭环。

## 功能需求

- V7 migration。
- EmbeddingProvider / OpenAiEmbeddingProvider。
- EmbeddingService。
- MemoryEmbeddingWorker。
- MemorySearchSkill。
- MemoryDetailSkill。

## 验收标准

- [x] 支持混合检索。
- [x] embedding 写入异步化。
- [x] pgvector graceful fallback。
- [x] 工具输出脱敏且可控。

## 验证预期

- Repository / service / skill tests。
- pgvector fallback tests。

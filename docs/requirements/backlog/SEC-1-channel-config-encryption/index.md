# SEC-1 Channel 配置加密

---
id: SEC-1
mode: full
status: deferred
priority: P1
risk: Full
created: 2026-04-28
updated: 2026-04-29
---

## 摘要

使用 AES-GCM 加密 Channel 配置中的敏感字段，例如 bot token 和 app secret。

旧 ToDo 代码扫描发现：`ChannelConfigEntity` / `ChannelConfigService` 已有明文 TODO，说明 AES-GCM 加密存储尚未完成，当前为明文 JSON。

## 当前状态

当前本地 / 单用户使用场景下重要但不紧急。多端部署、共享环境或 P12 正式上线前需要重新评估。

## 链接

| 文档 | 链接 |
| --- | --- |
| MRD | - |
| PRD | - |
| 技术方案 | - |

## 备注

- 敏感字段包括 `appSecret`、`botToken`、`encryptKey`。
- 密钥应来自环境变量或配置，不能写进源码。
- 现有明文数据需要迁移路径。

## 原始范围

- `ChannelConfigService` 存储前加密敏感字段。
- 敏感字段包括 appSecret、botToken、encryptKey。
- 读取时解密。
- 密钥从环境变量注入。
- 对已存在明文数据做一次性迁移脚本。
- 预估 1-2 天。

# N2 — Agent 行为规范层（Behavioral Rules）技术方案

> 生成日期：2026-04-16
> 来源：4-agent 设计流水线（2 Plan Agent + 2 Reviewer Agent，两轮迭代）

---

## 1. 概述

### 1.1 目标

为 SkillForge Agent 平台引入**结构化行为规范系统**，让用户可以：

- 从平台内置的 15 条行为规范中选择启用/禁用
- 添加自定义行为规则
- 通过预设模板（Autonomous / Cautious / Full）快速配置
- 行为规范自动注入到 Agent 的 system prompt 中

### 1.2 参考

- andrej-karpathy-skills（+30k 星/周）
- CLAUDE.md 结构化行为原则
- Claude Code hooks 系统

---

## 2. 数据模型

### 2.1 存储方案：AgentEntity 内嵌 JSON 字段

**决策理由**：Agent 与行为规则是 1:1 关系，与现有 `skillIds`、`toolIds`、`config` 字段的存储模式一致。

**扩展性声明**：当前设计为有意的 V1 trade-off。如果未来需要"规则模板共享"功能（多个 Agent 共享同一套规则配置），需要新建 `t_behavior_rule_template` 关联表。为降低迁移成本，在 `AgentDefinition` 层定义 `BehaviorRulesConfig` 结构化对象作为中间层，使 core 层与存储方式解耦。

### 2.2 AgentEntity 变更

```java
/** JSON: {"builtinRuleIds":["rule-id",...],"customRules":["text",...]} */
@Column(columnDefinition = "TEXT")
private String behaviorRules;
```

### 2.3 AgentDefinition 变更

```java
/**
 * 结构化行为规则配置，由 AgentService.toAgentDefinition() 从 JSON 解析。
 * SystemPromptBuilder 直接消费 resolvedBehaviorRules，不做 JSON 解析。
 */
public static class BehaviorRulesConfig {
    private List<String> builtinRuleIds = new ArrayList<>();
    private List<String> customRules = new ArrayList<>();
    // getters, setters
}

@JsonProperty("behavior_rules")
private BehaviorRulesConfig behaviorRules;

/** 解析后的 prompt 文本列表，由 AgentService 填充。不序列化。 */
@JsonIgnore
private List<String> resolvedBehaviorRules = new ArrayList<>();
```

### 2.4 JSON Schema

```json
{
  "builtinRuleIds": ["confirm-destructive-ops", "read-before-edit", "ask-when-ambiguous"],
  "customRules": ["Always respond in Chinese", "Never modify /config directory"]
}
```

- `builtinRuleIds`: 引用内置规则 ID，只存 ID 不存内容（规则文案可随版本更新）
- `customRules`: 用户自由文本（每条 ≤500 字符，最多 10 条）

### 2.5 Flyway Migration — `V8__agent_behavior_rules.sql`

```sql
-- N2: Agent behavior rules column
ALTER TABLE t_agent ADD COLUMN IF NOT EXISTS behavior_rules TEXT;

COMMENT ON COLUMN t_agent.behavior_rules IS
    'JSON: {"builtinRuleIds":["rule-id",...],"customRules":["text",...]}';
```

---

## 3. 内置规则库

### 3.1 存储方式

以 Java 资源文件管理，方便版本控制：`skillforge-core/src/main/resources/behavior-rules.json`

### 3.2 规则数据结构

```java
public record BehaviorRuleDefinition(
    String id,              // kebab-case，发布后不可删除/重命名
    String category,        // safety | quality | workflow | communication
    String severity,        // must | should | may
    String label,           // 英文 UI 标签
    String labelZh,         // 中文 UI 标签
    String promptText,      // 英文 prompt 注入文本
    String promptTextZh,    // 中文 prompt 注入文本
    boolean deprecated,     // 废弃标记
    String replacedBy,      // 替代规则 ID（deprecated 时填写）
    List<String> presets    // 所属预设模板：["autonomous", "cautious", "full"]
) {}
```

### 3.3 15 条内置规则

| # | ID | Category | Severity | Presets | 标签 | promptText 摘要 |
|---|---|---|---|---|---|---|
| 1 | `confirm-destructive-ops` | safety | must | A/C/F | 确认破坏性操作 | Always ask user confirmation before destructive operations (rm -rf, DROP TABLE, force push) |
| 2 | `no-secret-in-output` | safety | must | A/C/F | 不输出密钥 | Never output API keys, passwords, or tokens in responses |
| 3 | `sandbox-file-scope` | safety | must | A/C/F | 限定文件范围 | Only read/write files within the project workspace |
| 4 | `no-force-push-main` | safety | must | A/C/F | 禁止 force push main | Never force-push to main/master branch |
| 5 | `validate-input` | safety | should | A/C/F | 校验外部输入 | Validate and sanitize user input before passing to shell commands |
| 6 | `read-before-edit` | quality | must | A/C/F | 先读再改 | Always read a file before editing it |
| 7 | `minimal-change` | quality | should | C/F | 最小化变更 | Make minimal, targeted changes; avoid refactoring unrelated code |
| 8 | `prefer-edit-over-write` | quality | should | C/F | 优先编辑不重写 | Use FileEdit for modifications, FileWrite only for new files |
| 9 | `no-mock-in-prod` | quality | should | C/F | 不加 mock 数据 | Never add mock data or placeholder implementations in production code |
| 10 | `preserve-existing-style` | quality | should | F | 保持现有风格 | Match the existing code style and conventions of the project |
| 11 | `explain-before-act` | workflow | should | C/F | 先解释再执行 | Explain your plan before executing multi-step operations |
| 12 | `test-after-change` | workflow | should | C/F | 改后测试 | Run relevant tests after making code changes |
| 13 | `commit-message-convention` | workflow | may | F | 遵循提交规范 | Follow the project's commit message convention |
| 14 | `ask-when-ambiguous` | communication | should | C/F | 不确定就问 | Ask clarifying questions when requirements are ambiguous rather than guessing |
| 15 | `progress-updates` | communication | may | F | 报告进度 | Provide progress updates for long-running multi-step tasks |

> A = Autonomous, C = Cautious, F = Full

### 3.4 预设模板与 executionMode 联动

| 模板 | 默认关联 | 规则数量 | 说明 |
|------|---------|---------|------|
| Autonomous | `auto` 模式 | ~5 条 | 仅 safety + quality(must) |
| Cautious | `ask` 模式 | ~10 条 | safety + quality + workflow + communication(should+) |
| Full | 手动选择 | 全部 15 条 | 所有规则 |

### 3.5 规则 ID 稳定性契约

- 一旦发布的规则 ID **不可删除或重命名**
- 废弃规则标记 `deprecated: true` + `replacedBy: "new-rule-id"`
- 运行时遇到 deprecated ID：自动转换为 replacedBy 指向的新 ID
- 遇到完全不认识的 ID：`log.warn` + API 响应中返回 `warnings` 数组

---

## 4. 后端核心实现

### 4.1 BehaviorRuleRegistry 服务（新增）

```java
@Service
public class BehaviorRuleRegistry {

    private final Map<String, BehaviorRuleDefinition> rulesById;
    private final Map<String, List<String>> presetRuleIds;

    public BehaviorRuleRegistry(ObjectMapper objectMapper) {
        // 从 classpath:behavior-rules.json 加载
    }

    /** 解析 BehaviorRulesConfig 为 prompt 文本列表。根据 systemPrompt 语言自动选择中/英文版本。 */
    public List<String> resolveRules(BehaviorRulesConfig config, String systemPrompt) { ... }

    /** 获取所有规则定义（供 API 返回）*/
    public List<BehaviorRuleDefinition> getAllRules() { ... }

    /** 获取指定预设模板的规则 ID 列表 */
    public List<String> getPresetRuleIds(String executionMode) { ... }

    /** 验证规则 ID 列表，返回 warnings（unknown/deprecated）*/
    public List<String> validateRuleIds(List<String> ruleIds) { ... }
}
```

### 4.2 语言检测（选择 promptText vs promptTextZh）

```java
static boolean isPrimarilyChinese(String text) {
    if (text == null || text.isBlank()) return false;
    long cjkCount = text.codePoints()
            .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
            .count();
    return (double) cjkCount / text.codePoints().count() > 0.3;
}
```

### 4.3 AgentService 修改

**`updateAgent()` — 修复遗漏（CRITICAL）：**

```java
// WARNING: 手动逐字段复制。新增 AgentEntity 字段时必须在此添加对应 setter！
existing.setBehaviorRules(updated.getBehaviorRules());
```

**`toAgentDefinition()` — JSON 解析 + 规则解析：**

```java
// 解析 behaviorRules JSON -> BehaviorRulesConfig
if (entity.getBehaviorRules() != null && !entity.getBehaviorRules().isBlank()) {
    try {
        BehaviorRulesConfig config = objectMapper.readValue(
                entity.getBehaviorRules(), BehaviorRulesConfig.class);
        def.setBehaviorRules(config);
    } catch (JsonProcessingException e) {
        log.warn("Failed to parse behaviorRules for agent {}", entity.getId(), e);
        def.setBehaviorRules(new BehaviorRulesConfig());
    }
}

// 解析 builtin rule IDs -> resolved prompt texts
def.setResolvedBehaviorRules(
        behaviorRuleRegistry.resolveRules(def.getBehaviorRules(), def.getSystemPrompt()));
```

### 4.4 SystemPromptBuilder 修改

**插入位置**：Available Skills 之后、Context 之前（利用 LLM recency bias 提高规则执行力）。

```java
// Behavior Rules — after Available Skills, before Context
List<String> behaviorRules = agentDefinition.getResolvedBehaviorRules();
if (behaviorRules != null && !behaviorRules.isEmpty()) {
    sb.append("## Behavior Rules\n\n");
    sb.append("You MUST follow these behavioral guidelines:\n\n");
    for (int i = 0; i < behaviorRules.size(); i++) {
        sb.append(i + 1).append(". ").append(behaviorRules.get(i)).append("\n");
    }
    sb.append("\n");
}

// Custom rules — sandboxed with XML tag (prompt injection defense)
List<String> customRules = agentDefinition.getBehaviorRules() != null
        ? agentDefinition.getBehaviorRules().getCustomRules() : null;
if (customRules != null && !customRules.isEmpty()) {
    sb.append("<user-configured-guidelines>\n");
    sb.append("The user has configured the following custom behavior guidelines:\n");
    for (String rule : customRules) {
        String sanitized = sanitizeCustomRule(rule);
        sb.append("- ").append(sanitized).append("\n");
    }
    sb.append("</user-configured-guidelines>\n\n");
}
```

### 4.5 自定义规则安全防护

```java
private static final int MAX_CUSTOM_RULES = 10;
private static final int MAX_CUSTOM_RULE_LENGTH = 500;
private static final Pattern DANGEROUS_TAGS = Pattern.compile(
        "<(?:system|assistant|user|tool_use|tool_result|function|instructions)[^>]*>",
        Pattern.CASE_INSENSITIVE);

static String sanitizeCustomRule(String rule) {
    if (rule == null) return "";
    String cleaned = DANGEROUS_TAGS.matcher(rule).replaceAll("[filtered]");
    if (cleaned.length() > MAX_CUSTOM_RULE_LENGTH) {
        cleaned = cleaned.substring(0, MAX_CUSTOM_RULE_LENGTH) + "...";
    }
    return cleaned;
}
```

### 4.6 AgentYamlMapper 修改

参照 `skillIds` 的 corrupt data 处理模式：

**导出**：解析 JSON → 输出友好 YAML 结构；解析失败时导出 `behaviorRulesRaw` 原始值。

**导入**：优先读 `behaviorRulesRaw`（corrupt round-trip），其次读 `behaviorRules` Map → 序列化为 JSON。

---

## 5. REST API

### 5.1 新增端点

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/behavior-rules` | 返回所有内置规则定义（含 deprecated 标记） |
| GET | `/api/behavior-rules/presets?executionMode=ask` | 返回指定执行模式的推荐规则 ID 集 |

```json
// GET /api/behavior-rules 响应示例
{
  "version": "1.0",
  "rules": [
    {
      "id": "confirm-destructive-ops",
      "category": "safety",
      "severity": "must",
      "label": "Confirm Destructive Operations",
      "labelZh": "确认破坏性操作",
      "description": "Always ask user confirmation before destructive operations...",
      "descriptionZh": "执行破坏性操作前必须请求用户确认...",
      "deprecated": false,
      "presets": ["autonomous", "cautious", "full"]
    }
  ]
}
```

```json
// GET /api/behavior-rules/presets?executionMode=ask 响应示例
{
  "presetName": "cautious",
  "ruleIds": ["confirm-destructive-ops", "no-secret-in-output", "read-before-edit", ...]
}
```

### 5.2 修改现有端点

- `POST/PUT /api/agents` — RequestBody 增加 `behaviorRules` 字段
- `GET /api/agents/{id}` — 响应增加 `behaviorRules` 字段 + `warnings` 数组

---

## 6. 前端方案

### 6.1 交互设计

**RULES.md Tab**（Agent 编辑 Modal 中的第 5 个 Tab）：

1. **模板选择器**（Radio.Group）：`Autonomous | Cautious | Full | Custom`
   - 选择模板自动重新计算 `enabledBuiltinIds`
   - 手动切换任意规则自动变为 `Custom`
   - `executionMode` 变更时提示重新应用关联模板

2. **分类折叠面板**（Ant Design Collapse，max-height: 500px）：
   - 默认展开第一个分类，其余折叠
   - 面板标题：`"Safety & Security — 3/4 enabled"`
   - 每条规则：`Switch | Title | Severity Badge (must/should/may)`
   - hover 显示 Tooltip 详细描述

3. **自定义规则区域**：
   - 已有规则列表，每条可内联编辑（TextArea，Enter 保存 / Esc 取消 / Shift+Enter 换行）
   - 底部 "Add Rule" 按钮/输入行
   - 最多 10 条，每条 ≤500 字符

4. **Preview Full Prompt**（Modal Footer 按钮）：
   - 全局预览，展示所有 Tab 拼装后的完整 system prompt
   - 只读，等宽字体，语法高亮

### 6.2 组件结构

```
AgentList.tsx                    （仅表格 + 操作按钮）[重构]
 ├── AgentFormModal.tsx          （从 AgentList 抽取）[新增]
 │   ├── Form > Tabs
 │   │   ├── AGENT.md tab
 │   │   ├── SOUL.md tab
 │   │   ├── TOOLS.md tab
 │   │   ├── RULES.md tab       [新增]
 │   │   │   ├── RuleTemplateSelector
 │   │   │   ├── RuleCategoryCollapse
 │   │   │   │   └── RuleToggleItem
 │   │   │   └── CustomRulesEditor
 │   │   └── MEMORY.md tab
 │   └── Footer: [Preview Full Prompt] [Cancel] [OK]
 └── PromptPreviewModal.tsx      [新增]
```

### 6.3 TypeScript 类型

```typescript
export interface BehaviorRule {
  id: string;
  category: 'safety' | 'quality' | 'workflow' | 'communication';
  severity: 'must' | 'should' | 'may';
  label: string;
  labelZh: string;
  description: string;
  descriptionZh: string;
  deprecated: boolean;
  presets: string[];
}

export interface BehaviorRuleConfig {
  templateId: 'autonomous' | 'cautious' | 'full' | 'custom';
  enabledBuiltinIds: string[];
  customRules: string[];
}
```

### 6.4 API 对接

```typescript
// api/index.ts
export const getBuiltinBehaviorRules = () => api.get('/behavior-rules');
export const getBehaviorRulesPreset = (executionMode: 'ask' | 'auto') =>
  api.get('/behavior-rules/presets', { params: { executionMode } });
```

```typescript
// React Query
const { data: builtinRules } = useQuery({
  queryKey: ['behavior-rules', 'builtin'],
  queryFn: () => getBuiltinBehaviorRules().then(r => r.data),
  staleTime: 86_400_000,  // 1 天
});
```

### 6.5 状态管理

自定义 Hook `useBehaviorRules(agentId, executionMode)` 封装：
- 内置规则获取（TanStack Query）
- 本地配置状态管理
- 模板应用/规则切换/自定义规则 CRUD
- 按分类分组 + 启用状态计算

### 6.6 视觉设计

- 基于当前**浅色主题**（`--bg-primary: #FAFAFA`）
- 严格使用 `sf-rules-*` CSS 类前缀 + 现有 CSS 变量
- 不引入硬编码颜色值，确保未来深色主题切换零修改
- Modal 宽度从 640px 提升到 760px
- Severity Badge 颜色语义化：must = accent, should = secondary, may = muted

---

## 7. 安全考量

### 7.1 自定义规则 Prompt Injection

- 自定义规则用 `<user-configured-guidelines>` XML tag 包裹
- 声明性前缀：`"The user has configured the following custom behavior guidelines:"`
- 过滤危险 XML 标签（`<system>`, `<assistant>`, `<tool_use>` 等）
- 长度限制：每条 ≤500 字符，最多 10 条

### 7.2 风险等级

当前为**中等**：自定义规则由 Agent owner 编写（非终端用户），而 owner 本就能直接编辑 systemPrompt，injection 风险不额外增加。

**未来如果引入规则模板共享**（跨用户使用他人创建的规则），风险升至 CRITICAL，届时必须增加内容审核机制。

---

## 8. 实施顺序

| 阶段 | 内容 | 依赖 | 预计工作量 |
|------|------|------|-----------|
| P1 | Flyway V8 migration + AgentEntity 字段 + updateAgent() 修复 | 无 | 0.5h |
| P2 | behavior-rules.json + BehaviorRuleRegistry + BehaviorRulesConfig | P1 | 2h |
| P3 | AgentDefinition 字段 + AgentService.toAgentDefinition() 解析 | P2 | 1h |
| P4 | SystemPromptBuilder 注入（含语言检测 + 安全防护） | P3 | 1.5h |
| P5 | AgentYamlMapper round-trip + corrupt data 防御 + 测试 | P1 | 1h |
| P6 | BehaviorRuleController REST API | P2 | 1h |
| P7 | 前端：AgentFormModal 抽取 + useBehaviorRules Hook | P6 | 3h |
| P8 | 前端：BehaviorRulesEditor + CustomRulesEditor + CSS | P7 | 3h |
| P9 | 前端：PromptPreviewModal + executionMode 联动 | P8 | 2h |
| P10 | 端到端测试 + 视觉 QA | P9 | 2h |

**总计：~17h（约 2-3 天）**

P1-P4 为后端核心路径（顺序执行），P5/P6 可与 P4 并行，P7-P9 为前端路径。

---

## 9. 新增/修改文件清单

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `skillforge-core/src/main/resources/behavior-rules.json` | 内置规则定义 |
| `skillforge-core/.../BehaviorRuleDefinition.java` | 规则 record |
| `skillforge-server/.../BehaviorRuleRegistry.java` | 规则注册表服务 |
| `skillforge-server/.../BehaviorRuleController.java` | REST API |
| `skillforge-server/.../db/migration/V8__agent_behavior_rules.sql` | Flyway migration |
| `skillforge-dashboard/src/types/behaviorRules.ts` | TypeScript 类型 |
| `skillforge-dashboard/src/constants/behaviorRules.ts` | UI 元数据 |
| `skillforge-dashboard/src/hooks/useBehaviorRules.ts` | 状态管理 Hook |
| `skillforge-dashboard/src/components/AgentFormModal.tsx` | Agent 编辑 Modal（从 AgentList 抽取） |
| `skillforge-dashboard/src/components/BehaviorRulesEditor.tsx` | 规则编辑器 |
| `skillforge-dashboard/src/components/CustomRulesEditor.tsx` | 自定义规则编辑器 |
| `skillforge-dashboard/src/components/PromptPreviewModal.tsx` | 全局 Prompt 预览 |

### 修改文件

| 文件路径 | 变更 |
|---------|------|
| `AgentEntity.java` | 新增 `behaviorRules` 字段 |
| `AgentDefinition.java` | 新增 `BehaviorRulesConfig` + `resolvedBehaviorRules` |
| `AgentService.java` | `updateAgent()` 修复 + `toAgentDefinition()` 解析 |
| `SystemPromptBuilder.java` | 注入行为规则（新位置 + 安全防护） |
| `AgentYamlMapper.java` | behaviorRules round-trip |
| `AgentList.tsx` | 抽取 Modal 到 AgentFormModal |
| `api/index.ts` | 新增 behavior-rules API |
| `index.css` | 新增 `sf-rules-*` 样式 |

---

## 附录：Reviewer 质询与采纳情况

### 后端 Reviewer 质询（8 条）

| # | 问题 | 严重度 | 决策 | 说明 |
|---|------|--------|------|------|
| 1 | updateAgent() 遗漏 behaviorRules | CRITICAL | 采纳 | 更新时数据会丢失 |
| 2 | JSON 解析不应放 SystemPromptBuilder | HIGH | 采纳 | 移到 AgentService.toAgentDefinition() |
| 3 | 规则 ID 无稳定性策略 | HIGH | 采纳 | ID 不可删除契约 + deprecated 机制 |
| 4 | promptText 缺中文版本 | MEDIUM | 采纳 | 增加 promptTextZh + 语言检测 |
| 5 | 自定义规则 prompt injection | MEDIUM | 采纳 | XML tag 包裹 + 清洗 + 长度限制 |
| 6 | system prompt 插入位置 | MEDIUM | 采纳 | 移到 Available Skills 之后（recency bias） |
| 7 | AgentYamlMapper corrupt data | LOW | 采纳 | 参照 skillIds 模式 |
| 8 | 预留规则模板扩展口 | LOW | 部分采纳 | 定义 BehaviorRulesConfig，暂不实现多源组装 |

### 前端 Reviewer 质询（8 条）

| # | 问题 | 严重度 | 决策 | 说明 |
|---|------|--------|------|------|
| 1 | 内置规则硬编码 vs API | P0 | 采纳 | 后端 API 为唯一事实来源 |
| 2 | 默认全选不合适 | P0 | 采纳 | 改为 executionMode 联动模板 |
| 3 | AgentList.tsx 需要拆分 | P1 | 采纳 | 抽取 AgentFormModal.tsx |
| 4 | 分类全展开空间不够 | P1 | 采纳 | 默认只展开第一个 + 计数器 |
| 5 | CSS 必须用变量 | P1 | 采纳 | `sf-rules-*` 前缀 + CSS 变量 |
| 6 | 砍掉局部预览 | P2 | 采纳 | 改为 Modal 级 Preview Full Prompt |
| 7 | 自定义规则编辑细节 | P2 | 采纳 | TextArea + Enter/Esc |
| 8 | 主题适配 | P2 | 采纳 | 基于浅色主题设计，变量化确保可切换 |

---

*本方案由 4-agent 设计流水线生成：2 个 Plan Agent（后端架构 + 前端产品）并行出方案 → 2 个 Reviewer Agent（技术审查 + 产品审查）并行质询 → Plan Agent 修订采纳 → 最终整合。*

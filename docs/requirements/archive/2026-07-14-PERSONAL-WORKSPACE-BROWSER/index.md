# PERSONAL-WORKSPACE-BROWSER - Chat 右侧个人工作空间

> 创建：2026-07-14
> 状态：done（V1 已实现并完成本批 Full 验证；后续能力另立切片）
> 模式：Full
> 风险：Full（新增文件读取 API、授权根目录、路径安全边界和 Chat 右栏交互）

## 摘要

在现有 Chat 右侧栏增加 `Workspace`，同时提供两个互不混淆的视图：

- `This session`：展示本次会话引用、上传和 Agent 生成的附件。
- `MySpace`：只读浏览用户明确授权的文件根目录，当前最大 scope 为
  `/Users/youren/myspace`，因此可从 `myspace` 根部进入 `skillforge` 等子目录。

Workspace 是个人助理的资料与产物层，不是第二套聊天记录，也不会把浏览过的文件自动塞进模型上下文。

## 已确认决策

- 授权根由服务端配置 `SKILLFORGE_WORKSPACE_ROOT` 决定，前端不接收任意绝对路径。
- API 只接受相对路径，所有解析结果必须仍位于授权根内。
- V1 只读：列目录与安全文本预览，不提供写入、删除、重命名或上传到任意目录。
- 文件浏览不等于“加入对话”；未来加入上下文必须是显式用户动作，并单独记录来源。
- 每个嵌套 Git 仓库应用自己的 `.gitignore` 规则；隐藏 Git 元数据、构建产物、环境配置和敏感文件。
- 当前会话附件继续走受管附件服务与所有权校验，不直接暴露本地绝对路径。
- Workspace 与现有 `Context / Activity / SubAgent / Team` 同级，暂不移除 Team。

## V1 验收

1. Chat 右栏可在 `This session` 与 `MySpace` 间切换。
2. `MySpace` 根显示配置目录的 label，并能进入任意允许的子目录。
3. 目录支持面包屑、当前目录过滤、条目计数和返回上级。
4. Markdown 使用现有渲染器预览；普通文本使用等宽文本；二进制和超限内容安全降级。
5. `..`、绝对路径、符号链接越界、Git metadata、ignored 文件和敏感名称均不可读取。
6. 响应使用 `no-store`，错误返回稳定的错误码而非本地路径或异常正文。
7. Workspace 不可用时不影响 Chat 主流程。

## 非目标

- V1 不支持文件编辑、目录管理、全文搜索和版本控制操作。
- V1 不自动索引整个 MySpace，不生成 embedding，也不把文件内容写入 memory。
- V1 不把本地目录直接暴露给 iOS App。
- V1 不提供“授权任意目录”的 Dashboard 配置 UI；先由部署环境变量控制。

## 文档

1. [mrd.md](mrd.md)：用户诉求与产品边界。
2. [prd.md](prd.md)：工作流和验收标准。
3. [tech-design.md](tech-design.md)：API、安全约束、前端结构与验证。

## 当前实现

- 后端：`WorkspaceProperties`、`WorkspaceFileService`、`WorkspaceGitIgnorePolicy`、
  `WorkspaceController` 和稳定 DTO。
- 前端：Chat `RightRail` 内的 `WorkspaceTab`、Workspace API client、样式与组件测试。
- 配置：`skillforge.workspace.root` 从 `SKILLFORGE_WORKSPACE_ROOT` 读取；为空时 Workspace 明确不可用。
- 当前机器：授权根配置为 `/Users/youren/myspace`，根 label 为 `myspace`。

## 交付验证（2026-07-14）

- 后端全量：`mvn -pl skillforge-core,skillforge-server -am test`，3302 tests，0 failures，0 errors。
- Workspace 前端与 API：相关 Vitest 纳入本批 9 个文件、63 tests，全部通过；`tsc --noEmit` 通过；
  Vite production build 通过。
- 路径安全：覆盖绝对路径、`..`、符号链接、ignored/sensitive 文件、预览上限、稳定错误码与
  `Cache-Control: no-store`。
- 浏览器自动化运行时本轮不可用；关键交互由组件测试覆盖，真实页面视觉回归保留为后续人工验收项。

## 后续切片

- 显式“加入本次对话”及可审计的 context 引用。
- 文件搜索、最近文件和会话产物反向定位。
- 独立授权管理 UI、多根目录和更细粒度权限。
- 文件写入/编辑必须另立安全需求，不在 V1 上直接放开。

# Patterns

> 跨语言通用设计模式基线。语言特化内容见 [java/patterns.md](../java/patterns.md) / [typescript/patterns.md](../typescript/patterns.md) / [web/patterns.md](../web/patterns.md)，各自以 `> extends` 引用本文件。

## 关注点分离（Separation of Concerns）

- **Controller / Handler 层**：只做协议适配（解析请求、组装响应、错误映射），不放业务逻辑
- **Service 层**：业务逻辑唯一归属地，编排领域对象和数据访问
- **Repository / Data 层**：封装存储细节，对上层暴露接口而非实现

## Repository 模式

数据访问藏在接口后面，调用方不感知是 JPA / JDBC / 内存实现：

```
interface XxxRepository {
  findById(id) -> Optional<Xxx>
  save(xxx) -> Xxx
  deleteById(id)
}
```

便于测试时替换内存实现、便于换存储引擎。

## 依赖注入（构造器注入）

始终用构造器注入，不用字段注入 —— 可测试、依赖显式、字段可 `final`。

## DTO 边界映射

领域对象不直接穿透到 API 边界；在 Service / Controller 边界做 DTO 映射，避免持久化模型泄漏到外部契约。

## 组合优于继承

优先用组合 / 委托表达复用，深继承链难维护。需要"封闭类型集合"时用各语言的 sealed / 字面量 union。

## 不可变优先

返回新对象而非就地修改（详见 [coding-style.md](coding-style.md) Immutability 节）。共享可变状态是并发 bug 的主要来源。

## API 响应一致性

同一服务的响应外形（envelope vs 裸数据）要在前后端契约上统一。**注意**：信封模式（`{success, data, error}`）和裸返回（`List` / 单对象）混用会导致前端反序列化崩溃，新增端点前确认前后端对齐的 outer shape。

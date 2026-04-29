# Java Rules

Read this before touching `*.java`, JPA entities, repositories, services, Flyway migrations, or LLM provider code.

## Known Footguns

- Jackson `ObjectMapper` must register `JavaTimeModule` and disable timestamp date output. Prefer the Spring-managed `ObjectMapper` bean over `new ObjectMapper()`.
- New persisted time fields use `Instant`. `LocalDateTime` is legacy and should not spread.
- `@Transactional` belongs on public Service-layer methods. It does not work on private methods through Spring AOP.
- LLM provider `chat()` may retry `SocketTimeoutException`; `chatStream()` must not retry because streamed deltas may be duplicated.
- Jackson discriminated unions using `@JsonTypeInfo` and `@JsonSubTypes` need no-arg constructors and should tolerate unknown properties for compatibility.

## Style And Architecture

- Use constructor injection. Never use field injection with `@Autowired`.
- JPA entities need a no-arg constructor. Tables follow the `t_` prefix convention.
- Use `@EntityListeners(AuditingEntityListener.class)` with `@CreatedDate` and `@LastModifiedDate` for audit fields.
- Store enum/status values as strings with bounded column length.
- Use `@Column(columnDefinition = "TEXT")` or `CLOB` for large text fields to avoid 255-character truncation.
- Put `@Transactional(readOnly = true)` on read Service methods and `@Transactional` on multi-repository writes.
- Tool implementations use `com.skillforge.core.skill.Tool`; names are globally unique PascalCase and descriptions must be useful to the LLM.
- Register Java tools in `SkillForgeConfig`; `SystemSkillLoader` is for zip package skills, not Java tools.
- New LLM providers implement `com.skillforge.core.llm.LlmProvider` and register in `LlmProviderFactory`.
- Prefer records for DTOs/value types where compatible with project conventions.
- Repository finder methods return `Optional<T>`. Use `orElseThrow`, `map`, or `flatMap`; do not use `Optional` as a field or parameter.
- Prefer unchecked, domain-specific exceptions and centralized controller/WebSocket error handling.
- Use SLF4J loggers. Avoid broad `catch (Exception)` except at top-level boundaries.

## Naming

- Classes/interfaces: `PascalCase`
- Methods/fields/locals: `camelCase`
- Constants: `SCREAMING_SNAKE_CASE`
- Packages: lowercase `com.skillforge.<module>.<layer>`
- Entities: `*Entity`
- Repositories: `*Repository`
- Request/response DTOs: `*Request`, `*Response`

## Tests

- Java tests use JUnit 5, Mockito, AssertJ, and Testcontainers where real integration services are needed.
- Mirror `src/main/java` package structure in `src/test/java`.
- Java test methods use `methodName_scenario_expectedBehavior()` plus `@DisplayName` when useful.

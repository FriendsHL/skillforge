# Java Rules

Read this before touching `*.java`, JPA entities, repositories, services, Flyway migrations, or LLM provider code.

## Known Footguns

- Jackson `ObjectMapper` must register `JavaTimeModule` and disable timestamp date output. Prefer the Spring-managed `ObjectMapper` bean over `new ObjectMapper()`.
- New persisted time fields use `Instant`. `LocalDateTime` is legacy and should not spread.
- `@Transactional` belongs on public Service-layer methods. It does not work on private methods through Spring AOP.
- LLM provider `chat()` may retry `SocketTimeoutException`; `chatStream()` must not retry because streamed deltas may be duplicated.
- Jackson discriminated unions using `@JsonTypeInfo` and `@JsonSubTypes` need no-arg constructors and should tolerate unknown properties for compatibility.
- Persisted `Message` JSON and engine in-memory `Message` JSON for the same logical message must be byte-identical. Read `persistence-shape-invariant.md` before touching message construction or rewrite paths.
- New `t_session_message` identity/association columns must extend rewrite preserve logic. Read `identity-column-on-rewrite.md`.
- FE/BE contracts must verify both inner DTO fields and outer response envelope shape. Controller `ResponseEntity.ok(list)` and `ResponseEntity.ok(Map.of("items", list, ...))` require different frontend types and mocks.
- LLM provider and model-family changes require `llm-provider-compat.md`; generic Java review is not enough.
- Compact subsystem changes require `compact-review.md`; generic Java review is not enough.

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
- Controllers should delegate business logic to service methods quickly; repositories do not call services.
- Services with multi-repository writes or state transitions need explicit public `@Transactional` methods.
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
- DTO and wire-shape changes need JSON roundtrip tests or live curl evidence when practical.
- `Message` / `ContentBlock` JSON field changes need serialize -> deserialize -> serialize byte-stability tests.

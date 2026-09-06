# Java Design Review Rules

Read this for new services/controllers/repositories, structural refactors,
cross-module abstractions, or explicit design-review requests.

This is not a replacement for `java.md`; it covers open-ended design judgment.

## Core Questions

- Will intent be clear five months from now?
- Does adding the next likely requirement require edits in many places?
- Can key dependencies be replaced or mocked in tests?
- Does the abstraction leak lower-layer framework details?

## Design Dimensions

- SRP: inspect unrelated responsibilities and coupled change paths. Size is a
  diagnostic signal, not a blocker; justify structural findings by concrete impact.
- DIP: external IO clients, clocks, random IDs, and side-effect boundaries should
  be injected when business logic depends on them.
- OCP: repeated provider/type if-else chains in multiple locations may need a
  strategy or registry. A single local if-else does not.
- Leaky abstraction: controllers/services should not expose SQL, Hibernate,
  OkHttp, Jackson, or servlet details unless the layer genuinely owns them.
- Java 17: use records for DTO/value types where compatible, pattern matching,
  `Stream.toList()`, and text blocks when they improve clarity.
- Testability: business time logic should use `Clock`; external clients should
  be mockable.
- Naming: avoid vague `process`, `handle`, `manager`, `helper`, and `util` names
  unless the framework or role justifies them.
- Domain model: SkillForge mostly uses anemic JPA entities plus service logic;
  do not force DDD. Consider value objects only for repeated, meaningful domain
  concepts.
- Layers: controllers delegate to services; repositories do not call services;
  service dependency cycles are design warnings.

## Avoid Over-Design

Do not recommend patterns for their own sake. Every design finding must explain
the future maintenance risk and the smallest useful improvement.

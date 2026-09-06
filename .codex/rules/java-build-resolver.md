# Java Build Failure Checklist

Use `systematic-debugging.md` for reproduction, hypothesis, minimal fix, and
verification. This file adds only Java/Maven-specific checks.

- Locate the first causal error in the failing module, not downstream failures.
- `cannot find symbol` / missing package: inspect imports, module dependencies,
  generated sources, and recent API changes before adding dependencies.
- Incompatible types / wrong arguments: inspect the signature and callers; do not
  hide a real mismatch with casts.
- Annotation processing: inspect Lombok/MapStruct/Spring generation setup before
  changing business logic.
- Distinguish compilation, test assertion, environment, and external dependency
  failures. Fix the relevant cause without unrelated refactoring or suppressing
  a real error to obtain a green build.

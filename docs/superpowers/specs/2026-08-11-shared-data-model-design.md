# Shared Data Model Design

## Goal

Create a `shared-data-model` JVM module containing the review input model currently declared in `review-agent/src/Main.kt`. Keep the shared model independent of Spring so other modules can consume it without Spring Boot configuration annotations or dependencies.

## Scope

### Files

- `project.yaml`
  - Register the new `shared-data-model` module.
- `shared-data-model/module.yaml`
  - Define the module as a dependency-free JVM library.
- `shared-data-model/src/com/example/Review.kt`
  - Add plain `Review` and `Repository` data classes.
- `review-agent/module.yaml`
  - Add a dependency on `shared-data-model`.
- `review-agent/src/Main.kt`
  - Remove local `Review` and misspelled `Respository` declarations.
  - Bind the shared `Review` bean from the `review.*` environment properties.
- `review-agent/test/com/example/ReviewConfigurationTest.kt`
  - Verify review properties bind into the shared model.
- `docs/superpowers/specs/2026-08-11-shared-data-model-design.md`
  - Record this design.

`ReviewResult` and `ReviewComment` remain local to `review-agent` because they describe review-agent output rather than shared input configuration.

## Shared Model

Use package `com.example` to preserve the current public type name and minimize consumer changes:

```kotlin
data class Repository(
    val url: String,
)

data class Review(
    val repository: Repository,
    val pr: String,
)
```

Neither class receives Spring annotations. The module has no Spring dependency.

`Respository` is corrected to `Repository`; this is a source-compatible change only for the current local declaration because no external shared API exists yet.

## Configuration Binding

`review-agent` owns Spring configuration binding. Add a configuration class or equivalent bean definition that uses Spring Boot's `Binder` against the application `Environment`:

```kotlin
@Bean
fun review(environment: Environment): Review =
    Binder.get(environment)
        .bind("review", Bindable.of(Review::class.java))
        .orElseThrow { IllegalStateException("Missing review configuration") }
```

The actual implementation must use valid Kotlin syntax and existing project conventions. Binding remains under the `review-agent` module, so the shared module stays framework-neutral and the `review.*` prefix is not embedded in the data model.

Existing consumers continue injecting `Review` from the Spring context. Existing YAML configuration remains unchanged:

```yaml
review:
  repository:
    url: https://github.com/frederikpietzko/ebfs-jpa.git
  pr: 1
```

## Module Wiring

Register `shared-data-model` in the root module list, preserving the Kotlin CLI's alphabetical module ordering. Add the local sibling dependency as `../shared-data-model`. `review-agent` retains all current Spring and Spring AI dependencies.

The shared library must compile before `review-agent`, and `review-agent` must resolve `Review` from the shared module rather than from its own source set.

## Error Handling

- Missing or malformed `review.*` configuration fails application startup with an explicit binding error.
- No fallback values are introduced.
- The shared data model contains no environment access, Spring lifecycle code, or logging.

## Testing and Validation

Run through the Kotlin CLI after implementation:

1. `./kotlin check`
2. Run the existing `review-agent` Spring context test.
3. Add a focused test that supplies `review.repository.url` and `review.pr`, creates the binding configuration, and asserts the resulting `Review` values.
4. Run `git diff --check`.
5. Confirm `Review` has no Spring imports or annotations and `review-agent` has no duplicate `Review`/`Respository` declaration.

## Alternatives Considered

### Plain shared model with binding owned by `review-agent` — selected

Keeps the data module reusable and framework-neutral. Configuration ownership stays with the application that understands the `review` prefix. Manual `Binder` registration avoids placing `@ConfigurationProperties` on shared classes.

### Annotated shared `Review`

Simpler Spring Boot registration, but couples every consumer to Spring Boot and embeds application configuration concerns in a shared model. Rejected because the shared classes must remain unannotated.

### Duplicate local model plus mapping

Avoids module dependency but duplicates the contract and adds mapping code. Rejected because the requested shared module should be the single source of truth.

## Design Decisions

- Name module `shared-data-model`.
- Keep `Review` and `Repository` as plain Kotlin data classes.
- Correct `Respository` spelling while moving the type.
- Bind configuration manually in `review-agent` with Spring Boot `Binder`.
- Keep review output types local to `review-agent`.
- Preserve current package and YAML property names.

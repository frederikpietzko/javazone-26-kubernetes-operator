# Shared Data Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the review input model into a Spring-free `shared-data-model` JVM library and bind it inside
`review-agent` without `@ConfigurationProperties` on shared classes.

**Architecture:** `shared-data-model` publishes plain `com.example.Repository` and `com.example.Review` data classes.
`review-agent` depends on that module and exposes a Spring bean created by `Binder` from the `review` environment
prefix. Review output types remain in `review-agent`.

**Tech Stack:** Kotlin, Kotlin Toolchain YAML modules, Spring Boot `Binder`, Spring Test, Kotlin Test.

## Global Constraints

- Shared module name is `shared-data-model`.
- Shared model classes have no Spring imports or annotations.
- Shared module product type is `jvm/lib`.
- `review-agent` depends on the local module using `../shared-data-model`, the relative sibling-module syntax accepted
  by this project’s Kotlin CLI.
- Configuration prefix remains `review`.
- `Respository` is renamed to `Repository`.
- `ReviewResultCR` and `ReviewComment` remain in `review-agent`.
- Use `./kotlin` for project build and test commands.
- Preserve unrelated existing modification `.idea/workspace.xml`.

---

## File Map

- Create: `shared-data-model/module.yaml` — dependency-free JVM library declaration.
- Create: `shared-data-model/src/com/example/Review.kt` — shared `Repository` and `Review` data classes.
- Create: `shared-data-model/test/com/example/ReviewTest.kt` — direct model construction test.
- Modify: `project.yaml` — include `shared-data-model` in the project module list.
- Modify: `review-agent/module.yaml` — add `//shared-data-model` dependency.
- Create: `../../../review-agent/src/com/example/reviewer/ReviewConfiguration.kt` — bind `review.*` into the shared
  `Review` bean.
- Create: `review-agent/test/com/example/ReviewConfigurationTest.kt` — verify property binding and missing-property
  failure.
- Modify: `../../../review-agent/src/com/example/Main.kt` — remove local configuration model and
  configuration-properties scanning.

## Task 1: Add shared model module

**Files:**

- Create: `shared-data-model/module.yaml`
- Create: `shared-data-model/src/com/example/Review.kt`
- Create: `shared-data-model/test/com/example/ReviewTest.kt`
- Modify: `project.yaml`

**Interfaces:**

- Produces `com.example.Repository(val url: String)`.
- Produces `com.example.Review(val repository: Repository, val pr: String)`.
- Exposes no Spring or Spring Boot dependency.

- [ ] **Step 1: Register the module and test dependency**

Create `shared-data-model/module.yaml`:

```yaml
product: jvm/lib

test-dependencies:
  - $kotlin.test.junit5
```

Add the module to `project.yaml`:

```yaml
modules:
  - build-config
  - operator
  - review-agent
  - shared-data-model
```

Keep the existing `plugins` block unchanged.

- [ ] **Step 2: Write the failing shared-model test**

Create `shared-data-model/test/com/example/ReviewTest.kt`:

```kotlin
package com.example

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewTest {
    @Test
    fun `review stores repository and pull request`() {
        val review = Review(
            repository = Repository("https://github.com/example/project.git"),
            pr = "42",
        )

        assertEquals("https://github.com/example/project.git", review.repository.url)
        assertEquals("42", review.pr)
    }
}
```

- [ ] **Step 3: Run the test and verify the expected failure**

Run:

```bash
./kotlin test --include-module=shared-data-model --include-classes=com.example.ReviewTest
```

Expected: compilation fails because `Review` and `Repository` do not exist yet. If the failure is caused by YAML/module
configuration instead, fix that configuration and rerun until the failure specifically identifies the missing model
declarations.

- [ ] **Step 4: Add the minimal shared model implementation**

Create `shared-data-model/src/com/example/Review.kt`:

```kotlin
package com.example

data class Repository(
    val url: String,
)

data class Review(
    val repository: Repository,
    val pr: String,
)
```

Do not add `@ConfigurationProperties`, `@NestedConfigurationProperty`, `@Component`, or any other Spring
annotation/import.

- [ ] **Step 5: Run the shared-model test and inspect dependencies**

Run:

```bash
./kotlin test --include-module=shared-data-model --include-classes=com.example.ReviewTest
./kotlin show dependencies --module=shared-data-model
```

Expected:

- `ReviewTest` passes.
- The dependency graph contains Kotlin test dependencies only in the test scope and no Spring dependency.

- [ ] **Step 6: Commit the shared module**

```bash
git add project.yaml shared-data-model
git commit -m "feat: add shared review data model"
```

## Task 2: Bind shared model in review-agent

**Files:**

- Modify: `review-agent/module.yaml`
- Create: `../../../review-agent/src/com/example/reviewer/ReviewConfiguration.kt`
- Create: `review-agent/test/com/example/ReviewConfigurationTest.kt`
- Modify: `../../../review-agent/src/com/example/Main.kt`

**Interfaces:**

- Consumes `com.example.Review` and `com.example.Repository` from `../shared-data-model`.
- Produces Spring bean method `ReviewConfiguration.review(environment: Environment): Review`.
- Binds `review.repository.url` and `review.pr` from the Spring `Environment`.

- [ ] **Step 1: Add the local module dependency**

Add the shared sibling module to `review-agent/module.yaml` using the relative path syntax accepted by this project’s
Kotlin CLI:

```yaml
dependencies:
  - ../shared-data-model
  - $spring.boot.starter
  - $kotlin.reflect
  - bom: org.springframework.ai:spring-ai-bom:2.0.0
  - $libs.spring.ai.openai.starter
  - $libs.jackson.module.kotlin
  - $libs.spring.ai.community.agent.utils
```

Preserve all existing dependencies and their order relative to one another; place `//shared-data-model` first.

- [ ] **Step 2: Write the failing configuration-binding tests**

Create `review-agent/test/com/example/ReviewConfigurationTest.kt`:

```kotlin
package com.example

import org.springframework.core.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReviewConfigurationTest {
    @Test
    fun `binds review properties into shared model`() {
        val environment = MockEnvironment()
            .withProperty("review.repository.url", "https://github.com/example/project.git")
            .withProperty("review.pr", "42")

        val review = ReviewConfiguration().review(environment)

        assertEquals("https://github.com/example/project.git", review.repository.url)
        assertEquals("42", review.pr)
    }

    @Test
    fun `fails when review properties are missing`() {
        assertFailsWith<IllegalStateException> {
            ReviewConfiguration().review(MockEnvironment())
        }
    }
}
```

- [ ] **Step 3: Run the focused tests and verify the expected failure**

Run:

```bash
./kotlin test --include-module=review-agent --include-classes=com.example.ReviewConfigurationTest
```

Expected: compilation fails because `ReviewConfiguration` does not exist yet. The shared `Review` type must resolve from
`//shared-data-model`; unresolved `Review` indicates the module dependency or project registration is wrong and must be
fixed before continuing.

- [ ] **Step 4: Add minimal manual Binder configuration**

Create `../../../review-agent/src/com/example/reviewer/ReviewConfiguration.kt`:

```kotlin
package com.example

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class ReviewConfiguration {
    @Bean
    fun review(environment: Environment): Review =
        Binder.get(environment)
            .bind("review", Bindable.of(Review::class.java))
            .orElseThrow {
                IllegalStateException("Missing required review configuration")
            }
}
```

- [ ] **Step 5: Remove the local model and scanning annotations**

In `../../../review-agent/src/com/example/Main.kt`:

- Remove imports for `ConfigurationProperties`, `ConfigurationPropertiesScan`, and `NestedConfigurationProperty`.
- Change the application declaration from:

```kotlin
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(Reviewer::class)
class Application
```

to:

```kotlin
@SpringBootApplication
@Import(Reviewer::class)
class Application
```

- Delete the local `Respository` and `Review` declarations.
- Leave `Reviewer`, `ReviewComment`, and `ReviewResultCR` unchanged.

- [ ] **Step 6: Run focused tests and the application context test**

Run:

```bash
./kotlin test --include-module=review-agent --include-classes=com.example.ReviewConfigurationTest
./kotlin test --include-module=operator --include-classes=com.example.ExampleTest
```

Expected: both test commands pass. `ReviewConfigurationTest` proves that the shared immutable data model receives values
from the `review` prefix. `operator`'s `ExampleTest` remains a regression check for the existing Spring context.

- [ ] **Step 7: Commit application wiring**

```bash
git add review-agent/module.yaml review-agent/src/Main.kt review-agent/src/com/example/ReviewConfiguration.kt review-agent/test/com/example/ReviewConfigurationTest.kt
git commit -m "refactor(review-agent): bind shared review model"
```

## Task 3: Validate project integration

**Files:**

- No source changes expected.

**Interfaces:**

- `shared-data-model` appears in the module graph.
- `review-agent` resolves `Review` from the shared module.
- Shared model remains Spring-free.

- [ ] **Step 1: Run all project checks**

Run:

```bash
./kotlin check
```

Expected: exit code `0` with no compilation or test failures.

- [ ] **Step 2: Verify module graph**

Run:

```bash
./kotlin show modules
./kotlin show dependencies --module=review-agent
```

Expected:

- `shared-data-model` is listed as `jvm/lib`.
- `review-agent` dependency output includes `shared-data-model`.

- [ ] **Step 3: Verify source separation**

Run:

```bash
rg -n "ConfigurationProperties|NestedConfigurationProperty|Respository|data class Review|data class Repository" shared-data-model review-agent/src
```

Expected:

- `shared-data-model` has no Spring annotation/import matches.
- `Respository` has no matches.
- `data class Review` and `data class Repository` appear only under `shared-data-model`.
- Spring binding references appear only in `review-agent` configuration code.

- [ ] **Step 4: Check final diff and repository state**

Run:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected:

- `git diff --check` succeeds.
- Only the pre-existing `.idea/workspace.xml` modification remains uncommitted, unless the implementation commits have
  already made the working tree clean.
- Implementation commits are visible in the recent history.

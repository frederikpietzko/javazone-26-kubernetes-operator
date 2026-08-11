# Review Agent Custom Resource Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `review-agent` create and update a namespaced `ReviewResult` CR through the full `InProgress`/
`Completed`/`Failed` review lifecycle.

**Architecture:** Keep Spring AI output separate from the Fabric8 CR model. A pure mapper converts local review data
into `ReviewResultSpec`; an injected publisher owns typed Fabric8 CRUD and status-subresource updates; a small workflow
coordinates start, review, success, failure persistence, and rethrow. `ReviewConfiguration.kt` binds the CR target from
`application-review.yaml`.

**Tech Stack:** Kotlin Toolchain CLI, Spring Boot 4.1.0, Spring AI 2.0.0, Fabric8 Kubernetes Client 7.8.0, Fabric8 CRD
Generator v2, kind, `.kubeconfig`.

## Global Constraints

- Use `./kotlin` for project builds, tests, task execution, and task inspection.
- Use direct Fabric8 Kubernetes client; do not add Java Operator SDK to `review-agent`.
- Keep local review configuration in `review-agent/resources/application-review.yaml`.
- Keep `.kubeconfig` outside application YAML; local execution uses `KUBECONFIG="$PWD/.kubeconfig"`.
- Keep Spring AI structured output separate from `ReviewResultCR`.
- Treat `crds/src/com/example/ReviewResultCR.kt` as CRD source of truth.
- Generate CRD YAML with `./kotlin task :crds:generateCrds@build-config`; do not manually edit generated YAML.
- Catch review workflow exceptions, best-effort persist `Failed` and `status.error`, then rethrow the original
  exception.
- Do not add automated tests that invoke a real LLM or require a live Kubernetes cluster.
- Preserve unrelated existing `.idea/workspace.xml` modification.
- Manually run the review agent with both `local` and `review` profiles against the kind cluster and inspect the
  resulting CR.

---

## File Map

- Modify `libs.versions.toml`: add explicit Fabric8 Kubernetes client alias at version `7.8.0`.
- Modify `review-agent/module.yaml`: depend on the Fabric8 client alias.
- Modify `review-agent/resources/application-review.yaml`: add configured CR namespace/name.
- Modify `../../../review-agent/src/com/example/reviewer/ReviewConfiguration.kt`: bind typed CR target configuration.
- Modify `review-agent/src/com/example/Main.kt`: add local Spring AI result data classes and wire workflow/publisher.
- Create `../../../review-agent/src/com/example/reviewer/ReviewResultMapper.kt`: map local review output into CR spec.
- Create `../../../review-agent/src/com/example/reviewer/ReviewResultPublisher.kt`: define publisher boundary and
  Fabric8 implementation.
- Create `../../../review-agent/src/com/example/reviewer/ReviewWorkflow.kt`: coordinate lifecycle and failure
  propagation.
- Modify `crds/src/com/example/ReviewResultCR.kt`: add `ReviewResultStatus.error`.
- Modify `review-agent/test/com/example/ReviewConfigurationTest.kt`: test target binding and missing target values.
- Create `review-agent/test/com/example/ReviewResultMapperTest.kt`: test result-to-spec mapping.
- Create `review-agent/test/com/example/ReviewWorkflowTest.kt`: test success, failure persistence, and rethrow with fake
  publisher.
- Generated/updated `k8s/crds/reviewresults.example.com-v1.yml`: produced only by the Kotlin generation task.

---

### Task 1: Add failing configuration tests and bind CR target

**Files:**

- Modify: `review-agent/test/com/example/ReviewConfigurationTest.kt`
- Modify: `../../../review-agent/src/com/example/reviewer/ReviewConfiguration.kt`
- Modify: `review-agent/resources/application-review.yaml`

**Interfaces:**

- Produce `data class ReviewResultTarget(val namespace: String, val name: String)` in package `com.example`.
- Produce `ReviewConfiguration.reviewResultTarget(environment): ReviewResultTarget`.
- Preserve existing `ReviewConfiguration.review(environment): Review` behavior.

- [ ] **Step 1: Write failing target-binding test**

Add test data and assertions to the existing `ReviewConfigurationTest`:

```kotlin
@Test
fun `binds review result target`() {
    val environment = StandardEnvironment().apply {
        propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf(
                    "review.repository.url" to "https://github.com/example/project.git",
                    "review.pr" to "42",
                    "review.kubernetes.namespace" to "reviews",
                    "review.kubernetes.name" to "review-result-42",
                ),
            ),
        )
    }

    val target = ReviewConfiguration().reviewResultTarget(environment)

    assertEquals("reviews", target.namespace)
    assertEquals("review-result-42", target.name)
}
```

Add a missing-target test that expects `IllegalStateException` from `reviewResultTarget(StandardEnvironment())`.

- [ ] **Step 2: Run test and verify expected failure**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewConfigurationTest
```

Expected: failure because `ReviewResultTarget` and `reviewResultTarget` do not exist.

- [ ] **Step 3: Implement typed target binding**

Add the target data class and bean-style method in `ReviewConfiguration.kt`:

```kotlin
data class ReviewResultTarget(
    val namespace: String,
    val name: String,
)

fun reviewResultTarget(environment: Environment): ReviewResultTarget =
    Binder.get(environment)
        .bind("review.kubernetes", Bindable.of(ReviewResultTarget::class.java))
        .orElseThrow {
            IllegalStateException("Missing required review Kubernetes configuration")
        }
```

Keep existing review binding unchanged. Add the target block to `application-review.yaml`:

```yaml
  kubernetes:
    namespace: default
    name: review-result
```

- [ ] **Step 4: Run tests and verify green**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewConfigurationTest
```

Expected: all `ReviewConfigurationTest` tests pass.

- [ ] **Step 5: Commit configuration**

```bash
git add review-agent/src/com/example/ReviewConfiguration.kt review-agent/test/com/example/ReviewConfigurationTest.kt review-agent/resources/application-review.yaml
git commit -m "feat(review-agent): bind review result target"
```

---

### Task 2: Add CR status error and Fabric8 dependency

**Files:**

- Modify: `crds/src/com/example/ReviewResultCR.kt`
- Modify: `libs.versions.toml`
- Modify: `review-agent/module.yaml`

**Interfaces:**

- `ReviewResultStatus.status` remains nullable string.
- `ReviewResultStatus.error` becomes nullable string.
- Fabric8 client alias is `libs.fabrics8.kubernetes.client` and resolves to `io.fabric8:kubernetes-client:7.8.0`.

- [ ] **Step 1: Add failing compilation expectation through source change**

Update the CR model first so later mapper/publisher code can set `status.error`:

```kotlin
class ReviewResultStatus {
    var status: String? = null
    var error: String? = null
}
```

Add the client alias:

```toml
fabrics8-kubernetes-client = { module = "io.fabric8:kubernetes-client", version.ref = "fabrics8" }
```

Add the module dependency:

```yaml
  - $libs.fabrics8.kubernetes.client
```

- [ ] **Step 2: Resolve dependencies and compile**

Run:

```bash
./kotlin check
```

Expected: compilation and existing checks pass with the explicit client dependency.

- [ ] **Step 3: Commit CR model/dependency setup**

```bash
git add crds/src/com/example/ReviewResultCR.kt libs.versions.toml review-agent/module.yaml
git commit -m "feat(review-agent): add Kubernetes client and error status"
```

---

### Task 3: Add failing mapper tests and pure CR mapping

**Files:**

- Create: `review-agent/test/com/example/ReviewResultMapperTest.kt`
- Create: `../../../review-agent/src/com/example/reviewer/ReviewResultMapper.kt`
- Modify: `review-agent/src/com/example/Main.kt`

**Interfaces:**

- `ReviewResult` in `Main.kt` is Spring AI output only:

```kotlin
data class ReviewResult(
    val comments: List<ReviewCommentResult> = emptyList(),
)

data class ReviewCommentResult(
    val lines: List<Int> = emptyList(),
    val comment: String = "",
)
```

- `fun ReviewResult.toSpec(): ReviewResultSpec` creates a mutable Fabric8 spec with non-null `comments` list.

- [ ] **Step 1: Write failing mapping tests**

Create tests covering populated and empty comments. These tests intentionally reference the not-yet-created local result
classes and mapper:

```kotlin
@Test
fun `maps review comments into CR spec`() {
    val spec = ReviewResult(
        comments = listOf(
            ReviewCommentResult(lines = listOf(3, 4), comment = "Handle null response"),
        ),
    ).toSpec()

    assertEquals(1, spec.comments!!.size)
    assertEquals(listOf(3, 4), spec.comments!![0].lines)
    assertEquals("Handle null response", spec.comments!![0].comment)
}

@Test
fun `maps missing review comments to empty CR list`() {
    assertEquals(emptyList(), ReviewResult().toSpec().comments)
}
```

- [ ] **Step 2: Run mapping tests and verify expected failure**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewResultMapperTest
```

Expected: failure because the local result classes and `toSpec` are not implemented.

- [ ] **Step 3: Implement local result classes and minimal mapper**

In `Main.kt`, before the application/runner declarations, add exactly the two local output data classes from the
interface block. Use `.entity(ReviewResult::class.java)` and do not use `ReviewResultCR`, `ReviewResultSpec`, or
`ReviewComment` as the structured-output type.

Create `ReviewResultMapper.kt`:

```kotlin
package com.example

fun ReviewResult.toSpec(): ReviewResultSpec =
    ReviewResultSpec().also { spec ->
        spec.comments = comments.map { resultComment ->
            ReviewComment().also { comment ->
                comment.lines = resultComment.lines
                comment.comment = resultComment.comment
            }
        }
    }
```

- [ ] **Step 4: Run mapping tests and verify green**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewResultMapperTest
```

Expected: all mapper tests pass.

- [ ] **Step 5: Commit local result and mapper**

```bash
git add review-agent/src/com/example/Main.kt review-agent/src/com/example/ReviewResultMapper.kt review-agent/test/com/example/ReviewResultMapperTest.kt
git commit -m "feat(review-agent): map review output into CR spec"
```

---

### Task 4: Add failing workflow tests and lifecycle orchestration

**Files:**

- Create: `review-agent/test/com/example/ReviewWorkflowTest.kt`
- Create: `../../../review-agent/src/com/example/reviewer/ReviewWorkflow.kt`
- Create: `../../../review-agent/src/com/example/reviewer/ReviewResultPublisher.kt`

**Interfaces:**

- `interface ReviewResultPublisher`:

```kotlin
interface ReviewResultPublisher {
    fun start()
    fun complete(result: ReviewResult)
    fun fail(exception: Exception)
}
```

- `class ReviewWorkflow(private val publisher: ReviewResultPublisher, private val review: () -> ReviewResult)` with
  `fun run()`.
- `run()` calls `start`, invokes review, calls `complete`; on `Exception`, calls `fail`, then rethrows the original
  exception. If `fail` throws, preserve/log the secondary error without replacing the original.

- [ ] **Step 1: Write failing workflow tests**

Create a recording fake publisher and tests for success, review failure, and failure-persistence failure:

```kotlin
private class RecordingPublisher(
    private val failException: Exception? = null,
) : ReviewResultPublisher {
    val events = mutableListOf<String>()

    override fun start() {
        events += "start"
    }

    override fun complete(result: ReviewResult) {
        events += "complete"
        events += "complete-result"
    }

    override fun fail(exception: Exception) {
        events += "fail:${exception.message}"
        failException?.let { throw it }
    }
}

@Test
fun `publishes completed result after successful review`() {
    val publisher = RecordingPublisher()
    val result = ReviewResult(
        comments = listOf(ReviewCommentResult(comment = "Review comment")),
    )

    ReviewWorkflow(publisher) { result }.run()

    assertEquals(listOf("start", "complete", "complete-result"), publisher.events)
}

@Test
fun `publishes failed status and rethrows review exception`() {
    val publisher = RecordingPublisher()
    val failure = IllegalStateException("model unavailable")

    assertFailsWith<IllegalStateException> {
        ReviewWorkflow(publisher) { throw failure }.run()
    }

    assertEquals(listOf("start", "fail:model unavailable"), publisher.events)
}

@Test
fun `preserves original exception when failed status update throws`() {
    val publisher = RecordingPublisher(failException = IllegalStateException("status unavailable"))
    val failure = IllegalStateException("model unavailable")

    val thrown = assertFailsWith<IllegalStateException> {
        ReviewWorkflow(publisher) { throw failure }.run()
    }

    assertEquals("model unavailable", thrown.message)
}
```

The fake records calls and throws its configured failure; tests assert publisher behavior, not mock invocation counts
from an unexecuted code path.

- [ ] **Step 2: Run workflow tests and verify expected failure**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewWorkflowTest
```

Expected: failure because `ReviewWorkflow` and `ReviewResultPublisher` do not exist.

- [ ] **Step 3: Implement workflow with original-exception preservation**

Implement `ReviewWorkflow.run()` with this behavior:

```kotlin
fun run() {
    try {
        publisher.start()
        publisher.complete(review())
    } catch (exception: Exception) {
        try {
            publisher.fail(exception)
        } catch (secondary: Exception) {
            System.err.println("Could not persist failed review status: ${secondary.message}")
        }
        throw exception
    }
}
```

Keep publisher state responsible for skipping failure persistence when initial CR creation did not succeed.

- [ ] **Step 4: Run workflow tests and verify green**

Run:

```bash
./kotlin test --include-module review-agent --include-classes com.example.ReviewWorkflowTest
```

Expected: all workflow tests pass and the original failure message remains intact.

- [ ] **Step 5: Commit workflow abstraction**

```bash
git add review-agent/src/com/example/ReviewWorkflow.kt review-agent/src/com/example/ReviewResultPublisher.kt review-agent/test/com/example/ReviewWorkflowTest.kt
git commit -m "feat(review-agent): orchestrate review result lifecycle"
```

---

### Task 5: Implement typed Fabric8 publisher and wire Spring runner

**Files:**

- Modify: `../../../review-agent/src/com/example/reviewer/ReviewResultPublisher.kt`
- Modify: `review-agent/src/com/example/Main.kt`

**Interfaces:**

- Add
  `class KubernetesReviewResultPublisher(client: KubernetesClient, target: ReviewResultTarget) : ReviewResultPublisher, AutoCloseable`.
- Publisher creates/replaces the configured namespaced `ReviewResultCR`, updates status separately, maps completion
  spec, and records whether create succeeded.
- `Main.kt` injects `Review`, `ReviewResultTarget`, and `ChatClient.Builder` into the runner workflow; the runner
  creates `KubernetesClient` with `KubernetesClientBuilder` and closes it with `use`.

- [ ] **Step 1: Add the typed resource handles and compile the adapter boundary**

Add the publisher constructor and typed handles exactly as follows:

```kotlin
private val namespacedResources =
    client.resources(ReviewResultCR::class.java).inNamespace(target.namespace)
private val namedResource = namespacedResources.withName(target.name)
```

Run:

```bash
./kotlin check
```

Expected: project compiles with the Fabric8 typed resource handles. Keep all Kubernetes calls typed; do not switch to
shelling out to `kubectl`. The workflow tests from Task 4 cover lifecycle orchestration without a cluster; Task 7 covers
the concrete Fabric8 adapter manually against kind.

- [ ] **Step 2: Implement start lifecycle**

In `KubernetesReviewResultPublisher.start()`:

1. Build `ReviewResultCR` with configured name and empty `ReviewResultSpec`; initialize status with
   `ReviewResultStatus().also { it.status = "InProgress" }`.
2. Call typed `createOrReplace` in `target.namespace`.
3. Set internal `created = true` only after create succeeds.
4. Update the status subresource to `InProgress` separately.

- [ ] **Step 3: Implement completion lifecycle**

In `complete(result)`:

1. Map `result.toSpec()`.
2. Replace/update the named CR spec with mapped comments.
3. Set status to `Completed` and `error = null`.
4. Update the status subresource.

- [ ] **Step 4: Implement failure lifecycle**

In `fail(exception)`:

1. Return without Kubernetes calls if `created` is false.
2. Set status to `Failed`.
3. Set `error` to exception class name plus message, using a fallback when message is null.
4. Update only the status subresource.

Do not write stack traces or kubeconfig contents into the CR.

- [ ] **Step 5: Wire the runner and Spring AI output type**

Change the runner to obtain `Review`, `ReviewResultTarget`, and `ChatClient.Builder` beans. Inside the runner, create
`KubernetesClient` with `KubernetesClientBuilder().build().use { client -> }` and place the publisher/workflow
invocation inside that `use` block so Fabric8 uses `KUBECONFIG` locally and in-cluster configuration later. Build the
chat client as today, but call:

```kotlin
.entity(ReviewResult::class.java)
```

Run the resulting chat call through `ReviewWorkflow` and `KubernetesReviewResultPublisher`. Ensure the publisher/client
is closed after the runner completes while preserving the original workflow exception.

- [ ] **Step 6: Compile and run all automated tests**

Run:

```bash
./kotlin check
./kotlin test --include-module review-agent
```

Expected: exit code `0`; no real model or cluster is contacted.

- [ ] **Step 7: Commit publisher and runner wiring**

```bash
git add review-agent/src/com/example/ReviewResultPublisher.kt review-agent/src/com/example/Main.kt
git commit -m "feat(review-agent): publish review lifecycle to Kubernetes"
```

---

### Task 6: Regenerate CRD and apply it to kind

**Files:**

- Update generated: `k8s/crds/reviewresults.example.com-v1.yml`

- [ ] **Step 1: Generate CRD from Kotlin source**

Run:

```bash
./kotlin task :crds:generateCrds@build-config
```

Expected: generated CRD includes `status.properties.error.type: string`; command exits `0`.

- [ ] **Step 2: Validate generated output without manual edits**

Run:

```bash
grep -A8 -n 'status:' k8s/crds/reviewresults.example.com-v1.yml
git diff --check
```

Expected: status schema contains both `status` and `error`; no whitespace errors.

- [ ] **Step 3: Apply CRD to local kind cluster**

Run:

```bash
kubectl --kubeconfig .kubeconfig cluster-info
kubectl --kubeconfig .kubeconfig apply -f k8s/crds/reviewresults.example.com-v1.yml
kubectl --kubeconfig .kubeconfig get crd reviewresults.example.com
```

Expected: kind API is reachable and CRD is established.

- [ ] **Step 4: Commit generated CRD**

```bash
git add k8s/crds/reviewresults.example.com-v1.yml
git commit -m "chore(crds): regenerate review result schema"
```

---

### Task 7: Manually run review agent against kind and inspect CR

**Files:**

- No source changes expected.
- Runtime artifact: configured `ReviewResult` in the local cluster.

**Interfaces:**

- Run task: `:review-agent:runJvm`.
- Profiles: `local,review`.
- Kubeconfig: `$PWD/.kubeconfig`.
- Target: `review-result` in namespace `default`.

- [ ] **Step 1: Confirm task and cluster prerequisites**

Run:

```bash
./kotlin show tasks | grep ':review-agent:runJvm'
kubectl --kubeconfig .kubeconfig cluster-info
kubectl --kubeconfig .kubeconfig get namespace default
```

Expected: run task exists and kind cluster is reachable.

- [ ] **Step 2: Dispatch review agent manually**

Run the Spring review agent with both profiles and local kubeconfig:

```bash
SPRING_PROFILES_ACTIVE=local,review \
KUBECONFIG="$PWD/.kubeconfig" \
./kotlin task :review-agent:runJvm
```

Expected: agent starts, creates/updates `default/review-result`, runs configured Spring AI review, and exits `0` only on
successful review. Provider/model output may take time; no automated LLM test is added.

- [ ] **Step 3: Inspect successful CR**

Run:

```bash
kubectl --kubeconfig .kubeconfig get reviewresult review-result -n default -o yaml
```

Verify manually:

- `status.status` is `Completed`.
- `status.error` is absent or null.
- `spec.comments` contains mapped review comments, possibly an empty list if model found none.

- [ ] **Step 4: Optionally exercise failure path manually**

Run a second invocation with a deliberately invalid model endpoint/profile configuration. Verify the process exits
nonzero and:

```bash
kubectl --kubeconfig .kubeconfig get reviewresult review-result -n default -o jsonpath='{.status.status}{"\n"}{.status.error}{"\n"}'
```

prints `Failed` and a non-empty error. Restore local configuration after verification.

- [ ] **Step 5: Record final verification**

Run:

```bash
./kotlin check
./kotlin test --include-module review-agent
git diff --check
git status --short
```

Expected: checks/tests exit `0`; only intentional source/generated changes remain plus pre-existing
`.idea/workspace.xml`.

---

## Plan Self-Review

- Spec coverage: configuration, typed Fabric8 client, separate Spring AI result, mapper, status/error schema, complete
  exception flow, process failure propagation, generated CRD, local kind installation, automated deterministic tests,
  manual real-agent verification, and future RBAC note are covered by Tasks 1–7.
- Placeholder scan: no incomplete markers or unspecified implementation steps remain. Task 5 uses concrete typed Fabric8
  handles and compiler verification, while Task 7 manually verifies the concrete adapter against kind.
- Type consistency: `ReviewResultTarget`, `ReviewResult`, `ReviewCommentResult`, `ReviewResultPublisher`,
  `ReviewWorkflow`, and `ReviewResult.toSpec()` are defined before use. `ReviewConfigurationTest` calls the exact new
  binding method.
- Scope: no real-LLM automated test, operator controller, or RBAC manifest is added.

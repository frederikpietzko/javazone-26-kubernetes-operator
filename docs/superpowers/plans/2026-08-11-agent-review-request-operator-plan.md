# Agent Review Request Operator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an event-driven Spring Operator Framework controller that turns `AgentReviewRequest` resources into
review-agent Jobs and propagates asynchronous `ReviewResultCR` outcomes into request status.

**Architecture:** Keep `AgentReviewRequest` input limited to repository URL and numeric PR. The operator derives the
result target, serializes a complete `Review` configuration with Jackson 3, creates owned ConfigMap/identity/Job
resources, and reconciles Job and owned `ReviewResultCR` events through JOSDK informer event sources. Keep resource
construction and lifecycle decisions in testable Kotlin units; keep framework wiring thin.

**Tech Stack:** Kotlin Toolchain CLI, Kotlin 2.4.10, Spring Boot 4.1.0, Spring Operator Framework 6.6.0, Java Operator
SDK 5.5.1, Fabric8 Kubernetes Client 7.8.0, Jackson 3 YAML, Fabric8 CRD Generator v2, Kubernetes
`ValidatingAdmissionPolicy`.

## Global Constraints

- Run `kotlin --help` before the first project command; use `./kotlin` for all subsequent project builds, tests, and
  tasks.
- Use Spring Operator Framework/JOSDK already present in `operator/module.yaml`; do not add a second operator framework.
- Use Jackson 3 coordinates and packages only: `tools.jackson.dataformat:jackson-dataformat-yaml` and `tools.jackson.*`.
- Use Jackson 3 `YAMLMapper`; do not concatenate YAML strings manually.
- Keep `AgentReviewRequest.spec` limited to `repository.url` and `pr`.
- Keep `Review.kt` as the review-agent configuration model; add only optional owner-reference data needed to publish an
  owned result.
- Use deterministic names based on `agent-review-<request-name>`; never use `generateName` or timestamps.
- Use owner references with `blockOwnerDeletion=false` for all request-owned resources, including `ReviewResultCR`.
- Do not poll or use a reconciliation timer for Job/ReviewResult waiting; use JOSDK informer event sources. Framework
  retries are allowed for transient failures.
- Use per-request ServiceAccount, Role, and RoleBinding for review-agent result publication. Never run review-agent with
  the operator ServiceAccount.
- Keep operator RBAC in static manifests; do not add Helm or Operator SDK code generation.
- Do not add automated tests requiring a live Kubernetes cluster, LLM, or kind lifecycle.
- Preserve unrelated `.idea/workspace.xml` changes.

---

## File Map

- Modify: `libs.versions.toml` — add Jackson 3 YAML alias.
- Modify: `shared-data-model/src/com/example/Review.kt` — add optional owner-reference model.
- Modify: `shared-data-model/test/com/example/ReviewTest.kt` — test owner-reference data.
- Modify: `review-agent/module.yaml` — retain existing review-agent dependencies; no operator or YAML dependency is
  needed for owner-reference propagation.
- Modify: `review-agent/src/com/example/reviewer/ReviewResultPublisher.kt` — apply owner reference when creating result.
- Create: `review-agent/src/com/example/reviewer/ReviewResultOwnerReferenceMapper.kt` — map shared DTO to Fabric8 owner
  reference.
- Create: `review-agent/test/com/example/ReviewResultOwnerReferenceMapperTest.kt` — test owner-reference mapping.
- Modify: `review-agent/test/com/example/ReviewConfigurationTest.kt` — verify owner reference remains optional.
- Create: `crds/src/com/example/AgentReviewRequestCR.kt` — CRD types and annotations.
- Create: `crds/test/com/example/AgentReviewRequestCRTest.kt` — direct model construction and defaults.
- Modify: `operator/module.yaml` — add `../crds`, `../shared-data-model`, and Jackson 3 YAML dependency.
- Create: `operator/resources/application.yaml` — demo `agent-review.image` configuration.
- Create: `operator/src/com/example/AgentReviewProperties.kt` — typed image configuration.
- Create: `operator/src/com/example/ResourceNameGenerator.kt` — deterministic DNS-1123 names.
- Create: `operator/src/com/example/ReviewYamlFactory.kt` — construct and serialize complete `Review` YAML.
- Create: `operator/src/com/example/AgentReviewResourceFactory.kt` — build ConfigMap, ServiceAccount, Role, RoleBinding,
  and Job.
- Create: `operator/src/com/example/AgentReviewLifecycle.kt` — pure reconciliation decisions and status transitions.
- Create: `../../../operator/src/com/example/AgentReviewClient.kt` — typed Fabric8 observation and create operations
  used by the reconciler.
- Create: `operator/src/com/example/AgentReviewRequestReconciler.kt` — JOSDK `Reconciler` and informer event sources.
- Create: `operator/test/com/example/ResourceNameGeneratorTest.kt` — name tests.
- Create: `operator/test/com/example/ReviewYamlFactoryTest.kt` — generated YAML tests.
- Create: `operator/test/com/example/AgentReviewResourceFactoryTest.kt` — Kubernetes resource tests.
- Create: `operator/test/com/example/AgentReviewLifecycleTest.kt` — lifecycle tests.
- Create: `operator/test/com/example/AgentReviewRequestFixtures.kt` — shared request and observed-resource test
  fixtures.
- Modify: `operator/test/com/example/ExampleTest.kt` — retain the Spring context smoke test and verify the demo image
  property binds.
- Generate: `k8s/crds/agentreviewrequests.example.com-v1.yml` — generated CRD output.
- Create: `k8s/examples/agent-review-request.yaml` — manual demo request.
- Create: `k8s/operator/service-account.yaml` — operator ServiceAccount.
- Create: `k8s/operator/cluster-role.yaml` — least-privilege cluster-wide operator RBAC.
- Create: `k8s/operator/cluster-role-binding.yaml` — operator binding.
- Create: `k8s/operator/deployment.yaml` — operator Deployment.
- Create: `k8s/operator/validating-admission-policy.yaml` — spec freeze policy.
- Create: `k8s/operator/validating-admission-policy-binding.yaml` — policy binding.

---

### Task 1: Extend shared Review model for owned ReviewResult publication

**Files:**

- Modify: `shared-data-model/src/com/example/Review.kt`
- Modify: `shared-data-model/test/com/example/ReviewTest.kt`
- Create: `review-agent/src/com/example/reviewer/ReviewResultOwnerReferenceMapper.kt`
- Create: `review-agent/test/com/example/ReviewResultOwnerReferenceMapperTest.kt`
- Modify: `review-agent/src/com/example/reviewer/ReviewResultPublisher.kt`
- Modify: `review-agent/test/com/example/ReviewConfigurationTest.kt`

**Interfaces:**

- `Review.Kubernetes.ownerReference: Review.OwnerReference?` defaults to `null`.
- `Review.OwnerReference` contains `apiVersion`, `kind`, `name`, `uid`, `controller=true`, and
  `blockOwnerDeletion=false`.
- `fun Review.OwnerReference.toFabric8(): io.fabric8.kubernetes.api.model.OwnerReference` returns a Fabric8 owner
  reference.
- Existing YAML without `ownerReference` continues to bind successfully.

- [ ] **Step 1: Write failing owner-reference model and mapper tests**

Add to `ReviewTest.kt`:

```kotlin
@Test
fun `review Kubernetes target may contain request owner reference`() {
    val review = Review(
        repository = Repository("https://github.com/example/repository.git"),
        pr = "7",
        kubernetes = Review.Kubernetes(
            namespace = "reviews",
            name = "agent-review-request-7",
            ownerReference = Review.OwnerReference(
                apiVersion = "example.com/v1",
                kind = "AgentReviewRequest",
                name = "request-7",
                uid = "uid-7",
            ),
        ),
    )

    assertEquals("uid-7", review.kubernetes.ownerReference!!.uid)
    assertFalse(review.kubernetes.ownerReference!!.blockOwnerDeletion)
}
```

Create `ReviewResultOwnerReferenceMapperTest.kt`:

```kotlin
@Test
fun `maps shared owner reference to Fabric8 owner reference`() {
    val reference = Review.OwnerReference(
        apiVersion = "example.com/v1",
        kind = "AgentReviewRequest",
        name = "request-7",
        uid = "uid-7",
    ).toFabric8()

    assertEquals("example.com/v1", reference.apiVersion)
    assertEquals("AgentReviewRequest", reference.kind)
    assertEquals("request-7", reference.name)
    assertEquals("uid-7", reference.uid)
    assertEquals(true, reference.controller)
    assertEquals(false, reference.blockOwnerDeletion)
}
```

- [ ] **Step 2: Run focused tests and verify the expected failure**

Run:

```bash
./kotlin test --include-module shared-data-model --include-classes com.example.ReviewTest
./kotlin test --include-module review-agent --include-classes com.example.ReviewResultOwnerReferenceMapperTest
```

Expected: compilation failure because owner-reference fields and mapper do not exist.

- [ ] **Step 3: Add optional shared DTO**

Update `Review.kt`:

```kotlin
data class Review(
    val repository: Repository,
    val pr: String,
    val kubernetes: Kubernetes,
) {
    data class Kubernetes(
        val namespace: String,
        val name: String,
        val ownerReference: OwnerReference? = null,
    )

    data class OwnerReference(
        val apiVersion: String,
        val kind: String,
        val name: String,
        val uid: String,
        val controller: Boolean = true,
        val blockOwnerDeletion: Boolean = false,
    )
}
```

Create the mapper with `OwnerReferenceBuilder`, copying every field explicitly.

- [ ] **Step 4: Apply owner reference in publisher**

In `KubernetesReviewResultPublisher.start()`, build metadata with the mapped optional owner reference:

```kotlin
metadata = ObjectMetaBuilder()
    .withName(target.name)
    .apply {
        target.ownerReference?.let { withOwnerReferences(it.toFabric8()) }
    }
    .build()
```

Keep owner reference optional so existing standalone `review-agent` runs remain valid. Do not add finalizer permissions
to review-agent RBAC.

- [ ] **Step 5: Run focused tests and existing review-agent tests**

Run:

```bash
./kotlin test --include-module shared-data-model --include-classes com.example.ReviewTest
./kotlin test --include-module review-agent --include-classes com.example.ReviewResultOwnerReferenceMapperTest
./kotlin test --include-module review-agent --include-classes com.example.ReviewConfigurationTest
```

Expected: all pass, including configuration without an owner reference.

- [ ] **Step 6: Commit model and publisher ownership**

```bash
git add shared-data-model review-agent/src/com/example/reviewer/ReviewResultOwnerReferenceMapper.kt review-agent/src/com/example/reviewer/ReviewResultPublisher.kt review-agent/test/com/example/ReviewResultOwnerReferenceMapperTest.kt review-agent/test/com/example/ReviewConfigurationTest.kt
git commit -m "feat(review-agent): publish owned review results"
```

---

### Task 2: Add AgentReviewRequest CRD types

**Files:**

- Create: `crds/src/com/example/AgentReviewRequestCR.kt`
- Create: `crds/test/com/example/AgentReviewRequestCRTest.kt`

**Interfaces:**

- `AgentReviewRequestSpec.repository.url: String?` and `AgentReviewRequestSpec.pr: String?`.
- `AgentReviewRequestStatus.phase`, `message`, `jobName`, `configMapName`, `reviewResultName` are nullable strings.
- `AgentReviewRequestCR : CustomResource<AgentReviewRequestSpec, AgentReviewRequestStatus>, Namespaced`.
- API metadata: group `example.com`, version `v1`, kind `AgentReviewRequest`, plural `agentreviewrequests`.

- [ ] **Step 1: Write failing CR model test**

Create `AgentReviewRequestCRTest.kt`:

```kotlin
@Test
fun `request model stores repository and pull request`() {
    val request = AgentReviewRequestCR().apply {
        spec = AgentReviewRequestSpec().apply {
            repository = AgentReviewRepository().apply {
                url = "https://github.com/example/repository.git"
            }
            pr = "42"
        }
    }

    assertEquals("https://github.com/example/repository.git", request.spec.repository!!.url)
    assertEquals("42", request.spec.pr)
}
```

- [ ] **Step 2: Run test and verify expected failure**

Run:

```bash
./kotlin test --include-module crds --include-classes com.example.AgentReviewRequestCRTest
```

Expected: compilation failure because the CR classes do not exist.

- [ ] **Step 3: Add minimal annotated CR types**

Create the mutable Fabric8 model:

```kotlin
class AgentReviewRepository {
    var url: String? = null
}

class AgentReviewRequestSpec {
    var repository: AgentReviewRepository? = null
    var pr: String? = null
}

class AgentReviewRequestStatus {
    var phase: String? = null
    var message: String? = null
    var jobName: String? = null
    var configMapName: String? = null
    var reviewResultName: String? = null
}

@Group("example.com")
@Version("v1")
@Kind("AgentReviewRequest")
@Plural("agentreviewrequests")
class AgentReviewRequestCR :
    CustomResource<AgentReviewRequestSpec, AgentReviewRequestStatus>(),
    Namespaced
```

Annotate the mutable fields with the Fabric8 v2 generator annotations:

```kotlin
class AgentReviewRequestSpec {
    @field:Required
    var repository: AgentReviewRepository? = null

    @field:Required
    @field:Pattern("^[0-9]+$")
    var pr: String? = null
}

class AgentReviewRepository {
    @field:Required
    @field:Pattern("^https://[^\\s]+$")
    var url: String? = null
}
```

Import `io.fabric8.generator.annotation.Pattern` and `io.fabric8.generator.annotation.Required`. Generate and inspect
the CRD to verify required fields and patterns; do not manually edit generated YAML.

- [ ] **Step 4: Run model test and build CRD module**

Run:

```bash
./kotlin test --include-module crds --include-classes com.example.AgentReviewRequestCRTest
./kotlin check
```

Expected: test and project checks pass.

- [ ] **Step 5: Commit CR source**

```bash
git add crds/src/com/example/AgentReviewRequestCR.kt crds/test/com/example/AgentReviewRequestCRTest.kt
 git commit -m "feat(crds): add agent review request"
```

---

### Task 3: Add operator configuration, deterministic names, and Review YAML

**Files:**

- Modify: `libs.versions.toml`
- Modify: `operator/module.yaml`
- Create: `operator/resources/application.yaml`
- Create: `operator/src/com/example/AgentReviewProperties.kt`
- Create: `operator/src/com/example/ResourceNameGenerator.kt`
- Create: `operator/src/com/example/ReviewYamlFactory.kt`
- Create: `operator/test/com/example/ResourceNameGeneratorTest.kt`
- Create: `operator/test/com/example/ReviewYamlFactoryTest.kt`

**Interfaces:**

- `AgentReviewProperties.image: String` binds `agent-review.image`.
- `ResourceNameGenerator.baseName(requestName: String): String` returns deterministic DNS-1123 name.
- `ReviewYamlFactory.create(request: AgentReviewRequestCR, baseName: String): String` returns complete `review.yaml`.
- YAML contains `review.repository.url`, string `review.pr`, request namespace, deterministic result name, and owner
  reference with `blockOwnerDeletion=false`.

- [ ] **Step 1: Add failing name and YAML tests**

Create name tests:

```kotlin
@Test
fun `creates stable base name from request name`() {
    assertEquals("agent-review-ebfs-jpa-pr-1", ResourceNameGenerator.baseName("ebfs-jpa-pr-1"))
    assertEquals("agent-review-ebfs-jpa-pr-1", ResourceNameGenerator.baseName("EBFS_JPA_PR_1"))
}

@Test
fun `sanitizes and bounds generated name deterministically`() {
    val first = ResourceNameGenerator.baseName("A".repeat(300))
    val second = ResourceNameGenerator.baseName("A".repeat(299) + "B")

    assertTrue(first.length <= 63)
    assertTrue(first.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")))
    assertNotEquals(first, second)
}
```

Create a YAML factory test that parses the returned text with `YAMLMapper` and asserts:

```kotlin
assertEquals("https://github.com/example/repository.git", tree.at("/review/repository/url").asText())
assertEquals("42", tree.at("/review/pr").asText())
assertEquals("reviews", tree.at("/review/kubernetes/namespace").asText())
assertEquals("agent-review-request-42", tree.at("/review/kubernetes/name").asText())
assertEquals("uid-42", tree.at("/review/kubernetes/ownerReference/uid").asText())
assertFalse(tree.at("/review/kubernetes/ownerReference/blockOwnerDeletion").asBoolean())
```

- [ ] **Step 2: Run focused tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.ResourceNameGeneratorTest
./kotlin test --include-module operator --include-classes com.example.ReviewYamlFactoryTest
```

Expected: compilation failure because properties, name generator, and YAML factory do not exist.

- [ ] **Step 3: Add dependencies and operator application YAML**

Add to `libs.versions.toml`:

```toml
jackson-dataformat-yaml = { module = "tools.jackson.dataformat:jackson-dataformat-yaml" }
```

Add to `operator/module.yaml`:

```yaml
  - ../crds
  - ../shared-data-model
  - $libs.jackson.dataformat.yaml
```

Keep the existing Spring Operator dependencies. Create `operator/resources/application.yaml`:

```yaml
agent-review:
  image: review-agent:latest
```

Create the properties bean with mutable Spring Boot binding and validation:

```kotlin
@Component
@Validated
@ConfigurationProperties("agent-review")
data class AgentReviewProperties(
    @field:NotBlank var image: String = "",
)
```

`operator/test/com/example/ExampleTest.kt` must load the context with `agent-review.image=review-agent:latest` from
`operator/resources/application.yaml`; add a focused test that an empty image fails binding validation.

- [ ] **Step 4: Implement deterministic DNS-1123 naming**

Implement `ResourceNameGenerator` with these exact rules:

1. Lowercase input using `Locale.ROOT`.
2. Replace every character outside `[a-z0-9-]` with `-`.
3. Collapse repeated hyphens and trim hyphens.
4. Prefix `agent-review-`.
5. If the result exceeds 63 characters, retain a readable prefix and append `-` plus an 8-character SHA-256 hex prefix
   of the original request name.
6. Trim the final value to 63 characters without ending in `-`.

Return a non-empty deterministic fallback only for an impossible empty metadata name; normal Kubernetes requests always
have a name, so treat empty input as `IllegalArgumentException` in the unit test.

- [ ] **Step 5: Implement YAML factory with Jackson 3**

Construct a shared `Review` value:

```kotlin
Review(
    repository = Repository(request.spec.repository!!.url!!),
    pr = request.spec.pr!!,
    kubernetes = Review.Kubernetes(
        namespace = request.metadata.namespace,
        name = baseName,
        ownerReference = Review.OwnerReference(
            apiVersion = "example.com/v1",
            kind = "AgentReviewRequest",
            name = request.metadata.name,
            uid = request.metadata.uid,
        ),
    ),
)
```

Serialize with a Jackson 3 YAML mapper built through its builder and module discovery. Configure the mapper to emit
non-null fields and quote the PR when necessary; tests must parse YAML semantically rather than compare whitespace.

- [ ] **Step 6: Run focused tests and verify green**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.ResourceNameGeneratorTest
./kotlin test --include-module operator --include-classes com.example.ReviewYamlFactoryTest
```

Expected: all tests pass.

- [ ] **Step 7: Commit operator configuration utilities**

```bash
git add libs.versions.toml operator/module.yaml operator/resources/application.yaml operator/src operator/test
git commit -m "feat(operator): build review agent configuration"
```

---

### Task 4: Add pure Kubernetes resource builders

**Files:**

- Create: `operator/src/com/example/AgentReviewResourceFactory.kt`
- Create: `operator/test/com/example/AgentReviewResourceFactoryTest.kt`

**Interfaces:**

-
`data class AgentReviewResources(val configMap: ConfigMap, val serviceAccount: ServiceAccount, val role: Role, val roleBinding: RoleBinding, val job: Job)`.
- `AgentReviewResourceFactory.create(request: AgentReviewRequestCR, image: String): AgentReviewResources`.
- Every resource uses request namespace and the expected owner reference.

- [ ] **Step 1: Write failing resource-builder tests**

Build a named request fixture with namespace `reviews`, UID `uid-42`, name `request-42`, URL, and PR `42`. Assert:

```kotlin
assertEquals("agent-review-request-42", resources.configMap.metadata.name)
assertEquals("reviews", resources.job.metadata.namespace)
assertEquals("review-agent:1", resources.job.spec.template.spec.containers[0].image)
assertEquals("agent-review-request-42-agent", resources.job.spec.template.spec.serviceAccountName)
assertEquals(0, resources.job.spec.backoffLimit)
assertEquals(RestartPolicy.NEVER, resources.job.spec.template.spec.restartPolicy)
assertEquals("classpath:/,file:/config/review.yaml", resources.job.spec.template.spec.containers[0].env.single().value)
assertEquals(true, resources.configMap.immutable)
assertEquals("review.yaml", resources.configMap.data.keys.single())
assertEquals("reviewresults/status", resources.role.rules[1].resources.single())
```

Also assert owner UID/name on ConfigMap, ServiceAccount, Role, RoleBinding, and Job, and assert RoleBinding subject
references the per-request ServiceAccount.

- [ ] **Step 2: Run test and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceFactoryTest
```

Expected: compilation failure because the resource factory does not exist.

- [ ] **Step 3: Implement resource factory**

Build resources with Fabric8 builders:

- ConfigMap: deterministic base name, `immutable=true`, `data["review.yaml"]` from `ReviewYamlFactory`, request owner
  reference.
- ServiceAccount: `<base>-agent`, request owner reference.
- Role: `<base>-agent`, namespace request namespace, rules for `reviewresults` and `reviewresults/status`, request owner
  reference.
- RoleBinding: `<base>-agent`, same namespace, role reference to `<base>-agent`, subject to `<base>-agent`, request
  owner reference.
- Job: deterministic base name, `backoffLimit=0`, `restartPolicy=Never`, configured image, per-request ServiceAccount,
  ConfigMap `subPath` mount, read-only mount, exact environment variable, request owner reference.

Do not add delete verbs to dynamically generated Role. Do not add secrets, Jobs, ConfigMaps, or CR request permissions
to review-agent Role.

- [ ] **Step 4: Run resource-builder tests and verify green**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceFactoryTest
```

Expected: all resource shape and ownership assertions pass.

- [ ] **Step 5: Commit resource builders**

```bash
git add operator/src/com/example/AgentReviewResourceFactory.kt operator/test/com/example/AgentReviewResourceFactoryTest.kt
 git commit -m "feat(operator): build review agent resources"
```

---

### Task 5: Add lifecycle decisions and status transitions

**Files:**

- Create: `operator/src/com/example/AgentReviewLifecycle.kt`
- Create: `operator/test/com/example/AgentReviewLifecycleTest.kt`
- Create: `operator/test/com/example/AgentReviewRequestFixtures.kt`

**Interfaces:**

-
`data class ObservedAgentReviewResources(val configMap: ConfigMap?, val serviceAccount: ServiceAccount?, val role: Role?, val roleBinding: RoleBinding?, val job: Job?, val reviewResult: ReviewResultCR?)`.
- `sealed interface LifecycleDecision` with these exact cases:
    - `data class EnsureResources(val status: AgentReviewRequestStatus) : LifecycleDecision`
    - `data class Wait(val status: AgentReviewRequestStatus) : LifecycleDecision`
    - `data class Successful(val status: AgentReviewRequestStatus) : LifecycleDecision`
    - `data class Error(val status: AgentReviewRequestStatus) : LifecycleDecision`
    - `data object Noop : LifecycleDecision`
-
`AgentReviewLifecycle.decide(request: AgentReviewRequestCR, observed: ObservedAgentReviewResources): LifecycleDecision`.

- [ ] **Step 1: Write failing lifecycle tests**

Cover these exact cases with a request fixture and Fabric8 `JobBuilder`/`JobStatus` fixtures:

```kotlin
@Test
fun `new request asks for all dependent resources and InProgress status`() {
    val decision =
        AgentReviewLifecycle.decide(request(), ObservedAgentReviewResources(null, null, null, null, null, null))
    val ensure = assertIs<LifecycleDecision.EnsureResources>(decision)
    assertEquals("InProgress", ensure.status.phase)
    assertEquals("agent-review-request-42", ensure.status.jobName)
}

@Test
fun `matching resources are reused and request remains InProgress`() {
    val decision = AgentReviewLifecycle.decide(request(), observedWithActiveJob())
    val wait = assertIs<LifecycleDecision.Wait>(decision)
    assertEquals("InProgress", wait.status.phase)
}

@Test
fun `completed result makes request Successful`() {
    val result = ReviewResultCR().apply { status = ReviewResultStatus().also { it.status = "Completed" } }
    val decision = AgentReviewLifecycle.decide(request(), observedWithCompletedJob(result))
    val successful = assertIs<LifecycleDecision.Successful>(decision)
    assertEquals("Successful", successful.status.phase)
}

@Test
fun `failed result makes request Error with result message`() {
    val result = ReviewResultCR().apply {
        status = ReviewResultStatus().also { it.status = "Failed"; it.error = "model unavailable" }
    }
    val decision = AgentReviewLifecycle.decide(request(), observedWithActiveJob(result))
    val error = assertIs<LifecycleDecision.Error>(decision)
    assertEquals("Error", error.status.phase)
    assertEquals("model unavailable", error.status.message)
}

@Test
fun `failed job makes request Error`() {
    val decision = AgentReviewLifecycle.decide(request(), observedWithFailedJob())
    val error = assertIs<LifecycleDecision.Error>(decision)
    assertEquals("Error", error.status.phase)
}

@Test
fun `successful job without result makes request Error`() {
    val decision = AgentReviewLifecycle.decide(request(), observedWithCompletedJob(null))
    val error = assertIs<LifecycleDecision.Error>(decision)
    assertEquals("Error", error.status.phase)
}

@Test
fun `missing owned resource after start makes request Error without rerun`() {
    val request = request().apply { status = AgentReviewRequestStatus().also { it.phase = "InProgress" } }
    val decision = AgentReviewLifecycle.decide(request, observedWithActiveJob().copy(configMap = null))
    assertIs<LifecycleDecision.Error>(decision)
}

@Test
fun `terminal request is Noop`() {
    val request = request().apply { status = AgentReviewRequestStatus().also { it.phase = "Successful" } }
    assertIs<LifecycleDecision.Noop>(AgentReviewLifecycle.decide(request, observedWithActiveJob()))
}
```

Create `AgentReviewRequestFixtures.kt` with these package-level test helpers:

```kotlin
fun request(): AgentReviewRequestCR
fun observedWithActiveJob(result: ReviewResultCR? = null): ObservedAgentReviewResources
fun observedWithCompletedJob(result: ReviewResultCR?): ObservedAgentReviewResources
fun observedWithFailedJob(): ObservedAgentReviewResources
```

Each helper returns namespace `reviews`, request name `request-42`, UID `uid-42`, repository URL
`https://github.com/example/repository.git`, PR `42`, matching owner references on all owned resources, and the
requested Job status. Each test must assert phase, message, and whether a new Job is allowed. The lifecycle unit must
never call Kubernetes or sleep.

- [ ] **Step 2: Run lifecycle tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewLifecycleTest
```

Expected: compilation failure because lifecycle decisions do not exist.

- [ ] **Step 3: Implement lifecycle state machine**

Implement these rules:

- `Successful` and `Error` phases return `Noop`.
- No phase/resources returns `EnsureResources` and desired `InProgress` status.
- Matching ConfigMap/identity/Job resources with active Job returns `Wait` and `InProgress`.
- Missing owned ConfigMap/identity/Job after status is `InProgress` returns terminal `Error`; never recreate Job.
- Failed Job returns `Error`.
- Completed Job with no matching result returns `Error`.
- Result `InProgress` returns `Wait`.
- Result `Completed` returns `Successful`.
- Result `Failed` returns `Error` with result error detail.
- Temporary Kubernetes failures are not represented by this decision type; the reconciler throws them so JOSDK retries.

- [ ] **Step 4: Run lifecycle tests and verify green**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewLifecycleTest
```

Expected: all lifecycle decisions pass.

- [ ] **Step 5: Commit lifecycle state machine**

```bash
git add operator/src/com/example/AgentReviewLifecycle.kt operator/test/com/example/AgentReviewLifecycleTest.kt
git commit -m "feat(operator): model review request lifecycle"
```

---

### Task 6: Add JOSDK reconciler and event sources

**Files:**

- Create: `../../../operator/src/com/example/AgentReviewClient.kt`
- Create: `operator/src/com/example/AgentReviewRequestReconciler.kt`
- Modify: `operator/test/com/example/ExampleTest.kt`
- Create: `operator/test/com/example/AgentReviewRequestReconcilerTest.kt`
- Keep unchanged: `operator/src/com/example/Main.kt` — the Spring Operator starter discovers the reconciler bean.

**Interfaces:**

- `interface AgentReviewResourceGateway` exposes
  `observe(namespace: String, baseName: String): ObservedAgentReviewResources` and
  `createMissing(resources: AgentReviewResources)`.
- `@Component class Fabric8AgentReviewResourceGateway(client: KubernetesClient) : AgentReviewResourceGateway` performs
  typed get/create calls and throws API failures for JOSDK retry. `createMissing` creates only absent resources; an
  existing resource with different desired fields or owner UID throws `AgentReviewResourceConflict`.
-
`@Component @ControllerConfiguration(name = "agent-review-request") class AgentReviewRequestReconciler(gateway: AgentReviewResourceGateway, properties: AgentReviewProperties) : Reconciler<AgentReviewRequestCR>`.
- `fun reconcileOnce(primary: AgentReviewRequestCR, observed: ObservedAgentReviewResources): LifecycleDecision`
  delegates to `AgentReviewLifecycle` and is directly unit-testable.
-
`override fun reconcile(primary: AgentReviewRequestCR, context: Context<AgentReviewRequestCR>): UpdateControl<AgentReviewRequestCR>`.
- `override fun prepareEventSources(context: EventSourceContext<AgentReviewRequestCR>): Map<String, EventSource>`.

- [ ] **Step 1: Write failing reconciler tests**

Use the existing Spring Operator test support for context registration and a fake `AgentReviewClient`. Add concrete
tests:

```kotlin
@SpringBootTest
class AgentReviewRequestReconcilerTest {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `registers AgentReviewRequest controller`() {
        assertNotNull(applicationContext.getBean(AgentReviewRequestReconciler::class.java))
    }

    @Test
    fun `new request produces EnsureResources decision`() {
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision =
            reconciler.reconcileOnce(request(), ObservedAgentReviewResources(null, null, null, null, null, null))
        assertIs<LifecycleDecision.EnsureResources>(decision)
    }

    @Test
    fun `terminal result produces Successful decision without resource creation`() {
        val result = ReviewResultCR().apply { status = ReviewResultStatus().also { it.status = "Completed" } }
        val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
        val decision = reconciler.reconcileOnce(request(), observedWithCompletedJob(result))
        assertEquals("Successful", assertIs<LifecycleDecision.Successful>(decision).status.phase)
    }

    @Test
    fun `reconciler is Spring discoverable`() {
        assertNotNull(AgentReviewRequestReconciler::class.findAnnotation<Component>())
        assertEquals(
            "agent-review-request",
            AgentReviewRequestReconciler::class.findAnnotation<ControllerConfiguration>()!!.name,
        )
    }
```

Define `FakeGateway` in the test file with `observe` returning the supplied fixture and `createMissing` recording the
passed resource set. Reuse the concrete `request()`, `observedWithActiveJob()`, and `observedWithCompletedJob()` fixture
helpers from `AgentReviewLifecycleTest` through a shared `AgentReviewRequestFixtures.kt` test utility. The test must
assert no `UpdateControl.rescheduleAfter` or timer configuration is requested. Framework retry remains enabled for
thrown transient exceptions.

- [ ] **Step 2: Run tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewRequestReconcilerTest
```

Expected: compilation failure because the reconciler and gateway are missing.

- [ ] **Step 3: Implement JOSDK event sources**

Register cached `InformerEventSource` instances for `ConfigMap`, `ServiceAccount`, `Role`, `RoleBinding`, `Job`, and
`ReviewResultCR`. Use `Mappers.fromOwnerReferences(AgentReviewRequestCR::class.java)` so every owned resource event maps
back to its request. Return them with `EventSourceUtils.nameEventSources(...)`.

Do not register a polling or timer event source. The `ReviewResultCR` owner reference is created by review-agent from
generated YAML, so the standard owner-reference mapper works.

- [ ] **Step 4: Implement reconcile orchestration**

For each primary event:

1. Return no-op for terminal status.
2. Validate non-null repository URL and PR; CRD admission handles normal invalid input, but keep a defensive check.
3. Compute base name and desired resources.
4. Read observed resources through `AgentReviewResourceGateway.observe`; informer events trigger reconciliation, while
   typed gets provide the current resource state.
5. Detect an existing result with a different owner UID; throw a retryable error while it has deletion timestamp,
   otherwise set terminal `Error`.
6. Apply only missing matching resources in dependency order: ConfigMap, ServiceAccount, Role, RoleBinding, Job.
7. Set status fields and return `UpdateControl.patchStatus(primary)` once all five resources exist.
8. For Job/result events, apply lifecycle decision and patch status when it changes.
9. Throw transient Fabric8/API/cache errors so JOSDK’s retry policy handles them.

`Fabric8AgentReviewResourceGateway.createMissing` must compare existing resources before creating: matching resources
are reused, while different image, namespace, owner UID, ConfigMap data/reference, mount, environment, ServiceAccount,
or RBAC rule causes `AgentReviewResourceConflict`. Never force-update conflicting immutable ConfigMaps or Jobs. Never
recreate a Job after it was previously created and then disappeared.

- [ ] **Step 5: Register reconciler with Spring Operator starter**

Annotate `AgentReviewRequestReconciler` with `@Component` and `@ControllerConfiguration(name = "agent-review-request")`.
The existing Spring Operator starter collects `List<Reconciler<?>>` beans and registers them with its single
auto-configured `Operator`. Keep `Main.kt` as the Spring Boot entry point; do not create a second `Operator` instance or
Kubernetes client lifecycle.

- [ ] **Step 6: Run operator tests and full check**

Run:

```bash
./kotlin test --include-module operator
./kotlin check
```

Expected: all operator tests and project checks pass without a live cluster.

- [ ] **Step 7: Commit reconciler**

```bash
git add operator/src operator/test
 git commit -m "feat(operator): reconcile agent review requests"
```

---

### Task 7: Generate CRD and add static deployment/RBAC manifests

**Files:**

- Generate: `k8s/crds/agentreviewrequests.example.com-v1.yml`
- Create: `k8s/examples/agent-review-request.yaml`
- Create: `k8s/operator/service-account.yaml`
- Create: `k8s/operator/cluster-role.yaml`
- Create: `k8s/operator/cluster-role-binding.yaml`
- Create: `k8s/operator/deployment.yaml`
- Create: `k8s/operator/validating-admission-policy.yaml`
- Create: `k8s/operator/validating-admission-policy-binding.yaml`

**Interfaces:**

- `./kotlin task :crds:generateCrds@build-config` remains the only CRD generation command.
- Static operator manifests install cluster-wide watch permissions and the immutable-spec admission policy.

- [ ] **Step 1: Generate AgentReviewRequest CRD**

Run:

```bash
./kotlin task :crds:generateCrds@build-config
```

Expected: generated output contains `agentreviewrequests.example.com` with required `spec.repository`,
`spec.repository.url`, and `spec.pr` fields, HTTPS URL and numeric PR patterns, and `status` subresource. Do not
hand-edit generated YAML.

- [ ] **Step 2: Add least-privilege static operator RBAC**

Create a ServiceAccount and ClusterRole with explicit rules:

```yaml
- apiGroups: ["example.com"]
  resources: ["agentreviewrequests"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["example.com"]
  resources: ["agentreviewrequests/status"]
  verbs: ["get", "update", "patch"]
- apiGroups: [""]
  resources: ["configmaps", "serviceaccounts"]
  verbs: ["get", "list", "watch", "create"]
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create"]
- apiGroups: ["rbac.authorization.k8s.io"]
  resources: ["roles", "rolebindings"]
  verbs: ["get", "list", "watch", "create"]
- apiGroups: ["example.com"]
  resources: ["reviewresults"]
  verbs: ["get", "list", "watch"]
```

The reconciler does not emit Kubernetes Events, so omit `events` permissions. Do not grant wildcard resources/verbs. Do
not add leader-election RBAC because the demo Deployment runs one replica with leader election disabled.

- [ ] **Step 3: Add operator Deployment**

Create a one-replica Deployment using the operator ServiceAccount and demo image `agent-review-operator:latest`. The
image must be replaceable through the Deployment manifest. The operator’s packaged `application.yaml` supplies
`agent-review.image: review-agent:latest` until deployment configuration replaces it.

- [ ] **Step 4: Add ValidatingAdmissionPolicy**

Create policy and binding for namespaced `AgentReviewRequest` updates. Use this validation expression:

```cel
!has(oldObject.status.phase) ||
oldObject.status.phase == "Pending" ||
object.spec == oldObject.spec
```

Use `failurePolicy: Fail`, `validationActions: [Deny]`, and match only `UPDATE` operations for `agentreviewrequests` in
`example.com/v1`. Status-subresource updates are not matched as primary spec updates.

- [ ] **Step 5: Validate manifests structurally**

Run:

```bash
kubectl apply --dry-run=client -f k8s/crds/agentreviewrequests.example.com-v1.yml
kubectl apply --dry-run=client -f k8s/operator/
git diff --check
```

Expected: all manifests parse successfully and no whitespace errors occur.

- [ ] **Step 6: Add demo request manifest**

Create `k8s/examples/agent-review-request.yaml`:

```yaml
apiVersion: example.com/v1
kind: AgentReviewRequest
metadata:
  name: ebfs-jpa-pr-1
  namespace: default
spec:
  repository:
    url: https://github.com/frederikpietzko/ebfs-jpa.git
  pr: "1"
```

- [ ] **Step 7: Commit generated CRD and manifests**

```bash
git add k8s/crds/agentreviewrequests.example.com-v1.yml k8s/operator k8s/examples/agent-review-request.yaml
git commit -m "chore(operator): add deployment and RBAC manifests"
```

---

### Task 8: Final verification and manual artifact review

**Files:**

- No source changes expected unless verification exposes a defect.

- [ ] **Step 1: Run complete Kotlin verification**

Run:

```bash
kotlin --help
./kotlin check
./kotlin test
```

Expected: all checks and tests pass.

- [ ] **Step 2: Verify generated CRD and static manifest content**

Run:

```bash
grep -n "kind: AgentReviewRequest\|agentreviewrequests\|status:" k8s/crds/agentreviewrequests.example.com-v1.yml
grep -R -n "agent-review.image\|SPRING_CONFIG_LOCATION\|blockOwnerDeletion\|reviewresults/status" operator k8s/operator
```

Expected: CRD, image configuration, Spring config path, owner-reference policy, and workload RBAC are present.

- [ ] **Step 3: Review diff and repository state**

Run:

```bash
git diff --check
git status --short
git log --oneline -8
```

Expected: no whitespace errors; pre-existing `.idea/workspace.xml` remains the only unrelated working-tree modification.

- [ ] **Step 4: Record manual kind validation commands without adding E2E tests**

When manually validating against existing kind, apply generated CRD and manifests, then inspect:

```bash
kubectl apply -f k8s/crds/agentreviewrequests.example.com-v1.yml
kubectl apply -f k8s/operator/
kubectl apply -f k8s/examples/agent-review-request.yaml
kubectl get agentreviewrequest -A -o yaml
kubectl get configmap,serviceaccount,role,rolebinding,job,reviewresult -A
```

Do not create or destroy a cluster from automated tests. Do not claim runtime success unless these commands are actually
run and observed.

## Plan Self-Review

- Spec coverage: CRD input/status, derived Review Kubernetes target, Jackson 3 YAML, deterministic naming, ConfigMap,
  per-request identity/RBAC, Job volume/env, owner references, asynchronous Job/ReviewResult events, terminal status,
  retryable failures, immutability policy, static operator RBAC, no Helm, no E2E, and tests are mapped to Tasks 1–8.
- Placeholder scan: no `TBD`, `TODO`, or “implement later” steps. Demo image values are concrete and explicitly
  replaceable deployment configuration.
- Type consistency: `Review.OwnerReference`, `AgentReviewRequestCR`, `AgentReviewResources`,
  `ObservedAgentReviewResources`, `LifecycleDecision`, and reconciler signatures are defined before use.
- Dependency consistency: operator consumes `crds`, `shared-data-model`, Fabric8 API models, JOSDK, and Jackson 3 YAML;
  review-agent only receives shared owner DTO changes and existing Fabric8 dependencies.
- Verification consistency: all project commands use `./kotlin` after the required initial `kotlin --help`; no automated
  live-cluster or LLM tests are introduced.

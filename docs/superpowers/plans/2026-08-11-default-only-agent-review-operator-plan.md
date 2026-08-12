# Default-Only Agent Review Operator Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:
> executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify the demo operator to namespace `default`, use one pre-created review-agent identity, and make the
operator create only ConfigMap and Job resources.

**Architecture:** Keep request validation, deterministic naming, Jackson 3 YAML, ReviewResult publication, Job/result
lifecycle, and owner references. Remove per-request ServiceAccount/Role/RoleBinding construction and observation. Scope
JOSDK informer sources and operator RBAC to `default`; static review-agent RBAC grants result-publication permissions.

**Tech Stack:** Kotlin Toolchain CLI, Kotlin 2.4.10, Spring Boot 4.1.0, Spring Operator Framework 6.6.0, Java Operator
SDK 5.5.1, Fabric8 Kubernetes Client 7.8.0, Jackson 3 YAML, Kubernetes `ValidatingAdmissionPolicy`.

## Global Constraints

- Run `kotlin --help` before the first project command; use `./kotlin` for all subsequent project builds, tests, and
  tasks.
- Supported namespace is exactly `default`; reject requests outside `default` through admission and do not create
  resources for them.
- Operator Deployment, operator Role/RoleBinding, review-agent ServiceAccount/Role/RoleBinding, ConfigMaps, Jobs, and
  ReviewResultCRs use `default`.
- Operator creates only ConfigMap and Job resources per request.
- Static review-agent identity is `review-agent`; its Role grants only `reviewresults` and `reviewresults/status`
  permissions listed in the approved spec.
- Operator RBAC is namespaced and grants no ServiceAccount, Role, RoleBinding, `escalate`, `bind`, or ReviewResult write
  permissions.
- Use Jackson 3 coordinates and packages only: `tools.jackson.dataformat:jackson-dataformat-yaml` and `tools.jackson.*`.
- Preserve deterministic `agent-review-<request-name>` names, request owner references with `controller=true` and
  `blockOwnerDeletion=false`, and exact Spring config mount/environment values.
- Use JOSDK 5.5.1 `List<EventSource<*, AgentReviewRequestCR>>` API with namespace-scoped informers; do not poll or use
  timers.
- Preserve terminal `InProgress`, `Successful`, and `Error` lifecycle behavior, retry transient Kubernetes failures, and
  never recreate a Job after normal processing started and it disappeared.
- Keep static RBAC/manifests; do not add Helm, Operator SDK generation, or live-cluster/kind E2E tests.
- Preserve unrelated `.idea/vcs.xml` and `.idea/workspace.xml` changes.

---

### Task 1: Reduce per-request resources to ConfigMap and Job

**Files:**

- Modify: `operator/src/com/example/AgentReviewResourceFactory.kt`
- Modify: `../../../operator/src/com/example/AgentReviewClient.kt`
- Modify: `operator/src/com/example/AgentReviewResourceMatcher.kt`
- Modify: `operator/test/com/example/AgentReviewResourceFactoryTest.kt`
- Modify: `../../../operator/test/com/example/AgentReviewClientTest.kt`
- Modify: `operator/test/com/example/AgentReviewRequestFixtures.kt` — change fixture namespace to `default`; retain old
  observation fields until Task 2.

**Interfaces:**

- `data class AgentReviewResources(val configMap: ConfigMap, val job: Job)`.
- `data class ObservedAgentReviewResources` is updated in Task 2 to contain only `configMap`, `job`, and `reviewResult`.
- `AgentReviewResourceFactory.create(request: AgentReviewRequestCR, image: String): AgentReviewResources` builds exactly
  two resources.
- `AgentReviewClient` exposes:
    - `observe(namespace: String, baseName: String): ObservedAgentReviewResources`
    - `validateDesired(resources: AgentReviewResources, observed: ObservedAgentReviewResources)`
    - `createDependencies(resources: AgentReviewResources)`
    - `createMissing(resources: AgentReviewResources)`
- `AgentReviewResourceMatcher` exposes only `configMapMatches` and `jobMatches` for resource drift checks.

- [ ] **Step 1: Update failing resource shape tests**

Change the resource fixture assertions from five resources to two:

```kotlin
val resources = AgentReviewResourceFactory.create(request(), "review-agent:1")

assertEquals(setOf("configMap", "job"), resources::class.memberProperties.map { it.name }.toSet())
assertEquals("agent-review-request-42", resources.configMap.metadata.name)
assertEquals("agent-review-request-42", resources.job.metadata.name)
assertEquals("default", resources.job.metadata.namespace)
assertEquals("review-agent:1", resources.job.spec.template.spec.containers.single().image)
assertEquals("review-agent", resources.job.spec.template.spec.serviceAccountName)
assertEquals(0, resources.job.spec.backoffLimit)
assertEquals("Never", resources.job.spec.template.spec.restartPolicy)
assertEquals("classpath:/,file:/config/review.yaml", resources.job.spec.template.spec.containers.single().env.single().value)
assertEquals("review.yaml", resources.configMap.data.keys.single())
assertEquals(true, resources.configMap.immutable)
assertEquals("uid-42", resources.job.metadata.ownerReferences.single().uid)
assertEquals("uid-42", resources.configMap.metadata.ownerReferences.single().uid)
```

Remove assertions for per-request ServiceAccount, Role, RoleBinding, Role rules, and RoleBinding subjects. Keep the
existing six-field `ObservedAgentReviewResources` fixture shape until Task 2; pass `null` for the identity/RBAC fields
while updating `AgentReviewResources` assertions. Remove resource-factory assertions for identity/RBAC objects. Task 2
removes those observation fields and updates all shared fixtures atomically.

- [ ] **Step 2: Run focused tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceFactoryTest
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceGatewayTest
```

Expected: compilation failures because current resource data classes and builders still expose identity/RBAC resources.

- [ ] **Step 3: Simplify the resource factory**

Remove Fabric8 imports and builders for `ServiceAccount`, `Role`, `RoleBinding`, `PolicyRule`, `RoleRef`, and `Subject`.
Remove `SERVICE_ACCOUNT_SUFFIX`, result Role constants, and owner metadata creation for identity/RBAC resources.

Return:

```kotlin
data class AgentReviewResources(
    val configMap: ConfigMap,
    val job: Job,
)
```

Keep ConfigMap and Job names, namespaces, owner references, ConfigMap contents, immutable flag, Job backoff limit,
restart policy, volume, subPath, read-only mount, and exact environment value. Set the Job pod service account to the
literal `review-agent`.

- [ ] **Step 4: Simplify gateway and matcher**

Make `observe` fetch only ConfigMap, Job, and ReviewResultCR. Make `validateDesired` compare only existing ConfigMap and
Job. Make `createDependencies` ensure only ConfigMap. Make `createMissing` call `createDependencies` and then ensure
Job. Remove all ServiceAccount/Role/RoleBinding client calls and matcher methods.

Matching existing resources must still reuse them without updates. Drift in ConfigMap data/metadata or Job
image/template/metadata must throw `AgentReviewResourceConflict`. Transient Fabric8 exceptions remain uncaught for JOSDK
retry.

- [ ] **Step 5: Run resource tests and verify green**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceFactoryTest
./kotlin test --include-module operator --include-classes com.example.AgentReviewResourceGatewayTest
```

Expected: all updated resource and gateway tests pass.

- [ ] **Step 6: Commit the reduced resource model**

```bash
git add operator/src/com/example/AgentReviewResourceFactory.kt operator/src/com/example/AgentReviewResourceGateway.kt operator/src/com/example/AgentReviewResourceMatcher.kt operator/test/com/example/AgentReviewResourceFactoryTest.kt operator/test/com/example/AgentReviewResourceGatewayTest.kt
git commit -m "refactor(operator): reduce demo resources"
```

---

### Task 2: Scope lifecycle and reconciler to `default`

**Files:**

- Modify: `operator/src/com/example/AgentReviewLifecycle.kt`
- Modify: `operator/src/com/example/AgentReviewRequestReconciler.kt`
- Modify: `operator/test/com/example/AgentReviewLifecycleTest.kt`
- Modify: `operator/test/com/example/AgentReviewRequestReconcilerTest.kt`
- Modify: `operator/test/com/example/AgentReviewRequestFixtures.kt`

**Interfaces:**

-
`data class ObservedAgentReviewResources(val configMap: ConfigMap?, val job: Job?, val reviewResult: ReviewResultCR?)`.
- `internal val AGENT_REVIEW_EVENT_SOURCE_NAMES = listOf("configmaps", "jobs", "reviewresults")`.
- `AgentReviewRequestReconciler` remains a Spring `@Component` with
  `@ControllerConfiguration(name = "agent-review-request")`.
- `prepareEventSources` remains `List<EventSource<*, AgentReviewRequestCR>>` and configures each source with
  `.withNamespaces("default")`.

- [ ] **Step 1: Update lifecycle and reconciler tests**

Update every fixture constructor to the three-resource observation shape. Add exact tests:

```kotlin
@Test
fun `unsupported namespace is rejected before resource access`() {
    val gateway = FakeGateway()
    val reconciler = AgentReviewRequestReconciler(gateway, AgentReviewProperties("review-agent:1"))
    val primary = request().apply { metadata.namespace = "other" }

    assertTrue(reconciler.reconcile(primary, mockContext()).isNoUpdate)
    assertFalse(gateway.observed)
}

@Test
fun `informer registrations are limited to default`() {
    val reconciler = AgentReviewRequestReconciler(FakeGateway(), AgentReviewProperties("review-agent:1"))
    val sources = reconciler.prepareEventSources(testEventSourceContext())

    assertEquals(setOf("configmaps", "jobs", "reviewresults"), sources.map { it.name() }.toSet())
    assertTrue(sources.all { it.name() != "serviceaccounts" && it.name() != "roles" && it.name() != "rolebindings" })
}
```

Define `FakeGateway.observed` as a Boolean initialized to `false`; set it to `true` inside `observe`. Define
`mockContext()` by mocking `Context<AgentReviewRequestCR>` and `testEventSourceContext()` with the existing
cache/configuration/client construction already used by the reconciler test. Assert all fixture requests use namespace
`default`.

Keep tests for creation marker retry, post-start missing Job error, conflict terminal Error, unchanged status no-update,
missing URL/PR terminal Error, successful/failed result, and terminal Error/Successful Noop.

- [ ] **Step 2: Run focused tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewLifecycleTest
./kotlin test --include-module operator --include-classes com.example.AgentReviewRequestReconcilerTest
```

Expected: compilation failures from old six-resource observations and cluster-wide informer expectations.

- [ ] **Step 3: Simplify lifecycle observations**

Remove ServiceAccount, Role, and RoleBinding fields from `ObservedAgentReviewResources`. Replace
`hasAllNonJobResources()` with a ConfigMap-only check. Preserve these decisions:

- Terminal `Successful` or `Error`: `Noop`.
- New request with no ConfigMap/Job: `EnsureResources` with `InProgress` and `JOB_CREATION_PENDING_MESSAGE`.
- ConfigMap exists, Job absent, and status is unset/`Pending`/creation-pending: `EnsureResources` so transient Job
  creation remains retryable.
- ConfigMap exists, Job absent, and normal `InProgress`: terminal `Error` with Job-disappeared message; never recreate
  it.
- ConfigMap or Job missing after normal processing: terminal `Error` without recreation.
- Failed Job: terminal `Error`.
- Completed Job without result: terminal `Error` with `review-agent Job completed without publishing a result`.
- ReviewResult `InProgress`: `Wait`; `Completed`: `Successful`; `Failed`: `Error` with result detail.

Keep deterministic status names and existing owner/result behavior.

- [ ] **Step 4: Scope reconciler and event sources**

At the start of reconciliation, after terminal-status handling and before `gateway.observe`, guard the namespace:

```kotlin
if (metadata.namespace != "default") {
    return UpdateControl.noUpdate()
}
```

Keep defensive repository URL/PR validation and resource-conflict handling. Use reduced resource factory/gateway
interfaces. Replace the six event source names with exactly:

```kotlin
internal val AGENT_REVIEW_EVENT_SOURCE_NAMES = listOf(
    "configmaps",
    "jobs",
    "reviewresults",
)
```

Replace `.withWatchAllNamespaces()` with `.withNamespaces("default")` on every `InformerEventSourceConfiguration`. Do
not configure a timer, polling source, or cluster-wide watch.

- [ ] **Step 5: Run lifecycle/reconciler tests and full operator tests**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.AgentReviewLifecycleTest
./kotlin test --include-module operator --include-classes com.example.AgentReviewRequestReconcilerTest
./kotlin test --include-module operator
```

Expected: all lifecycle/reconciler tests and the complete operator test suite pass.

- [ ] **Step 6: Commit namespace-scoped lifecycle**

```bash
git add operator/src/com/example/AgentReviewLifecycle.kt operator/src/com/example/AgentReviewRequestReconciler.kt operator/test/com/example/AgentReviewLifecycleTest.kt operator/test/com/example/AgentReviewRequestReconcilerTest.kt operator/test/com/example/AgentReviewRequestFixtures.kt
git commit -m "feat(operator): scope demo to default namespace"
```

---

### Task 3: Replace cluster RBAC with static default-namespace identities and admission policies

**Files:**

- Modify: `k8s/operator/service-account.yaml` — retain operator ServiceAccount in `default`.
- Delete: `k8s/operator/cluster-role.yaml`
- Delete: `k8s/operator/cluster-role-binding.yaml`
- Create: `k8s/operator/role.yaml`
- Create: `k8s/operator/role-binding.yaml`
- Create: `k8s/operator/review-agent-service-account.yaml`
- Create: `k8s/operator/review-agent-role.yaml`
- Create: `k8s/operator/review-agent-role-binding.yaml`
- Modify: `k8s/operator/deployment.yaml`
- Create: `k8s/operator/validating-admission-policy-default-namespace.yaml`
- Create: `k8s/operator/validating-admission-policy-default-namespace-binding.yaml`
- Modify: `k8s/operator/validating-admission-policy.yaml`
- Modify: `k8s/operator/validating-admission-policy-binding.yaml`
- Modify: `operator/test/com/example/OperatorRbacManifestTest.kt`

**Interfaces:**

- Static operator Role name: `agent-review-operator` in namespace `default`.
- Static review-agent ServiceAccount name: `review-agent` in namespace `default`.
- Static review-agent Role name: `review-agent-result-publisher` in namespace `default`.
- Static admission policy name: `agent-review-request-default-namespace`.
- Existing immutable policy remains named `agent-review-request-spec-immutable` and gains
  `request.namespace == "default"` under `spec.matchConstraints.matchConditions`.

- [ ] **Step 1: Write failing manifest tests**

Update `OperatorRbacManifestTest.kt` to load manifests and assert:

```kotlin
assertEquals("Role", operatorRole.kind)
assertEquals("default", operatorRole.metadata.namespace)
assertTrue(operatorRole.rules.any { it.apiGroups == listOf("example.com") && it.resources == listOf("agentreviewrequests") && it.verbs == listOf("get", "list", "watch") })
assertTrue(operatorRole.rules.any { it.resources == listOf("agentreviewrequests/status") && it.verbs == listOf("get", "update", "patch") })
assertTrue(operatorRole.rules.none { it.apiGroups == listOf("rbac.authorization.k8s.io") })
assertEquals("review-agent", reviewAgentServiceAccount.metadata.name)
assertEquals("review-agent-result-publisher", reviewAgentRole.metadata.name)
assertEquals("default", reviewAgentRole.metadata.namespace)
assertEquals("review-agent", reviewAgentRoleBinding.subjects.single().name)
assertEquals("review-agent-result-publisher", reviewAgentRoleBinding.roleRef.name)
assertEquals("default", defaultNamespacePolicyBinding.metadata.namespace)
```

Assert operator Role has no ServiceAccount/RBAC permissions and review-agent Role has only `reviewresults` and
`reviewresults/status` rules. Assert Deployment uses `agent-review-operator` ServiceAccount and namespace `default`.

- [ ] **Step 2: Run manifest tests and verify expected failure**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.OperatorRbacManifestTest
```

Expected: failure because current manifests still define cluster-wide operator RBAC and no static review-agent identity.

- [ ] **Step 3: Add static namespaced operator RBAC**

Create `k8s/operator/role.yaml` with these exact rules and no others:

```yaml
- apiGroups: ["example.com"]
  resources: ["agentreviewrequests"]
  verbs: ["get", "list", "watch"]
- apiGroups: ["example.com"]
  resources: ["agentreviewrequests/status"]
  verbs: ["get", "update", "patch"]
- apiGroups: ["example.com"]
  resources: ["reviewresults"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["configmaps"]
  verbs: ["get", "list", "watch", "create"]
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create"]
```

Create `role-binding.yaml` binding `agent-review-operator` in `default` to that Role. Delete cluster-scoped operator
RBAC manifests. Keep the Deployment in `default` using the operator ServiceAccount.

- [ ] **Step 4: Add static review-agent identity**

Create `review-agent-service-account.yaml`, `review-agent-role.yaml`, and `review-agent-role-binding.yaml` in `default`.
The Role rules must be exactly:

```yaml
- apiGroups: ["example.com"]
  resources: ["reviewresults"]
  verbs: ["get", "create", "update", "patch"]
- apiGroups: ["example.com"]
  resources: ["reviewresults/status"]
  verbs: ["get", "update", "patch"]
```

Bind `review-agent` to `review-agent-result-publisher`. Do not add owner references to these static resources.

- [ ] **Step 5: Add namespace admission policy and scope immutability policy**

Create a `ValidatingAdmissionPolicy` named `agent-review-request-default-namespace` matching `CREATE` and `UPDATE` of
`agentreviewrequests` in `example.com/v1`, with `failurePolicy: Fail` and validation:

```cel
request.namespace == "default"
```

Create a binding with `validationActions: [Deny]`.

Add this `matchCondition` under `spec.matchConstraints` of `agent-review-request-spec-immutable`:

```yaml
matchConditions:
  - name: default-namespace-only
    expression: request.namespace == "default"
```

Keep its existing UPDATE match and immutable-spec expression. Update its binding metadata/name references only as
required; keep `validationActions: [Deny]`.

- [ ] **Step 6: Validate static manifests and tests**

Run:

```bash
./kotlin test --include-module operator --include-classes com.example.OperatorRbacManifestTest
kubectl apply --dry-run=client -f k8s/crds/agentreviewrequests.example.com-v1.yml
kubectl apply --dry-run=client -f k8s/operator/
git diff --check
```

Expected: manifest tests and both dry runs pass; no cluster-scoped operator Role/RoleBinding remains.

- [ ] **Step 7: Commit static default manifests**

```bash
git add k8s/operator operator/test/com/example/OperatorRbacManifestTest.kt
git commit -m "chore(operator): add default namespace demo RBAC"
```

---

### Task 4: Full verification and artifact review

**Files:**

- Modify: `operator/test/com/example/ExampleTest.kt` only if existing Spring context assertions require the
  namespace-scoped reconciler configuration.
- No other source changes expected.

**Interfaces:**

- Existing CRD and shared model remain unchanged.
- Existing `review-agent` owner-reference publisher remains unchanged.
- Demo request remains `k8s/examples/agent-review-request.yaml` in namespace `default`.

- [ ] **Step 1: Run complete verification**

Run:

```bash
kotlin --help
./kotlin check
./kotlin test
kubectl apply --dry-run=client -f k8s/crds/agentreviewrequests.example.com-v1.yml
kubectl apply --dry-run=client -f k8s/operator/
git diff --check
git status --short
```

Expected: all Kotlin checks/tests pass, 0 test failures, CRD/operator manifests parse, and only unrelated pre-existing
`.idea` modifications remain.

- [ ] **Step 2: Inspect final behavior markers**

Run:

```bash
grep -R -n "default\|serviceAccountName: review-agent\|review-agent-result-publisher\|withNamespaces(\"default\")\|SPRING_CONFIG_LOCATION" operator k8s/operator
! grep -R -n "withWatchAllNamespaces\|escalate\|bind\|kind: ClusterRole\|kind: ClusterRoleBinding" operator k8s/operator
```

Expected: default namespace, static review-agent identity, namespace-scoped informer configuration, exact Spring config
path, and absence of cluster-wide/escalation markers.

- [ ] **Step 3: Commit any required test-only adjustment**

If `ExampleTest.kt` requires an adjustment for the namespaced Spring context, run its focused test and commit only that
adjustment:

```bash
./kotlin test --include-module operator --include-classes com.example.ExampleTest
git add operator/test/com/example/ExampleTest.kt
git commit -m "test(operator): update default namespace context"
```

If no adjustment is needed, make no commit.

- [ ] **Step 4: Final review**

Review `git diff main...HEAD` for:

- No `.idea` changes.
- No cluster-wide operator RBAC.
- No per-request identity/RBAC builders or client calls.
- Exactly ConfigMap and Job per request.
- Static review-agent RBAC limited to ReviewResult resources.
- Default-only admission and informer scope.
- No polling, Helm, or live-cluster tests.

Record residual risk: no live-cluster validation; static review-agent identity is shared by all demo requests in
`default`.

---

## Plan Self-Review

- Spec coverage: default-only namespace, static review-agent identity, reduced ConfigMap/Job lifecycle, namespaced
  operator RBAC, no escalation/bind, namespace-scoped informers, admission denial, immutable spec, owner references,
  ReviewResult publication, lifecycle retries, tests, static manifests, and verification are covered in Tasks 1–4.
- Placeholder scan: no TBD/TODO/“implement later” steps; every implementation step names exact files, values, tests, or
  commands.
- Type consistency: `AgentReviewResources` is reduced in Task 1 before Task 2 consumes it;
  `ObservedAgentReviewResources` is reduced in Task 2 before reconciler and fixtures use it; static manifest names and
  service-account values match resource factory and Deployment.
- Scope: CRD, shared model, review-agent publisher, Jackson dependencies, and generated CRD remain unchanged because
  default-only simplification affects only operator resource construction, reconciliation, manifests, and tests.

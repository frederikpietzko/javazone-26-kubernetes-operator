# Review Agent Custom Resource Status Design

## Goal

Make `review-agent` publish the complete review lifecycle to the namespaced `ReviewResult` custom resource in the local kind cluster.

The agent creates a resource, marks it `InProgress`, runs the Spring AI review, maps the AI response into the CR, and finishes with either `Completed` or `Failed`. Configuration lives in `application-review.yaml` so the same values can later be supplied to a Job pod.

## Scope

### Included

- Add a typed `ReviewResult` data class in `review-agent/src/com/example/Main.kt` for Spring AI structured output.
- Use Fabric8 Kubernetes client directly from `review-agent`.
- Bind CR name and namespace through `ReviewConfiguration.kt`.
- Add an error field to the CR status model.
- Regenerate the CRD through the existing Kotlin Toolchain task.
- Apply the generated CRD to the local kind cluster using `.kubeconfig`.
- Catch review and CR-update failures and persist failure details when the CR exists.
- Add unit tests for configuration binding, pure result mapping, and lifecycle/error behavior through a fake CR publisher.

### Excluded

- End-to-end automated tests invoking a real LLM review.
- Operator SDK controllers, watches, or reconciliation loops.
- Automatic namespace discovery from a pod service account.
- Changes to unrelated operator behavior.

Real-agent verification remains manual because it requires a running model/provider and repository review.

## Configuration

Extend `review-agent/resources/application-review.yaml`:

```yaml
review:
  repository:
    url: https://github.com/frederikpietzko/ebfs-jpa.git
  pr: 1
  kubernetes:
    namespace: default
    name: review-result
```

`ReviewConfiguration.kt` remains the configuration boundary. It continues to provide the shared `Review` bean and adds a required typed configuration value for the target CR namespace/name. The configuration test must cover both values and missing Kubernetes configuration. The kubeconfig is not stored in application configuration; local execution uses:

```bash
KUBECONFIG="$PWD/.kubeconfig"
```

This allows a later Job pod to provide its own Kubernetes client configuration without changing review input configuration.

## CRD model

`ReviewResultCR.kt` remains the source of truth. Expand `ReviewResultStatus`:

```kotlin
class ReviewResultStatus {
    var status: String? = null
    var error: String? = null
}
```

The generated CRD must expose both status fields. Generate it with:

```bash
./kotlin task :crds:generateCrds@build-config
```

Do not edit generated YAML manually. Apply the generated file to the local cluster with:

```bash
kubectl --kubeconfig .kubeconfig apply -f k8s/crds/reviewresults.example.com-v1.yml
```

## Review result model and mapping

Spring AI receives a local `ReviewResult` data class in `review-agent/src/com/example/Main.kt`, separate from the Fabric8 `ReviewResultCR` resource. This avoids asking Spring AI to deserialize Kubernetes metadata, spec, and status as structured output.

The local result contains a list of local comment data classes with `lines` and `comment` fields. It must not reuse Fabric8 resource classes as Spring AI output types. An explicit mapper copies each local comment and its line numbers into `ReviewResultSpec`. The mapper also initializes empty comments when the AI returns no comments.

## Runtime flow

1. Spring creates the `Reviewer` command-line runner and injects the bound review and Kubernetes target configuration. The complete workflow runs inside exception handling, including client creation.
2. The runner creates a Fabric8 Kubernetes client using the standard client resolution chain: `KUBECONFIG` for local execution, in-cluster configuration when later run in a Job pod. The client is closed after the workflow.
3. It creates or replaces the configured namespaced `ReviewResult` resource with an empty spec.
4. It updates the status subresource to `InProgress`; status is updated separately because Kubernetes status subresources may ignore status during create.
5. It invokes Spring AI and deserializes the response into the local `ReviewResult` data class.
6. It maps the response into `ReviewResultSpec` and updates the resource spec.
7. It sets status to `Completed`, clears `error`, and updates the status subresource.
8. If any `Exception` occurs after resource creation, including a status or spec update failure, it sets status to `Failed`, stores a safe exception type/message string in `status.error`, and attempts to update the status subresource.
9. If failure-status persistence itself fails, it logs both the original and secondary exceptions. If initial resource creation fails, no CR exists for error persistence and the runner logs the original exception.
10. After best-effort failure persistence, the runner rethrows the original exception so a future Job exits nonzero and does not report a failed review as a successful process. A secondary persistence exception is attached or logged without replacing the original failure.

Status values are exactly:

```text
InProgress
Completed
Failed
```

The configured CR name is deterministic. `createOrReplace` makes reruns update the same resource rather than creating untracked resources. The implementation tracks whether creation succeeded so it only attempts failure-status persistence when a CR exists.

## Client choice

Use Fabric8 Kubernetes client directly. The review agent performs one bounded CRUD workflow and does not watch resources or reconcile external changes. Java Operator SDK would add controller lifecycle and reconciliation machinery without serving this use case.

Declare the Fabric8 client dependency explicitly in `review-agent/module.yaml` rather than relying on transitive dependencies from the CRD model module. Add this library alias to `libs.versions.toml` using the existing Fabric8 version:

```toml
fabrics8-kubernetes-client = { module = "io.fabric8:kubernetes-client", version.ref = "fabrics8" }
```

Use it from the module as `$libs.fabrics8.kubernetes.client`.

## Testing and verification

Automated tests cover deterministic code only:

- Binding Kubernetes namespace/name through `ReviewConfiguration`, including missing values.
- Mapping local `ReviewResult` into `ReviewResultCR` spec/status.
- Success status and error clearing.
- Failure status and exception message persistence through a fake `ReviewResultPublisher`.
- Failure propagation after best-effort CR error persistence.
- The publisher boundary is injected into the review workflow so lifecycle tests do not need a Kubernetes cluster.

No automated test runs a real Spring AI review or talks to a live model. Real-agent verification is manual only.

Manual verification uses the local cluster:

```bash
./kotlin check
./kotlin task :crds:generateCrds@build-config
kubectl --kubeconfig .kubeconfig apply -f k8s/crds/reviewresults.example.com-v1.yml
./kotlin show tasks
kubectl --kubeconfig .kubeconfig get reviewresult review-result -o yaml
```

Use the existing review-agent run task shown by `./kotlin show tasks`, with both `local` and `review` profiles active so local model settings and review input are loaded, and with `KUBECONFIG="$PWD/.kubeconfig"`. Manual inspection must confirm `status.status`, `status.error` behavior on failure, and mapped review comments under `spec.comments`. Exercise failure manually by supplying an invalid model endpoint or otherwise forcing the review call to fail; verify the process fails after the CR reaches `Failed`.

A future Job pod will need Kubernetes RBAC allowing `get`, `create`, `update`, and `update/status` on `reviewresults.example.com` in its target namespace. RBAC manifests are outside this change.

## Alternatives considered

### Fabric8 typed client — selected

Uses the existing typed CRD model, gives compile-time resource access, and requires only the one-shot create/update operations needed by the agent.

### Java Operator SDK

Appropriate for a long-running controller that watches and reconciles `ReviewResult` resources. Not needed for a review Job that owns one bounded lifecycle.

### `kubectl` subprocess

Avoided because shell invocation makes typed mapping, status-subresource updates, and deterministic unit testing harder.

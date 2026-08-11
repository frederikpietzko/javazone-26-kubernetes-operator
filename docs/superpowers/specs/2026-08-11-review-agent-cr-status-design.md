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
- Add unit tests for mapping and lifecycle/error behavior where practical.

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

`ReviewConfiguration.kt` remains the configuration boundary. It continues to provide the shared `Review` bean and adds a typed configuration value for the target CR namespace/name. The kubeconfig is not stored in application configuration; local execution uses:

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

The local result contains review comments matching the CR spec. An explicit mapper copies each comment and its line numbers into `ReviewResultSpec`. The mapper also initializes empty comments when the AI returns no comments.

## Runtime flow

1. Spring creates the `Reviewer` command-line runner and injects the bound review and Kubernetes target configuration.
2. The runner creates a Fabric8 Kubernetes client using the active kubeconfig.
3. It creates or replaces the configured namespaced `ReviewResult` resource.
4. It updates the status subresource to `InProgress`.
5. It invokes Spring AI and deserializes the response into the local `ReviewResult` data class.
6. It maps the response into `ReviewResultSpec`.
7. It sets status to `Completed`, clears `error`, and updates the resource/status.
8. If review processing fails after resource creation, it sets status to `Failed`, stores the exception message in `status.error`, and updates the status subresource.

Status values are exactly:

```text
InProgress
Completed
Failed
```

If initial CR creation fails, no CR exists for error persistence. The runner logs that failure. If failure-status persistence itself fails, the runner logs the secondary exception without hiding the original failure.

The configured CR name is deterministic. A rerun updates the same resource rather than creating untracked resources.

## Client choice

Use Fabric8 Kubernetes client directly. The review agent performs one bounded CRUD workflow and does not watch resources or reconcile external changes. Java Operator SDK would add controller lifecycle and reconciliation machinery without serving this use case.

Declare the Fabric8 client dependency explicitly in `review-agent/module.yaml` rather than relying on transitive dependencies from the CRD model module.

## Testing and verification

Automated tests cover deterministic code only:

- Binding Kubernetes namespace/name through `ReviewConfiguration`.
- Mapping local `ReviewResult` into `ReviewResultCR` spec/status.
- Success status and error clearing.
- Failure status and exception message persistence behavior using injected client abstractions or test doubles where direct Kubernetes access is not appropriate.

No automated test runs a real Spring AI review or talks to a live model.

Manual verification uses the local cluster:

```bash
./kotlin check
./kotlin task :crds:generateCrds@build-config
kubectl --kubeconfig .kubeconfig apply -f k8s/crds/reviewresults.example.com-v1.yml
./kotlin show tasks
kubectl --kubeconfig .kubeconfig get reviewresult review-result -o yaml
```

Use the existing review-agent run task shown by `./kotlin show tasks`, with `application-review` active and `KUBECONFIG="$PWD/.kubeconfig"`. Manual inspection must confirm `status.status`, `status.error` behavior on failure, and mapped review comments under `spec.comments`.

## Alternatives considered

### Fabric8 typed client — selected

Uses the existing typed CRD model, gives compile-time resource access, and requires only the one-shot create/update operations needed by the agent.

### Java Operator SDK

Appropriate for a long-running controller that watches and reconciles `ReviewResult` resources. Not needed for a review Job that owns one bounded lifecycle.

### `kubectl` subprocess

Avoided because shell invocation makes typed mapping, status-subresource updates, and deterministic unit testing harder.

# Agent Review Request Operator Design

## Goal

Make the `operator` module process `AgentReviewRequest` custom resources by creating a ConfigMap and Kubernetes Job for the configured review-agent image, then asynchronously propagate the review result into request status.

## Scope

This design covers:

- The `AgentReviewRequest` CRD.
- Operator image configuration.
- ConfigMap and Job creation.
- Asynchronous Job and `ReviewResult` observation.
- Request status lifecycle.
- Spec immutability enforcement.
- Unit and controller-level tests.

End-to-end tests that create and destroy a dedicated kind cluster are explicitly out of scope. Existing local kind usage remains available for manual validation only.

## Existing Context

- `operator/src/com/example/Main.kt` starts Spring Boot but contains no controller.
- `operator/module.yaml` already includes Spring Operator Framework, Operator Framework, Fabric8 support, and Spring Operator test dependencies.
- `shared-data-model/src/com/example/Review.kt` defines the review-agent configuration model:
  - `repository.url`
  - `pr`
  - `kubernetes.namespace`
  - `kubernetes.name`
- `review-agent/resources/application-review.yaml` uses the `review` Spring configuration root.
- `review-agent` publishes `ReviewResultCR` with statuses `InProgress`, `Completed`, and `Failed`.
- `crds/src/com/example/ReviewResultCR.kt` defines the existing result resource in group `example.com`, version `v1`.

## Architecture

Use Spring Operator Framework for an event-driven controller. The operator will watch `AgentReviewRequest` resources and their dependent `Job` and `ReviewResult` resources. It will not poll and will not use a reconciliation timer.

The controller owns one request lifecycle:

1. Receive a new `AgentReviewRequest` event.
2. Validate the request spec and configured review-agent image.
3. Compute deterministic names from the request name.
4. Create the ConfigMap containing complete review-agent configuration.
5. Create the Job that mounts the ConfigMap and starts the configured image.
6. Set request status to `InProgress`.
7. React to Job and `ReviewResult` events asynchronously.
8. Set request status to `Successful` or `Error`.
9. Treat terminal requests as no-ops.

Repeated events are safe. Existing resources are reused when their definitions match the desired resources. A conflicting existing resource causes request status to become `Error`; the controller must not silently overwrite it.

## AgentReviewRequest CRD

Add `crds/src/com/example/AgentReviewRequestCR.kt` with group `example.com`, version `v1`, kind `AgentReviewRequest`, plural `agentreviewrequests`, and namespaced scope.

The user-owned spec contains review input:

```yaml
spec:
  repository:
    url: https://github.com/example/repository.git
  pr: "1"
```

The request does not expose `kubernetes` configuration. The operator derives the Kubernetes target from request metadata and generated names. `Review.kt` remains unchanged because the operator constructs the complete `Review` configuration before writing the ConfigMap.

The status contains:

```yaml
status:
  phase: Pending | InProgress | Successful | Error
  message: optional human-readable detail
  jobName: generated Job name
  configMapName: generated ConfigMap name
  reviewResultName: generated ReviewResult name
```

The CRD must enable the `status` subresource. Generated CRD YAML is written under `k8s/crds/` using the existing CRD generation setup; generated files are not manually edited.

## Deterministic Resource Names

Use one stable base name for all dependent resources:

```text
agent-review-<AgentReviewRequest.metadata.name>
```

For example, request `ebfs-jpa-pr-1` produces:

```text
agent-review-ebfs-jpa-pr-1
```

The name generator must:

- Normalize input to DNS-1123-compatible lowercase text.
- Replace unsupported characters with `-`.
- Remove leading and trailing hyphens.
- Truncate to the Kubernetes name limit.
- Append a deterministic short hash when truncation could make two inputs ambiguous.
- Return the same output for the same request name on every reconcile.

Use the base name for ConfigMap, Job, and ReviewResult. Store all three names in status for observability and direct correlation.

## Operator Configuration

Add Spring configuration binding under `agent-review`:

```yaml
agent-review:
  image: ghcr.io/example/review-agent:latest
```

Bind this to an operator properties object with an `image` field. Missing or blank image configuration must prevent successful operator startup. The configured image is used unchanged in created Jobs.

## Generated ConfigMap

Create a ConfigMap in the `AgentReviewRequest` namespace with the deterministic base name. Store one key, `review.yaml`, containing:

```yaml
review:
  repository:
    url: <request.spec.repository.url>
  pr: "<request.spec.pr>"
  kubernetes:
    namespace: <request.metadata.namespace>
    name: <deterministic-base-name>
```

The generated Kubernetes values point `review-agent` at the exact `ReviewResultCR` resource watched by the operator. Mark the ConfigMap immutable after creation. Add an owner reference to the `AgentReviewRequest`.

## Generated Job

Create a namespaced `batch/v1 Job` using the deterministic base name:

```yaml
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: review-agent
          image: <agent-review.image>
          env:
            - name: SPRING_CONFIG_LOCATION
              value: "classpath:/,file:/config/review.yaml"
          volumeMounts:
            - name: review-config
              mountPath: /config/review.yaml
              subPath: review.yaml
              readOnly: true
      volumes:
        - name: review-config
          configMap:
            name: <deterministic-base-name>
```

The Job and its Pod run in the request namespace. `backoffLimit=0` prevents repeated review executions after a failed run. Add an owner reference to the `AgentReviewRequest`.

Do not set `ttlSecondsAfterFinished`; retain Job and ConfigMap data for debugging while the request exists. Kubernetes garbage collection removes owned resources when the request is deleted.

## Asynchronous Result Handling

The operator watches matching `ReviewResultCR` events by namespace and deterministic result name. When the result appears, the operator may add an owner reference if it is absent, then reacts to future status events.

Lifecycle behavior:

| Event | Request phase | Message behavior |
|---|---|---|
| New valid request before dependent resources exist | `Pending`, then `InProgress` | Record creation progress |
| Job active or pending | `InProgress` | Keep current progress message |
| Job completed | `InProgress` | Wait for `ReviewResult` |
| Job failed | `Error` | Include Job failure detail |
| ReviewResult `InProgress` | `InProgress` | Keep current progress message |
| ReviewResult `Completed` | `Successful` | Clear error message |
| ReviewResult `Failed` | `Error` | Copy result error detail |

If ConfigMap or Job creation fails, set `Error` with the operation failure. Do not mark the request `InProgress` until both dependent resources exist.

Terminal `Successful` and `Error` requests are no-op on later events. Status writes must be idempotent and skipped when the desired status already matches the current status.

## Spec Immutability

The controller treats the spec as immutable once processing starts. To enforce this at the API server, deploy a Kubernetes `ValidatingAdmissionPolicy` for `UPDATE` operations on `agentreviewrequests.example.com`.

The policy allows spec changes while the old phase is `Pending` and rejects changes after processing starts. The validation compares `object.spec` with `oldObject.spec` when the old phase is `InProgress`, `Successful`, or `Error`. Status-subresource updates remain allowed because the spec is unchanged.

The local kind cluster runs Kubernetes `v1.36.1`, so `ValidatingAdmissionPolicy` is available.

## Error and Conflict Handling

- Validate repository URL and PR before creating dependent resources.
- Reject missing or blank required fields with `Error` status.
- Reuse existing resources only when names, namespace, owner, image, ConfigMap reference, mount, and environment match the desired definition.
- Set `Error` for conflicting resources instead of force-updating user-owned resources.
- Use owner references for cleanup.
- Preserve terminal status and avoid duplicate Jobs.
- Keep original failure detail in `status.message` without exposing unrelated stack traces unless already supplied by Kubernetes.

## Testing

Add operator tests for:

- `agent-review.image` binding and missing/blank image failure.
- Deterministic DNS-1123 resource name generation, including invalid and long request names.
- ConfigMap content, immutable flag, namespace, owner reference, and complete `review.yaml` output.
- Job image, namespace, restart policy, backoff limit, volume, mount, and exact `SPRING_CONFIG_LOCATION` value.
- New request creation of ConfigMap and Job.
- Idempotent reconciliation with existing matching resources.
- Conflicting resource failure.
- `InProgress` status after successful resource creation.
- Job failure transition to `Error`.
- `ReviewResult` `Completed` transition to `Successful`.
- `ReviewResult` `Failed` transition to `Error` with copied error detail.
- Terminal request no-op behavior.
- Asynchronous event handling without polling.
- CRD schema and status-subresource generation where supported by the existing build setup.

No automated kind-cluster lifecycle test is included. Manual validation against the existing local kind cluster may be performed separately.

## Out of Scope

- Changes to review-agent AI behavior.
- Retry/backoff policy beyond `Job.backoffLimit=0`.
- Automatic reruns after terminal status.
- User-configurable image per request.
- Webhook server implementation.
- Automated end-to-end tests that create or destroy a kind cluster.
- Unrelated cleanup of scaffold `Example` resources.

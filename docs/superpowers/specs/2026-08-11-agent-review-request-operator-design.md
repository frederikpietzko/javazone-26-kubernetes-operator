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
- The operator will use Jackson 3; the project already uses `tools.jackson.module:jackson-module-kotlin`.

## Architecture

Use Spring Operator Framework for an event-driven controller. The operator will watch `AgentReviewRequest` resources, owned `Job` resources, and a cluster-wide cached event source for `ReviewResult` resources. It will not poll and will not use a reconciliation timer.

The controller owns one request lifecycle:

1. Receive a new `AgentReviewRequest` event.
2. Validate the request spec and configured review-agent image.
3. Compute deterministic names from the request name.
4. Create the ConfigMap containing complete review-agent configuration.
5. Create the per-request ServiceAccount, Role, and RoleBinding.
6. Create the Job that mounts the ConfigMap and starts the configured image.
7. Set request status to `InProgress`.
8. React to Job and `ReviewResult` events asynchronously.
9. Set request status to `Successful` or `Error`.
10. Treat terminal requests as no-ops.

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

The request does not expose `kubernetes` configuration. The operator derives the Kubernetes target from request metadata and generated names. `Review.kt` remains the shared source model, with an optional owner-reference field added to `Review.Kubernetes` so the review-agent can publish an owned result.

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

CRD schema validation must require `spec.repository.url` and `spec.pr`. The repository URL must be an absolute `https` URL. The PR remains a string for compatibility with `Review.kt`, but must match one or more decimal digits. The status fields remain optional so Kubernetes can create a request before the first reconciliation.

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

Use the base name for ConfigMap, Job, and ReviewResult. Derive per-review identity resources as `<base-name>-agent` for ServiceAccount, Role, and RoleBinding. Store ConfigMap, Job, and ReviewResult names in status for observability and direct correlation.

If a request is recreated with the same name while its old result is still being garbage-collected, wait and retry while the old result has a deletion timestamp. If an active result with the same deterministic name remains, set the new request to `Error` and require a new request name or manual cleanup.

## Operator Configuration

Add `operator/resources/application.yaml` with a replaceable demo value:

```yaml
agent-review:
  image: review-agent:latest
```

Add Spring configuration binding under `agent-review`. Bind this to an operator properties object with an `image` field. Missing or blank image configuration must prevent successful operator startup. The configured image is used unchanged in created Jobs. The demo value must be replaced with the published image before deployment.

Add the operator module dependencies required by the controller:

- `../crds`
- `../shared-data-model`
- Fabric8 Kubernetes client API, if not already provided as a usable transitive dependency by Spring Operator Framework.
- Jackson 3 YAML: `tools.jackson.dataformat:jackson-dataformat-yaml`.

Construct a `Review` object from request data and derived Kubernetes values, then serialize it with Jackson 3 `tools.jackson.dataformat.yaml.YAMLMapper`. Do not concatenate YAML strings manually or use Jackson 2 packages.

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
    ownerReference:
      apiVersion: example.com/v1
      kind: AgentReviewRequest
      name: <request.metadata.name>
      uid: <request.metadata.uid>
      controller: true
      blockOwnerDeletion: false
```

The generated Kubernetes values point `review-agent` at the exact `ReviewResultCR` resource watched by the operator. Extend `Review.Kubernetes` with an optional owner-reference DTO so existing standalone review-agent configuration remains valid. `review-agent` maps the DTO to Fabric8 `OwnerReference` when creating `ReviewResultCR`.

Mark the ConfigMap immutable after creation. Add an owner reference from ConfigMap to the `AgentReviewRequest`.

## Generated Review-Agent Identity

Create one ServiceAccount, Role, and RoleBinding per request:

- ServiceAccount: `<base-name>-agent`
- Role: `<base-name>-agent`
- RoleBinding: `<base-name>-agent`

The Role grants only:

```yaml
apiGroups:
  - example.com
resources:
  - reviewresults
verbs:
  - get
  - create
  - update
  - patch
---
apiGroups:
  - example.com
resources:
  - reviewresults/status
verbs:
  - get
  - update
  - patch
```

Add owner references from all three resources to `AgentReviewRequest`. The operator ServiceAccount remains separate from the review-agent ServiceAccount.

## Generated Job

Create a namespaced `batch/v1 Job` using the deterministic base name:

```yaml
spec:
  backoffLimit: 0
  template:
    spec:
      serviceAccountName: <base-name>-agent
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

Do not set `ttlSecondsAfterFinished`; retain Job, ConfigMap, and result data for debugging while the request exists. Kubernetes garbage collection removes owned resources when the request is deleted.

## Asynchronous Result Handling

The operator watches `ReviewResultCR` events through a cluster-wide cached owned-resource event source and matches each event by request namespace plus deterministic result name. `review-agent` creates the result with an owner reference supplied in `review.yaml`:

```yaml
controller: true
blockOwnerDeletion: false
```

The direct owner reference enables Kubernetes garbage collection when the request is deleted without requiring review-agent to update the request `/finalizers` subresource. The operator does not adopt, patch, or delete result resources.

Lifecycle behavior:

| Event | Request phase | Message behavior |
|---|---|---|
| New valid request before dependent resources exist | `Pending`, then `InProgress` | Record creation progress |
| Job active or pending | `InProgress` | Keep current progress message |
| Job completed with matching ReviewResult | `InProgress` | Apply ReviewResult status |
| Job completed without matching ReviewResult | `Error` | Report that review-agent completed without publishing a result |
| Job failed | `Error` | Include Job failure detail |
| ReviewResult `InProgress` | `InProgress` | Keep current progress message |
| ReviewResult `Completed` | `Successful` | Clear error message |
| ReviewResult `Failed` | `Error` | Copy result error detail |

If validation or a resource conflict fails, set `Error` with the operation failure. Temporary Kubernetes API, network, cache, or resource-version failures must be returned to the framework for automatic retry rather than converted into terminal `Error`. Do not mark the request `InProgress` until ConfigMap, ServiceAccount, Role, RoleBinding, and Job all exist.

Terminal `Successful` and `Error` requests are no-op on later events. Status writes must be idempotent and skipped when the desired status already matches the current status.

If ConfigMap, ServiceAccount, Role, RoleBinding, or Job disappears during `InProgress`, set terminal `Error` rather than silently recreating it. If a Job disappears after creation, set terminal `Error` rather than recreating it and rerunning the review. A missing `ReviewResult` while the Job is active or before Job completion keeps the request `InProgress`.

## Spec Immutability

The controller treats the spec as immutable once processing starts. To enforce this at the API server, deploy a Kubernetes `ValidatingAdmissionPolicy` for `UPDATE` operations on `agentreviewrequests.example.com`.

The policy allows spec changes while the old phase is `Pending` and rejects changes after processing starts. The validation compares `object.spec` with `oldObject.spec` when the old phase is `InProgress`, `Successful`, or `Error`. Status-subresource updates remain allowed because the spec is unchanged.

The local kind cluster runs Kubernetes `v1.36.1`, so `ValidatingAdmissionPolicy` is available.

## Error and Conflict Handling

- Validate repository URL and PR before creating dependent resources.
- Reject missing or blank required fields with `Error` status.
- Reuse existing ConfigMaps and Jobs only when names, namespace, owner, image, ConfigMap reference, mount, and environment match the desired definition.
- Set `Error` for conflicting resources instead of force-updating user-owned resources.
- Use owner references for ConfigMap, Job, ServiceAccount, Role, RoleBinding, and `ReviewResultCR` cleanup.
- Set `blockOwnerDeletion=false` on the `ReviewResultCR` owner reference.
- Preserve terminal status and avoid duplicate Jobs.
- Keep original failure detail in `status.message` without exposing unrelated stack traces unless already supplied by Kubernetes.

## RBAC and Deployment Manifests

JOSDK does not generate RBAC manifests from Kotlin controller code. The Operator SDK `controller-gen` marker workflow is for Go/controller-runtime projects and is not applicable here. The official JOSDK generic Helm chart can render dynamic RBAC from values, but adding Helm is out of scope for this demo.

Add static manifests under `k8s/operator/`:

- Operator `ServiceAccount`.
- Cluster-wide `ClusterRole` with explicit least-privilege rules.
- `ClusterRoleBinding`.
- Operator `Deployment`.
- `ValidatingAdmissionPolicy` and its binding.

Required resource permissions:

- `agentreviewrequests.example.com`: get/list/watch.
- `agentreviewrequests.example.com/status`: get/update/patch.
- `configmaps`: get/list/watch/create.
- `jobs.batch`: get/list/watch/create.
- `serviceaccounts`: get/list/watch/create.
- `roles.rbac.authorization.k8s.io`: get/list/watch/create.
- `rolebindings.rbac.authorization.k8s.io`: get/list/watch/create.
- `reviewresults.example.com`: get/list/watch.
- `events`: create/patch only if the controller emits Kubernetes Events.

If leader election is enabled, add a separate namespace-scoped `Role` and `RoleBinding` for Lease access. Do not grant wildcard resources or verbs. Cluster-wide watch is required because requests may exist in any namespace. A future `WATCH_NAMESPACE` restriction can replace the cluster-wide binding.

## Testing

Add operator tests for:

- `agent-review.image` binding and missing/blank image failure.
- Deterministic DNS-1123 resource name generation, including invalid and long request names.
- ConfigMap content, immutable flag, namespace, owner reference, and complete `review.yaml` output.
- Job image, namespace, ServiceAccount, restart policy, backoff limit, volume, mount, and exact `SPRING_CONFIG_LOCATION` value.
- Per-request ServiceAccount, Role, and RoleBinding permissions, ownership, and namespace.
- New request creation of ConfigMap and Job.
- Idempotent reconciliation with existing matching resources.
- Conflicting resource failure.
- `InProgress` status after successful resource creation.
- Job failure transition to `Error`.
- `ReviewResult` owner-reference propagation from generated YAML through `review-agent`, including `/status` RBAC.
- `ReviewResult` `Completed` transition to `Successful`.
- `ReviewResult` `Failed` transition to `Error` with copied error detail.
- Successful Job with no matching `ReviewResult` transition to `Error`.
- Missing ConfigMap or Job during `InProgress` transition to `Error` without rerunning.
- Temporary Kubernetes/API failure returned for framework retry.
- Terminal request no-op behavior.
- Asynchronous event handling without polling.
- CRD schema validation and status-subresource generation where supported by the existing build setup.

No automated kind-cluster lifecycle test is included. Manual validation against the existing local kind cluster may be performed separately.

## Out of Scope

- Changes to review-agent AI behavior.
- Retry/backoff policy beyond `Job.backoffLimit=0`.
- Automatic reruns after terminal status.
- User-configurable image per request.
- Webhook server implementation.
- Helm chart packaging or Helm-generated RBAC.
- Automated end-to-end tests that create or destroy a kind cluster.
- Unrelated cleanup of scaffold `Example` resources.

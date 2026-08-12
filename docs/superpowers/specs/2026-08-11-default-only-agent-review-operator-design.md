# Default-Only Agent Review Operator Simplification

**Date:** 2026-08-11

## Goal

Reduce demo complexity and remove RBAC privilege-escalation permissions by limiting the operator to the `default`
namespace and using a pre-created review-agent identity.

## Scope

The operator handles `AgentReviewRequest` resources only in namespace `default`. Requests outside `default` are rejected
by admission. Operator Deployment and static identity resources also live in `default`.

No automated live-cluster or kind end-to-end tests are added.

## Resource lifecycle

For each accepted request in `default`, the operator creates only:

- ConfigMap named `agent-review-<request-name>` containing the generated review-agent YAML.
- Job named `agent-review-<request-name>`.

Both resources use the request as owner with `controller=true` and `blockOwnerDeletion=false`.

The operator does not create or observe per-request ServiceAccounts, Roles, or RoleBindings. The request namespace is
validated before any Kubernetes lookup; the only supported namespace is `default`.

## Static review-agent identity

Static manifests create in `default`:

- ServiceAccount `review-agent`.
- Role granting only:
    - `reviewresults`: `get`, `create`, `update`, `patch`.
    - `reviewresults/status`: `get`, `update`, `patch`.
- RoleBinding connecting `review-agent` to that Role.

Every review Job uses `serviceAccountName: review-agent`. Static identity resources are not owned by individual requests
and are not garbage-collected with requests.

## Operator permissions

Replace cluster-wide operator RBAC with a namespaced Role and RoleBinding in `default`. The operator ServiceAccount is
`agent-review-operator`. Grant only:

- `agentreviewrequests`: `get`, `list`, `watch`.
- `agentreviewrequests/status`: `get`, `update`, `patch`.
- `reviewresults`: `get`, `list`, `watch`.
- `configmaps`: `get`, `list`, `watch`, `create`.
- `jobs`: `get`, `list`, `watch`, `create`.

Remove ServiceAccount, Role, RoleBinding, `escalate`, and `bind` permissions from operator RBAC. The operator receives
no direct write permissions for `ReviewResultCR` resources.

## Controller behavior

Keep existing behavior for:

- Deterministic DNS-1123 naming.
- Jackson 3 YAML serialization.
- ConfigMap mount at `/config/review.yaml`.
- `SPRING_CONFIG_LOCATION=classpath:/,file:/config/review.yaml`.
- Asynchronous Job and ReviewResult informer events.
- `InProgress`, `Successful`, and `Error` status transitions.
- Retry handling for transient Kubernetes API failures.
- Terminal handling for resource conflicts, invalid requests, failed Jobs, missing results, and post-start resource
  disappearance.
- ValidatingAdmissionPolicy immutability after processing starts.

Informer event sources are namespace-scoped to `default`, not cluster-wide. Configure each JOSDK `InformerEventSource`
with the namespace `default`; do not use watch-all-namespaces configuration.

## Admission

Create a `ValidatingAdmissionPolicy` named `agent-review-request-default-namespace` and bind it with
`validationActions: [Deny]`. Match `CREATE` and `UPDATE` operations for `agentreviewrequests` in `example.com/v1`, and
validate:

```cel
request.namespace == "default"
```

Requests outside `default` are denied. Add this match condition to the existing immutability policy's `matchConstraints`
(the binding remains an UPDATE binding):

```cel
request.namespace == "default"
```

Keep spec immutability enforcement for updates after processing starts:

```cel
!has(oldObject.status.phase) ||
oldObject.status.phase == "Pending" ||
object.spec == oldObject.spec
```

## Exact file changes

- Modify `operator/src/com/example/AgentReviewResourceFactory.kt`: return only `AgentReviewResources(configMap, job)`
  and set Job `serviceAccountName` to `review-agent`.
- Modify `../../../operator/src/com/example/AgentReviewClient.kt`: observe and create only ConfigMaps and Jobs; remove
  identity and RBAC operations.
- Modify `operator/src/com/example/AgentReviewRequestReconciler.kt`: use `default`-namespace informers and the reduced
  resource set.
- Modify `operator/src/com/example/AgentReviewLifecycle.kt` and fixtures for ConfigMap/Job-only observations.
- Replace `k8s/operator/cluster-role.yaml` and `cluster-role-binding.yaml` with namespaced `role.yaml` and
  `role-binding.yaml`.
- Create static `k8s/operator/review-agent-service-account.yaml`, `review-agent-role.yaml` (name
  `review-agent-result-publisher`), and `review-agent-role-binding.yaml`.
- Create `k8s/operator/validating-admission-policy-default-namespace.yaml` and binding; update immutable-policy matching
  to `default`.
- Keep operator Deployment and review-agent static resources in `default`.

## Error and cleanup semantics

Admission is authoritative for namespace and spec validation. The namespaced `default` informer means normal
reconciliation cannot receive other namespaces; the reconciler still guards the namespace before resource access and
returns no-op for an unsupported namespace. Invalid or conflicting `default` requests use terminal `Error` status.
Transient Kubernetes API failures remain retryable.

ConfigMaps, Jobs, and owned `ReviewResultCR` objects are garbage-collected with the request. Static review-agent and
operator identity/RBAC resources remain until the demo installation is removed.

## Tests

Update resource-builder tests to assert:

- Only ConfigMap and Job are built.
- Job uses `serviceAccountName: review-agent`.
- No dynamic identity resources are created.
- Owner references remain on ConfigMap and Job.

Update gateway and reconciler tests to cover:

- Namespace validation for `default`.
- Namespaced informer registration.
- ConfigMap and Job create-only behavior.
- Existing lifecycle, conflict, retry, and status behavior.

Update manifest checks for namespaced operator RBAC, static review-agent RBAC, default namespace, and admission policy.

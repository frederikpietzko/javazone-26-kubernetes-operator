# Default-Only Agent Review Operator Simplification

**Date:** 2026-08-11

## Goal

Reduce demo complexity and remove RBAC privilege-escalation permissions by limiting the operator to the `default` namespace and using a pre-created review-agent identity.

## Scope

The operator handles `AgentReviewRequest` resources only in namespace `default`. Requests outside `default` are rejected by admission. Operator Deployment and static identity resources also live in `default`.

No automated live-cluster or kind end-to-end tests are added.

## Resource lifecycle

For each accepted request, the operator creates only:

- ConfigMap named `agent-review-<request-name>` containing the generated review-agent YAML.
- Job named `agent-review-<request-name>`.

Both resources use the request as owner with `controller=true` and `blockOwnerDeletion=false`.

The operator does not create or observe per-request ServiceAccounts, Roles, or RoleBindings.

## Static review-agent identity

Static manifests create in `default`:

- ServiceAccount `review-agent`.
- Role granting only:
  - `reviewresults`: `get`, `create`, `update`, `patch`.
  - `reviewresults/status`: `get`, `update`, `patch`.
- RoleBinding connecting `review-agent` to that Role.

Every review Job uses `serviceAccountName: review-agent`. Static identity resources are not owned by individual requests and are not garbage-collected with requests.

## Operator permissions

Replace cluster-wide operator RBAC with a namespaced Role and RoleBinding in `default`. Grant only permissions needed to:

- Read/watch `agentreviewrequests`, `reviewresults`, ConfigMaps, and Jobs.
- Patch `agentreviewrequests/status`.
- Create ConfigMaps and Jobs.

Remove ServiceAccount, Role, RoleBinding, `escalate`, and `bind` permissions from operator RBAC. The operator receives no direct write permissions for `ReviewResultCR` resources.

## Controller behavior

Keep existing behavior for:

- Deterministic DNS-1123 naming.
- Jackson 3 YAML serialization.
- ConfigMap mount at `/config/review.yaml`.
- `SPRING_CONFIG_LOCATION=classpath:/,file:/config/review.yaml`.
- Asynchronous Job and ReviewResult informer events.
- `InProgress`, `Successful`, and `Error` status transitions.
- Retry handling for transient Kubernetes API failures.
- Terminal handling for resource conflicts, invalid requests, failed Jobs, missing results, and post-start resource disappearance.
- ValidatingAdmissionPolicy immutability after processing starts.

Informer event sources are namespace-scoped to `default`, not cluster-wide.

## Admission

Add a default-namespace admission rule for `AgentReviewRequest` create/update operations. Requests outside `default` are denied.

Keep spec immutability enforcement for updates after processing begins:

```cel
!has(oldObject.status.phase) ||
oldObject.status.phase == "Pending" ||
object.spec == oldObject.spec
```

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

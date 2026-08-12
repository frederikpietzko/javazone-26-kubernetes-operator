# Live-Coding `AgentReviewRequestReconciler.reconcile`

## Goal

Live-code the `reconcile` method from top to bottom, adding one piece of behavior at a time.

The audience should see the method grow into this flow:

```text
request event
  -> inspect request and current resources
  -> decide what is missing or completed
  -> create missing resources or update status
  -> wait for the next Kubernetes event
```

Do not start with a tour of every helper, status type, or manifest. Introduce those naturally when the next line of
`reconcile` needs them.

## Starting Point

Begin in `AgentReviewRequestReconciler.kt` with the existing method removed or reduced to its smallest shell. Keep
existing helper methods and collaborators available:

- `AgentReviewLifecycle.decide`
- `AgentReviewClient`
- `AgentReviewResourceFactory`
- `updateIfStatusChanged`
- `prepareEventSources`

The live-coding target is the method body. Do not redesign helpers during this pass.

## Coding Sequence

### 1. Ignore work that cannot continue

Add the first guard clauses:

- If request already has `Successful` status, return `UpdateControl.noUpdate()`.
- If request already has `Error` status, return `UpdateControl.noUpdate()`.
- If request is outside `default`, return `UpdateControl.noUpdate()`.

This gives the method its first useful behavior: terminal requests are stable and the demo remains namespace-scoped.

**Checkpoint:** Explain that reconciliation can run many times. Returning early prevents terminal requests from starting
work again.

### 2. Read the request and reject invalid input

Extract the metadata needed later:

- namespace
- name
- UID

Then validate the request fields:

- repository URL exists
- pull request number exists

For invalid input, return a terminal error through the existing status helper. Keep this before any gateway call.

**Checkpoint:** Create a malformed request if useful. Show that the operator reports an error without creating a
ConfigMap or Job.

### 3. Observe the current resources

Derive the deterministic base name from the request name and ask the gateway for the current state:

- ConfigMap
- Job
- ReviewResult

At this point, pause and state the key reconciliation rule: never assume this is the first invocation. The method must
work when resources are absent, partially created, running, completed, or conflicting.

### 4. Build the desired resources

Create the desired ConfigMap and Job through `AgentReviewResourceFactory`.

Pass the request, configured image, and configured OpenAI base URL. Do not construct Kubernetes objects inside
`reconcile`; only call the existing factory.

The method now has both sides of the comparison:

```text
observed resources + desired resources
```

### 5. Check for conflicts before changing anything

Add the gateway validation call.

If an existing ConfigMap or Job does not match the desired resource, return the existing conflict status instead of
overwriting it.

Then check an observed ReviewResult's owner reference. If it belongs to another request, return an error; if it is
terminating, preserve the existing conflict behavior.

**Checkpoint:** Show why owner references are a safety boundary. A request may manage only its own result and dependent
resources.

### 6. Ask the lifecycle helper what the state means

Call `AgentReviewLifecycle.decide(primary, observed)` and store the decision.

Do not repeat lifecycle rules in the reconciler. From this point onward, the method translates each decision into a
small Kubernetes action or status response.

### 7. Implement `EnsureResources`

Handle the creation path first because it is the easiest path to demonstrate:

1. If this is the first reconciliation and no Job exists, create the ConfigMap dependency.
2. Patch the request status to `InProgress` with the pending message.
3. On the next reconciliation, create the missing Job as well.
4. Use the gateway's idempotent create methods instead of calling the Kubernetes client directly.

The audience should see why the operator creates the ConfigMap before the Job: the Job mounts the ConfigMap's review
configuration.

### 8. Add the remaining decision branches

Use a `when` expression for the decisions that remain:

- `Wait`: leave resources alone and patch only if status changed.
- `Successful`: patch the terminal successful status.
- `Error`: patch the terminal error status.
- `Noop`: return `UpdateControl.noUpdate()`.

Keep every branch short. The lifecycle helper has already decided what happened; the reconciler only applies the result.

### 9. Make repeated events harmless

Finish by checking the behavior for repeated calls:

- Existing resources are not recreated.
- Identical status is not patched again.
- Successful and failed requests remain terminal.
- No polling, sleep, timer, or reschedule is added.

Point out that ConfigMap, Job, and ReviewResult events cause the next invocation. The method does not wait for work
synchronously.

### 10. Run the complete flow

Use the local kind cluster after the method is complete:

1. Create an `AgentReviewRequest`.
2. Watch it become `InProgress`.
3. Inspect the ConfigMap and Job.
4. Watch the review-agent Job publish a ReviewResult.
5. Watch the request become `Successful`.
6. Point back to the `EnsureResources`, `Wait`, and `Successful` branches that handled the events.

Use one failure example only if time allows, such as a failed Job or malformed request.

## Live-Coding Rhythm

For each step:

1. State the behavior being added.
2. Add the smallest code block that provides it.
3. Compile or run the focused test.
4. Trigger the relevant branch when practical.
5. Move to the next branch.

Keep the method visible throughout. Avoid switching to helper implementations unless a line cannot be understood without
them.

## Deferred Refactoring

After the live-coding behavior works, start a separate refactoring pass for:

- reducing repeated `requireNotNull` calls
- simplifying nested conditionals
- making request validation explicit
- making observed-resource state less nullable
- extracting small coordinator functions only where they improve the live-coding story

Do not mix those changes into the first pass. The first pass should teach the reconciliation flow; the second should
improve its shape.

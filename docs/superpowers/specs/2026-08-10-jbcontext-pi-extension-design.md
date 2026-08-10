# JetBrains Context Pi Extension Design

## Goal

Keep the JetBrains Context semantic index refreshed for Pi sessions, matching the
existing Claude Code user setup without adding a second search interface.

## Current Claude Code Setup

Claude Code installs two global hooks in `~/.claude/settings.json`:

- `SessionStart`
- `SessionEnd`

Both hooks run the following command asynchronously with an empty matcher:

```bash
/Users/frederik.pietzko/.jbcontext/bin/jbcontext_binary index --silent
```

The command runs in the session working directory. JetBrains Context indexes the
repository at its current `HEAD` revision and stores the semantic snapshot outside
the session transcript. `jbcontext search` queries that snapshot; indexing does not
inject source code into the model context and does not provide conversation memory.

The current setup has no file watcher and no per-tool indexing hook. Changes made
after session startup can remain absent from the index until another session
boundary or an explicit index command.

## Architecture

Create one global Pi extension:

```text
~/.pi/agent/extensions/jbcontext-index.ts
```

Pi auto-discovers extensions from this directory, so no settings change is needed.
The extension resolves the stable JetBrains Context launcher at:

```text
~/.jbcontext/bin/jbcontext_binary
```

Every index invocation passes the working directory explicitly:

```bash
jbcontext_binary index --silent --project-path <ctx.cwd>
```

Explicit project paths prevent indexing from depending on child-process working
directory behavior while preserving one index per active project.

## Lifecycle

### Session start

Register a `session_start` handler for every session-start reason:

- `startup`
- `reload`
- `new`
- `resume`
- `fork`

Launch indexing without awaiting it. Pi can start responding while the index is
being refreshed, matching Claude Code's asynchronous `SessionStart` hook.

### Session shutdown

Register a `session_shutdown` handler. Wait for the index operation to finish so
final edits have an opportunity to reach JetBrains Context before Pi tears down the
session.

### Overlapping operations

Keep the active index promise for the extension instance. If a shutdown occurs
while a start refresh is running, await the existing operation instead of launching
a second index process. Clear the promise after completion, including failure.

### Manual command

Register `/jbcontext-index` to run a synchronous refresh for the current working
directory. The command reports success or failure through Pi's UI and remains
available when automatic lifecycle indexing has failed.

## Error Handling

- Missing launcher, process failures, and non-zero exit codes must never prevent Pi
  from starting or shutting down.
- Automatic lifecycle failures are logged without injecting output into the model
  conversation.
- Manual command failures are shown as a warning/error notification containing the
  command's diagnostic output when available.
- The index command always uses `--silent`, preventing its normal output from
  polluting Pi's model context or terminal UI.
- The extension does not run a watcher or index after every `write`/`edit` tool call;
  lifecycle refresh remains the intentional Claude-compatible behavior.

## Search Integration

The extension does not register a custom `jbcontext_search` tool. The existing
`context-search` skill remains the discovery policy: use `jbcontext search` when
code location or behavior is unknown, then inspect returned files locally. This
keeps indexing and search policy separate and avoids duplicate tool behavior.

## Verification

Verification must cover:

1. TypeScript/runtime loading through Pi's extension loader in print mode.
2. Automatic startup loading without an extension error.
3. Manual command registration and execution through a small extension harness.
4. Successful `jbcontext status` output after indexing.
5. Failure handling with an unavailable launcher, confirming Pi remains usable.
6. Git diff inspection confirming no unrelated repository files are staged.

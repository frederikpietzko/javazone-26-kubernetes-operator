# CRD Generation Task Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an explicit Kotlin Toolchain `generateCrds` task that compiles the operator first, scans its compiled classes with Fabric8, and writes CRD YAML to `operator/src/main/resources/META-INF/fabric8`.

**Architecture:** `build-config` exposes a task action consuming `CompilationArtifact` and `Classpath`. `${module.jar}` supplies the compilation prerequisite; the compiled JAR is scanned and loaded with `${module.compileClasspath}`. The task writes directly to the conventional Fabric8 resource directory and is not listed in `generated.resources`, preserving explicit invocation.

**Tech Stack:** Kotlin Toolchain plugin API, Fabric8 CRD Generator v2 `7.8.0`, Kotlin CLI, YAML module/plugin configuration.

## Global Constraints

- Task invocation remains explicit: `./kotlin task :operator:generateCrds@build-config`.
- Compilation must precede generation through `${module.jar}` task input.
- Use Fabric8 CRD Generator v2 APIs already declared in `build-config/module.yaml`.
- Use `${module.compileClasspath}` to resolve custom-resource dependencies and annotations.
- Write generated files under `operator/src/main/resources/META-INF/fabric8`.
- Do not add `generated.resources`; normal builds must not invoke generation.
- Fail explicitly when no custom-resource classes are discovered.
- Use `./kotlin` for project build, check, and task commands.
- Preserve unrelated existing modification `.idea/workspace.xml`.

## File Map

- Create: `build-config/plugin.yaml` — declarative registration of `generateCrds`.
- Modify: `build-config/src/generateCrds.kt` — Fabric8 collection and generation action.
- Modify: `project.yaml` — register local `build-config` plugin.
- Modify: `operator/module.yaml` — enable `build-config` for `operator`.
- Modify: `operator/src/com/example/Main.kt` — add CRD group metadata required by Fabric8 scanning.
- Create during verification: `operator/src/main/resources/META-INF/fabric8/*.yaml` — generated CRDs; retain as generated project output unless user requests cleanup.

### Task 1: Implement Fabric8 generation action

**Files:**
- Modify: `build-config/src/generateCrds.kt`
- Create: `build-config/plugin.yaml`

**Interfaces:**
- Consumes `org.jetbrains.amper.plugins.CompilationArtifact`, `org.jetbrains.amper.plugins.Classpath`, and `java.nio.file.Path`.
- Produces generated YAML under the `@Output` `Path`.
- Public action signature:

```kotlin
@TaskAction
fun generateCrds(
    @Input compilationArtifact: CompilationArtifact,
    @Input compileClasspath: Classpath,
    @Output outputDir: Path,
)
```

- [ ] **Step 1: Replace the stub action with typed inputs and output**

Update `build-config/src/generateCrds.kt` imports and action body:

```kotlin
package com.example

import io.fabric8.crd.generator.collector.CustomResourceCollector
import io.fabric8.crdv2.generator.CRDGenerator
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
@TaskAction
fun generateCrds(
    @Input compilationArtifact: CompilationArtifact,
    @Input compileClasspath: Classpath,
    @Output outputDir: Path,
) {
    outputDir.deleteRecursively()
    outputDir.createDirectories()

    val compiledArtifact = compilationArtifact.artifact
    val classpathElements = (compileClasspath.resolvedFiles + compiledArtifact)
        .distinct()
        .map(Path::toString)

    val collector = CustomResourceCollector()
        .withParentClassLoader(Thread.currentThread().contextClassLoader)
        .withClasspathElements(classpathElements)
        .withFilesToScan(listOf(compiledArtifact.toFile()))

    val customResourceClasses = collector.findCustomResourceClasses()
    check(customResourceClasses.isNotEmpty()) {
        "No Fabric8 custom resources found in ${compiledArtifact.fileName}"
    }

    val generationInfo = CRDGenerator()
        .customResourceClasses(customResourceClasses)
        .inOutputDir(outputDir.toFile())
        .detailedGenerate()

    generationInfo.crdDetailsPerNameAndVersion.forEach { (crdName, versionToInfo) ->
        println("Generated CRD $crdName:")
        versionToInfo.forEach { (version, info) ->
            println(" $version -> ${info.filePath}")
        }
    }
}
```

- [ ] **Step 2: Register task action declaratively**

Create `build-config/plugin.yaml`:

```yaml
tasks:
  generateCrds:
    action: !com.example.generateCrds
      compilationArtifact: ${module.jar}
      compileClasspath: ${module.compileClasspath}
      outputDir: ${module.rootDir}/src/main/resources/META-INF/fabric8
```

Do not add a `generated:` section. The task output is intentionally a source-resource path so the task remains explicit.

- [ ] **Step 3: Compile plugin and inspect task configuration**

Run:

```bash
./kotlin check
./kotlin show tasks
```

Expected:

- `./kotlin check` succeeds.
- Task list includes a registered `generateCrds` task for `operator` after Task 2 enables the plugin; before enablement, plugin compilation still succeeds.
- No YAML schema/configuration diagnostic reports an invalid action parameter.

- [ ] **Step 4: Commit action implementation**

```bash
git add build-config/src/generateCrds.kt build-config/plugin.yaml
git commit -m "feat(build-config): add CRD generation task"
```

### Task 2: Register and enable plugin for operator

**Files:**
- Modify: `project.yaml`
- Modify: `operator/module.yaml`
- Modify: `operator/src/com/example/Main.kt`

**Interfaces:**
- `project.yaml` registers `./build-config` as a local plugin.
- `operator/module.yaml` enables plugin ID `build-config`.
- `Example` exposes `@Group("example.com")` and existing `@Version("v1")` metadata for collector discovery.

- [ ] **Step 1: Register local plugin in project**

Update `project.yaml`:

```yaml
modules:
  - build-config
  - operator

plugins:
  - ./build-config
```

- [ ] **Step 2: Enable plugin in operator module**

Add this top-level block to `operator/module.yaml`:

```yaml
plugins:
  build-config: enabled
```

Keep existing product, settings, dependencies, and test-dependencies unchanged.

- [ ] **Step 3: Add required Fabric8 group annotation**

Update `operator/src/com/example/Main.kt`:

```kotlin
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Version
```

Annotate the custom resource:

```kotlin
@Group("example.com")
@Version("v1")
class Example : CustomResource<WebappSpec, WebappStatus>(), Namespaced
```

- [ ] **Step 4: Verify task graph exposes compilation prerequisite**

Run:

```bash
./kotlin show tasks
```

Expected task graph contains an operator task whose dependency chain includes compilation/jar before `generateCrds`; no generated-resource task is introduced.

- [ ] **Step 5: Commit plugin wiring**

```bash
git add project.yaml operator/module.yaml operator/src/com/example/Main.kt
git commit -m "feat(operator): enable CRD generation plugin"
```

### Task 3: Generate and validate CRD output

**Files:**
- Create during task execution: `operator/src/main/resources/META-INF/fabric8/*.yaml`

**Interfaces:**
- Runs the explicit public command `./kotlin task :operator:generateCrds@build-config`.
- Expects one or more CRD YAML files for annotated `Example`.

- [ ] **Step 1: Run project checks**

```bash
./kotlin check
```

Expected: exit code `0`.

- [ ] **Step 2: Run explicit generation task**

```bash
./kotlin task :operator:generateCrds@build-config
```

Expected:

- operator compilation/jar tasks run before `generateCrds`;
- output includes `Generated CRD`;
- command exits `0`.

- [ ] **Step 3: Verify generated file and schema**

```bash
test -d operator/src/main/resources/META-INF/fabric8
test "$(find operator/src/main/resources/META-INF/fabric8 -type f -name '*.yaml' | wc -l | tr -d ' ')" -gt 0
grep -R -q 'apiVersion: apiextensions.k8s.io/v1' operator/src/main/resources/META-INF/fabric8
grep -R -q 'group: example.com' operator/src/main/resources/META-INF/fabric8
grep -R -q 'version: v1' operator/src/main/resources/META-INF/fabric8
grep -R -q 'spec:' operator/src/main/resources/META-INF/fabric8
grep -R -q 'status:' operator/src/main/resources/META-INF/fabric8
```

Expected: every command succeeds.

- [ ] **Step 4: Verify execution avoidance**

Run the same explicit task twice without source/classpath changes:

```bash
./kotlin task :operator:generateCrds@build-config
./kotlin task :operator:generateCrds@build-config
```

Expected: second invocation does not regenerate changed YAML when inputs and output state are unchanged.

- [ ] **Step 5: Verify regeneration after source change**

Touch the operator source, then rerun:

```bash
touch operator/src/com/example/Main.kt
./kotlin task :operator:generateCrds@build-config
```

Expected: compilation and generation rerun; generated YAML remains valid.

- [ ] **Step 6: Check final diff**

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. Existing `.idea/workspace.xml` remains unrelated and is not staged.

- [ ] **Step 7: Commit generated CRD resources**

The output directory is the operator's source-resource location, so retain the generated YAML and commit it as the initial CRD resource set:

```bash
git add operator/src/main/resources/META-INF/fabric8
git commit -m "chore(operator): generate CRD manifests"
```

## Plan Self-Review

- Spec coverage: task action, explicit invocation, compilation prerequisite, classpath loading, Fabric8 scanning, `@Group` metadata, output location, failure on zero resources, registration, and validation are covered by Tasks 1–3.
- Placeholder scan: no `TBD`, `TODO`, or unspecified implementation steps.
- Type consistency: `CompilationArtifact.artifact` is `Path`; `Classpath.resolvedFiles` is `List<Path>`; both match the action code and YAML references.
- Scope: one plugin feature with target-module wiring; no independent subsystem remains.

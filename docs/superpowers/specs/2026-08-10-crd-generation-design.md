# CRD Generation Task Design

## Goal

Add an explicit Kotlin Toolchain plugin task that generates Fabric8 CustomResourceDefinitions (CRDs) for the `operator` module from its compiled custom-resource classes.

The task must not run during normal builds. Running it explicitly must compile the target module first because Fabric8 scans compiled classes.

## Scope

### Files

- `build-config/plugin.yaml`
  - Register `generateCrds` task.
- `build-config/src/generateCrds.kt`
  - Implement task action using Fabric8 CRD Generator v2 APIs.
- `project.yaml`
  - Register local `build-config` plugin.
- `operator/module.yaml`
  - Enable `build-config` plugin.
- `operator/src/com/example/Main.kt`
  - Add Fabric8 `@Group` annotation required by the collector.
- `docs/superpowers/specs/2026-08-10-crd-generation-design.md`
  - Record this design.

No generated-resource declaration is added. This keeps generation explicit rather than making it an implicit build dependency.

## Task Configuration

`build-config/plugin.yaml` registers one task named `generateCrds` using the fully qualified `com.example.generateCrds` action.

The action receives:

- `@Input compilationArtifact: CompilationArtifact` from `${module.jar}`.
- `@Input compileClasspath: Classpath` from `${module.compileClasspath}`.
- `@Output outputDir: Path` at `${module.rootDir}/src/main/resources/META-INF/fabric8`.

The compilation artifact input makes compilation a prerequisite through Kotlin Toolchain task dependency inference. The output is intentionally a source-resource directory, not `${taskOutputDir}` registered as `generated.resources`, so ordinary builds do not invoke the task.

Explicit command:

```text
./kotlin task :operator:generateCrds@build-config
```

## Generation Flow

1. Kotlin Toolchain resolves `operator`'s compiled JAR and compile classpath.
2. The action deletes stale content from `META-INF/fabric8` and recreates the directory.
3. `CustomResourceCollector` receives:
   - the task context classloader as parent;
   - resolved compile classpath plus compiled artifact as classloader elements;
   - compiled JAR as scan input.
4. The collector discovers classes implementing Fabric8 `HasMetadata` and carrying both `@Group` and `@Version`.
5. If no custom resources are found, the action fails instead of silently succeeding.
6. `CRDGenerator` writes CRD YAML into `META-INF/fabric8`.
7. `CRDGenerationInfo` is used to print generated CRD names, versions, and paths.

The current `Example` resource receives a group such as `example.com`; its existing `@Version("v1")` remains unchanged.

## Error Handling

- Missing or invalid compiled artifacts fail through normal exception propagation.
- Class-loading and generator failures are not swallowed.
- Empty discovery fails with an explicit error.
- Existing generated output is removed before each generation, preventing stale CRDs after resource removal or renaming.

## Validation

Run with the Kotlin CLI:

1. `./kotlin check`
2. `./kotlin task :operator:generateCrds@build-config`
3. Confirm YAML exists below `operator/src/main/resources/META-INF/fabric8/`.
4. Inspect generated YAML for `apiextensions.k8s.io/v1`, the expected resource group/version, and `spec`/`status` schemas.
5. Run the explicit task again and confirm execution avoidance when inputs and outputs are unchanged.
6. Change the operator custom-resource source, rerun the task, and confirm regenerated output.
7. Run `git diff --check`.

## Alternatives Considered

### Direct API task writing source resources — selected

Uses Fabric8 APIs already present in the plugin dependencies, scans the compiled artifact, and preserves explicit invocation. Generated YAML lands in the conventional Fabric8 resource location.

### Task output plus `generated.resources`

Provides cleaner build-directory isolation but causes generation to participate in normal resource/build processing, conflicting with the explicit-task requirement.

### Fabric8 CLI process

Adds a CLI dependency and process boundary without solving a requirement that the direct API does not already solve.

## Design Decisions

- Use compiled JAR scanning rather than source scanning: Fabric8 CRD Generator operates on compiled class metadata and the Kotlin Toolchain exposes the compilation artifact directly.
- Use `${module.compileClasspath}` for class loading: custom-resource superclasses, annotations, and dependent model types must be resolvable.
- Write to `src/main/resources/META-INF/fabric8`: this is the expected discovery/package location and avoids implicit task registration through generated resources.
- Fail on zero resources: a successful task with no CRD output hides missing annotations or incorrect scanning configuration.

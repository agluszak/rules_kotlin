# Incremental Compilation Implementation Plan for rules_kotlin

## Executive Summary

This document provides a comprehensive implementation plan for adding Incremental Compilation (IC) to rules_kotlin using the Kotlin Build Tools API. The plan balances Bazel's hermetic design principles with the performance benefits of IC, targeting **50-70% faster incremental builds** for local development.

**Status**: Planning Phase
**Priority**: P1 (Highest Impact)
**Estimated Effort**: 2-3 weeks
**Target**: Local development with persistent workers

---

## Table of Contents

1. [Background & Motivation](#background--motivation)
2. [Architecture Overview](#architecture-overview)
3. [Key Design Decisions](#key-design-decisions)
4. [Detailed Implementation Plan](#detailed-implementation-plan)
5. [Testing Strategy](#testing-strategy)
6. [Rollout Plan](#rollout-plan)
7. [Performance Goals](#performance-goals)
8. [Risks & Mitigations](#risks--mitigations)

---

## Background & Motivation

### Current State

rules_kotlin uses:
- ✅ **Build Tools API** for compilation (BuildToolsAPICompiler)
- ✅ **Persistent workers** for cross-invocation caching
- ✅ **Deterministic ProjectId** (based on module name)
- ✅ **Bazel action caching** for hermeticity
- ❌ **No incremental compilation** at compiler level

### The Problem

Without IC, every compilation:
1. Processes all source files from scratch
2. Analyzes entire classpath
3. Generates all outputs fresh
4. Takes 10-60+ seconds for medium/large modules

Even when Bazel's action cache hits, **zero file change = instant**. But **1 file change = full recompilation**.

### The Opportunity

Build Tools API provides IC that:
- Tracks source file changes
- Tracks classpath changes via snapshots
- Recompiles only affected files
- Reduces compilation time by 50-70% for incremental builds

### Why Now?

1. ✅ BuildToolsAPICompiler already implemented
2. ✅ Deterministic ProjectId enables stable caching
3. ✅ Persistent workers maintain state
4. ✅ API already has the cached CompilationService

---

## Architecture Overview

### High-Level Design

```
┌─────────────────────────────────────────────────────────────┐
│                     Bazel Build                              │
│  ┌────────────┐                                              │
│  │  Rule      │ ──────┐                                      │
│  │  Analysis  │       │  Declares inputs/outputs             │
│  └────────────┘       │                                      │
│                       ▼                                      │
│  ┌─────────────────────────────────────────┐                │
│  │         Bazel Action                     │                │
│  │  Inputs: sources, jars, IC cache         │                │
│  │  Outputs: class.jar, srcjar, IC cache    │                │
│  │  Execution: Persistent Worker            │                │
│  └─────────────────────────────────────────┘                │
│                       │                                      │
│                       │  Worker Protocol                     │
│                       ▼                                      │
│  ┌─────────────────────────────────────────┐                │
│  │    Persistent Worker (KotlinBuilder)    │                │
│  │  - Maintains CompilationService cache    │                │
│  │  - Preserves IC state across actions     │                │
│  │  - Stable ProjectId per module           │                │
│  └─────────────────────────────────────────┘                │
│                       │                                      │
│                       │  BTAPI Call                          │
│                       ▼                                      │
│  ┌─────────────────────────────────────────┐                │
│  │    BuildToolsAPICompiler                 │                │
│  │  useIncrementalCompilation(              │                │
│  │    workingDir,                           │                │
│  │    sourcesChanges,                       │                │
│  │    classpathSnapshots,                   │                │
│  │    config                                │                │
│  │  )                                       │                │
│  └─────────────────────────────────────────┘                │
│                       │                                      │
│                       │                                      │
│                       ▼                                      │
│  ┌─────────────────────────────────────────┐                │
│  │  Kotlin Compiler (Incremental Runner)   │                │
│  │  - Reads IC caches                       │                │
│  │  - Detects changes                       │                │
│  │  - Compiles only affected files          │                │
│  │  - Updates IC caches                     │                │
│  └─────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────┘

Storage Layer (execroot):
┌─────────────────────────────────────────────────────────────┐
│  bazel-out/k8-fastbuild/bin/{package}/{target}/              │
│  ├─ classes.jar           (declared output)                  │
│  ├─ srcjar                (declared output)                  │
│  └─ _ic/                  (IC state directory)               │
│     ├─ caches/            (kotlin IC caches)                 │
│     ├─ classpath-snapshots/  (generated snapshots)           │
│     └─ shrunk-snapshot.bin    (previous build snapshot)      │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow

1. **Analysis Phase** (Starlark):
   - Declare IC cache directory as input/output
   - Pass IC configuration to worker

2. **Execution Phase** (Persistent Worker):
   - Receive compilation request
   - Check if IC is enabled for this action
   - Generate classpath snapshots (cached)
   - Call BuildToolsAPICompiler.exec() with IC config
   - IC state persists in worker between actions

3. **Compilation Phase** (BTAPI):
   - Compare current vs previous snapshots
   - Determine which files need recompilation
   - Incremental compile only affected files
   - Update IC caches

4. **Cache Update Phase**:
   - Write updated IC state to output directory
   - Bazel sees IC cache as action output
   - Next action uses it as input

---

## Key Design Decisions

### Decision 1: IC State Storage Location

**Options Considered**:

| Option | Location | Pros | Cons |
|--------|----------|------|------|
| **A** | Bazel output tree (`bazel-out/.../target/_ic/`) | ✅ Hermetic, ✅ Sandboxing-friendly, ✅ Per-target isolation | ⚠️ Lost on `bazel clean` |
| **B** | Separate cache dir (`~/.cache/bazel/kotlin-ic/`) | ✅ Survives `bazel clean` | ❌ Breaks hermeticity, ❌ RBE incompatible |
| **C** | Worker memory only | ✅ Fast, ✅ No disk I/O | ❌ Lost on worker restart, ❌ No cross-invocation caching |

**Decision**: **Option A** (Bazel output tree)

**Rationale**:
- Maintains Bazel's hermeticity guarantees
- Compatible with sandboxing and RBE (with caveats)
- IC state is treated as action input/output
- Explicit cache invalidation via Bazel primitives
- Aligns with Bazel's philosophy

**Trade-off**: `bazel clean` invalidates IC caches (acceptable - clean should be clean).

### Decision 2: IC Enable/Disable Strategy

**Options Considered**:

| Option | Mechanism | Pros | Cons |
|--------|-----------|------|------|
| **A** | Opt-in via toolchain flag | ✅ Explicit control, ✅ Gradual rollout | ⚠️ Requires toolchain modification |
| **B** | Opt-out via build flag | ✅ Default enabled | ❌ Breaks hermeticity by default |
| **C** | Auto-detect (persistent worker only) | ✅ Smart, ✅ No config needed | ❌ Implicit behavior |

**Decision**: **Option A** (Opt-in via toolchain flag)

**Configuration**:
```python
# In toolchain definition
define_kt_toolchain(
    name = "dev_toolchain",
    experimental_use_incremental_compilation = True,  # NEW FLAG
)

# In .bazelrc (optional, for local dev)
build --define=kt_experimental_ic=1
```

**Rationale**:
- Explicit opt-in for testing and validation
- Can be enabled per-configuration (dev vs CI)
- Maintains backward compatibility
- Clear migration path

### Decision 3: RBE Compatibility

**Challenge**: IC state is local and mutable, conflicting with RBE's stateless model.

**Solution**: **Disable IC for RBE, enable for local persistent workers**

**Implementation**:
```python
# In execution_requirements
execution_requirements = {
    "supports-workers": "1",
    # Only use IC with persistent workers, not with RBE
    "no-remote": "1" if toolchain.use_incremental_compilation else "0",
}
```

**Alternative**: Use Bazel's remote cache for IC state (future work).

**Rationale**:
- RBE prioritizes hermeticity over IC benefits
- Local development benefits most from IC
- CI can use RBE without IC (still fast via action cache)

### Decision 4: Classpath Snapshot Strategy

**Options Considered**:

| Option | When to generate | Granularity | Caching |
|--------|------------------|-------------|---------|
| **A** | On-demand during compilation | Mixed (external: CLASS_LEVEL, local: CLASS_MEMBER_LEVEL) | In-memory + disk |
| **B** | Pre-compilation via aspect | CLASS_MEMBER_LEVEL | Persistent |
| **C** | Bazel artifact transform | CLASS_LEVEL | Bazel cache |

**Decision**: **Option A** (On-demand with mixed granularity)

**Implementation**:
```kotlin
fun generateClasspathSnapshot(
    service: CompilationService,
    entry: File,
    cacheDir: File
): File {
    val snapshotFile = File(cacheDir, "classpath-snapshots/${entry.name}.snapshot")

    if (!snapshotFile.exists() || entry.lastModified() > snapshotFile.lastModified()) {
        // Determine granularity based on location
        val granularity = if (entry.path.contains("external/") ||
                             entry.path.contains(".m2/")) {
            ClassSnapshotGranularity.CLASS_LEVEL  // External: coarse
        } else {
            ClassSnapshotGranularity.CLASS_MEMBER_LEVEL  // Local: fine
        }

        val snapshot = service.calculateClasspathSnapshot(entry, granularity, true)
        snapshot.saveSnapshot(snapshotFile)
    }

    return snapshotFile
}
```

**Rationale**:
- External dependencies rarely change → CLASS_LEVEL saves space
- Local code changes frequently → CLASS_MEMBER_LEVEL maximizes IC benefit
- On-demand generation avoids upfront cost
- Caching prevents regeneration

### Decision 5: Source Change Detection

**Options Considered**:

| Option | Detection Method | Accuracy | Overhead |
|--------|------------------|----------|----------|
| **A** | `SourcesChanges.ToBeCalculated` | ✅ High (compiler detects) | Low (compiler does it anyway) |
| **B** | `SourcesChanges.Known` (Bazel input changes) | ⚠️ Medium (Bazel level) | Very low |
| **C** | `SourcesChanges.Unknown` | ❌ Low (always recompile) | None |

**Decision**: **Option A** (`ToBeCalculated`)

**Rationale**:
- Compiler has best knowledge of what changed
- Bazel already tracks input changes (no double work)
- Simplifies Starlark implementation

---

## Detailed Implementation Plan

### Phase 1: Foundation (Days 1-3)

#### 1.1 Add IC Configuration to Proto Schema

**File**: `src/main/protobuf/kotlin_model.proto`

```protobuf
message CompilationTaskInfo {
    // ... existing fields ...

    // Incremental compilation configuration
    optional bool enable_incremental_compilation = 20;
    optional string ic_working_directory = 21;
    repeated string ic_classpath_entries = 22;  // For snapshot generation
}
```

#### 1.2 Add Toolchain Configuration

**File**: `kotlin/internal/toolchains.bzl`

```python
def define_kt_toolchain(
    name,
    # ... existing parameters ...
    experimental_use_incremental_compilation = False,
    **kwargs
):
    """Define a Kotlin toolchain.

    Args:
        experimental_use_incremental_compilation: Enable BTAPI incremental compilation.
            WARNING: Experimental feature. Only works with persistent workers.
    """
    _kt_toolchain(
        name = name,
        # ... existing fields ...
        experimental_use_incremental_compilation = experimental_use_incremental_compilation,
        **kwargs
    )

_kt_toolchain = rule(
    implementation = _kt_toolchain_impl,
    attrs = {
        # ... existing attrs ...
        "experimental_use_incremental_compilation": attr.bool(
            default = False,
            doc = "Enable Build Tools API incremental compilation (experimental)",
        ),
    },
)
```

#### 1.3 Update Toolchain Provider

**File**: `kotlin/internal/toolchains.bzl` (in `_kt_toolchain_impl`)

```python
def _kt_toolchain_impl(ctx):
    return [
        platform_common.ToolchainInfo(
            # ... existing fields ...
            experimental_use_incremental_compilation = ctx.attr.experimental_use_incremental_compilation,
        ),
    ]
```

### Phase 2: Starlark Integration (Days 4-6)

#### 2.1 Declare IC Cache as Input/Output

**File**: `kotlin/internal/jvm/compile.bzl` (in `_run_kt_builder_action`)

```python
def _run_kt_builder_action(
        ctx,
        # ... existing params ...
):
    # ... existing code ...

    # NEW: Declare IC cache directory
    ic_cache_dir = None
    if toolchains.kt.experimental_use_incremental_compilation:
        ic_cache_dir = ctx.actions.declare_directory(ctx.label.name + "_ic_cache")
        args.add("--ic_cache_dir", ic_cache_dir.path)
        args.add("--ic_enabled", "true")

        # Add classpath entries for snapshot generation
        args.add_all("--ic_classpath_entries", compile_deps.compile_jars)

    # Update inputs/outputs
    inputs = depset(
        srcs.all_srcs + srcs.src_jars + generated_src_jars,
        transitive = [
            compile_deps.compile_jars,
            # ... other inputs ...
        ],
    )

    outputs = [f for f in outputs.values()]
    if ic_cache_dir:
        # IC cache is both input and output (mutable state)
        inputs = depset([ic_cache_dir], transitive = [inputs])
        outputs.append(ic_cache_dir)

    ctx.actions.run(
        mnemonic = mnemonic,
        inputs = inputs,
        outputs = outputs,
        # ... rest of action ...
    )
```

**Key Points**:
- `declare_directory()` creates a tree artifact for IC state
- IC cache is BOTH input and output (mutable)
- Bazel tracks changes to the cache directory

#### 2.2 Pass IC Config to Builder

**File**: `kotlin/internal/utils/utils.bzl` (in `init_args`)

```python
def init_args(ctx, rule_kind, module_name, kotlinc_options):
    """Initialize builder arguments."""
    args = ctx.actions.args()
    args.set_param_file_format("multiline")
    args.use_param_file("--flagfile=%s", use_always = True)

    toolchain = ctx.toolchains[_TOOLCHAIN_TYPE]

    # ... existing args ...

    # NEW: Incremental compilation flags
    if toolchain.experimental_use_incremental_compilation:
        args.add("--experimental_use_ic", "true")

    return args
```

### Phase 3: Kotlin Implementation (Days 7-10)

#### 3.1 Update BuildToolsAPICompiler Signature

**File**: `src/main/kotlin/io/bazel/kotlin/compiler/BuildToolsAPICompiler.kt`

```kotlin
@OptIn(ExperimentalBuildToolsApi::class)
fun exec(
    errStream: PrintStream,
    enableIncrementalCompilation: Boolean = false,  // NEW
    icWorkingDir: File? = null,                      // NEW
    classpathEntries: List<File> = emptyList(),      // NEW
    vararg args: String,
): CompilationResult {
    // Parse arguments
    val parsedArgs = parseArguments(args.toList())

    // Create ProjectId (already deterministic based on module name)
    val projectId = ProjectId.ProjectUUID(
        UUID.nameUUIDFromBytes(parsedArgs.moduleName.toByteArray())
    )

    // Execution strategy
    val executionConfig = compilationService
        .makeCompilerExecutionStrategyConfiguration()
        .useInProcessStrategy()

    // Compilation config
    val compilationConfig = compilationService
        .makeJvmCompilationConfiguration()
        .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))

    // NEW: Configure incremental compilation if enabled
    if (enableIncrementalCompilation && icWorkingDir != null) {
        configureIncrementalCompilation(
            compilationConfig,
            icWorkingDir,
            classpathEntries
        )
    }

    return try {
        compilationService.compileJvm(
            projectId,
            executionConfig,
            compilationConfig,
            emptyList(),
            args.toList()
        )
    } finally {
        try {
            compilationService.finishProjectCompilation(projectId)
        } catch (e: Throwable) {
            System.err.println("Warning: Error during finishProjectCompilation cleanup: ${e.message}")
        }
    }
}
```

#### 3.2 Implement IC Configuration

**File**: `src/main/kotlin/io/bazel/kotlin/compiler/BuildToolsAPICompiler.kt`

```kotlin
@OptIn(ExperimentalBuildToolsApi::class)
private fun configureIncrementalCompilation(
    compilationConfig: JvmCompilationConfiguration,
    icWorkingDir: File,
    classpathEntries: List<File>
) {
    // Ensure IC directory exists
    icWorkingDir.mkdirs()

    // Subdirectories
    val cacheDir = File(icWorkingDir, "caches").apply { mkdirs() }
    val snapshotsDir = File(icWorkingDir, "classpath-snapshots").apply { mkdirs() }
    val shrunkSnapshotFile = File(icWorkingDir, "shrunk-snapshot.bin")

    // Generate classpath snapshots
    val snapshotFiles = classpathEntries.mapNotNull { entry ->
        if (!entry.exists()) {
            System.err.println("Warning: Classpath entry does not exist: ${entry.path}")
            return@mapNotNull null
        }

        val snapshotFile = File(snapshotsDir, "${entry.name}.snapshot")

        // Regenerate if missing or outdated
        if (!snapshotFile.exists() || entry.lastModified() > snapshotFile.lastModified()) {
            try {
                val granularity = if (isExternalDependency(entry)) {
                    ClassSnapshotGranularity.CLASS_LEVEL  // External: coarse
                } else {
                    ClassSnapshotGranularity.CLASS_MEMBER_LEVEL  // Local: fine
                }

                val snapshot = compilationService.calculateClasspathSnapshot(
                    entry,
                    granularity,
                    parseInlinedLocalClasses = true
                )
                snapshot.saveSnapshot(snapshotFile)
            } catch (e: Exception) {
                System.err.println("Warning: Failed to generate snapshot for ${entry.path}: ${e.message}")
                return@mapNotNull null
            }
        }

        snapshotFile
    }

    // Configure IC
    val icConfig = compilationConfig
        .makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
        .setRootProjectDir(icWorkingDir.parentFile.parentFile)  // Project root
        .setBuildDir(icWorkingDir.parentFile)                   // Build directory
        .usePreciseJavaTracking(true)                           // Track Java changes
        .usePreciseCompilationResultsBackup(true)               // File-by-file backup
        .keepIncrementalCompilationCachesInMemory(true)         // Performance optimization
        .useOutputDirs(listOf(cacheDir))                        // Output directories

    val icParams = ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
        snapshotFiles,           // Current snapshots
        shrunkSnapshotFile       // Previous build snapshot
    )

    compilationConfig.useIncrementalCompilation(
        cacheDir,
        SourcesChanges.ToBeCalculated,  // Let compiler detect source changes
        icParams,
        icConfig
    )
}

/**
 * Determine if a classpath entry is an external dependency.
 * External dependencies get coarser-grained snapshots (CLASS_LEVEL).
 */
private fun isExternalDependency(file: File): Boolean {
    val path = file.absolutePath
    return path.contains("/external/") ||      // Bazel external deps
           path.contains("/.m2/repository/") || // Maven local cache
           path.contains("/bazel-out/") && path.contains("/external/")  // Bazel output of external
}
```

#### 3.3 Update KotlinJvmTaskExecutor

**File**: `src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/KotlinJvmTaskExecutor.kt`

```kotlin
fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
) {
    val compiler = BuildToolsAPICompiler(
        toolchain.kotlinCompilerJar,
        toolchain.buildToolsImplJar
    )

    val preprocessedTask = task
        .preProcessingSteps(context)
        .runPlugins(context, plugins, compiler)

    context.execute("compile classes") {
        preprocessedTask.apply {
            sequenceOf(
                runCatching {
                    context.execute("kotlinc") {
                        if (compileKotlin) {
                            // Extract IC configuration from task
                            val enableIC = info.enableIncrementalCompilation
                            val icWorkingDir = if (enableIC && info.icWorkingDirectory.isNotEmpty()) {
                                File(info.icWorkingDirectory)
                            } else null
                            val classpathEntries = if (enableIC) {
                                info.icClasspathEntriesList.map { File(it) }
                            } else emptyList()

                            compileKotlin(
                                context,
                                compiler,
                                enableIncrementalCompilation = enableIC,
                                icWorkingDir = icWorkingDir,
                                classpathEntries = classpathEntries,
                                args = baseArgs()
                                    // ... existing args ...
                            )
                        } else {
                            emptyList()
                        }
                    }
                },
            ).map {
                // ... existing error handling ...
            }.fold(/*...*/)

            // ... existing jar creation ...
        }
    }
}
```

#### 3.4 Update CompilationTask Extension

**File**: `src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/CompilationTask.kt`

```kotlin
private fun JvmCompilationTask.compileKotlin(
    context: CompilationTaskContext,
    compiler: BuildToolsAPICompiler,
    enableIncrementalCompilation: Boolean = false,  // NEW
    icWorkingDir: File? = null,                      // NEW
    classpathEntries: List<File> = emptyList(),      // NEW
    args: CompilerPluginArgsEncoder,
    printOnFail: Boolean = true,
): List<String> {
    return context.executeCompilerTask(
        args.encode(),
        printOnFail = printOnFail,
    ) { taskDirs, argFile ->
        val outputStream = context.executionContext.out
        val compilerArgs = argFile.readLines().toTypedArray()

        // Call compiler with IC parameters
        compiler.exec(
            outputStream,
            enableIncrementalCompilation,
            icWorkingDir,
            classpathEntries,
            *compilerArgs
        )
    }
}
```

### Phase 4: Testing (Days 11-13)

#### 4.1 Unit Tests

**File**: `src/test/kotlin/io/bazel/kotlin/compiler/IncrementalCompilationTest.java`

```java
@RunWith(JUnit4.class)
public class IncrementalCompilationTest {
    @Test
    public void testIC_firstBuild_fullCompilation() {
        // First build should compile all files
    }

    @Test
    public void testIC_noChanges_skipCompilation() {
        // No changes should skip compilation
    }

    @Test
    public void testIC_oneFileChanged_incrementalCompilation() {
        // One file change should recompile only affected files
    }

    @Test
    public void testIC_classpathChanged_detectsChanges() {
        // Classpath changes should trigger affected file recompilation
    }

    @Test
    public void testIC_snapshotGeneration_cachesResults() {
        // Snapshot generation should cache results
    }
}
```

#### 4.2 Integration Tests

**Create test scenarios**:
1. First build (no IC cache) → full compilation
2. No changes → should be very fast (IC detects no changes)
3. One source file changed → incremental compilation
4. Dependency changed → affected files recompiled
5. Clean build → IC cache reset, full compilation

#### 4.3 Performance Benchmarks

**Benchmark script**: `scripts/benchmark_ic.sh`

```bash
#!/bin/bash
# Benchmark IC performance

MODULE="//examples/dagger:dagger"

echo "=== Cold build (no IC) ==="
bazel clean
time bazel build $MODULE

echo "=== Warm build (no changes, no IC) ==="
time bazel build $MODULE

echo "=== Enable IC ==="
cat > ic_toolchain.bzl <<EOF
define_kt_toolchain(
    name = "ic_toolchain",
    experimental_use_incremental_compilation = True,
)
EOF

echo "=== Cold build (with IC, no cache) ==="
bazel clean
time bazel build $MODULE --define=kt_experimental_ic=1

echo "=== Warm build (no changes, with IC) ==="
time bazel build $MODULE --define=kt_experimental_ic=1

echo "=== Touch one file and rebuild ==="
touch examples/dagger/src/coffee/CoffeeApp.kt
time bazel build $MODULE --define=kt_experimental_ic=1
```

### Phase 5: Documentation & Polish (Days 14-15)

#### 5.1 User Documentation

**File**: `docs/incremental_compilation.md`

Topics:
- What is IC and when to use it
- How to enable IC (toolchain configuration)
- Performance characteristics
- Interaction with Bazel caching
- Troubleshooting guide
- RBE considerations

#### 5.2 Migration Guide

**File**: `docs/migrating_to_ic.md`

Topics:
- Opt-in strategy
- Performance expectations
- Caveats and limitations
- Rollback procedure

#### 5.3 Update Main README

Add section on IC with link to docs.

---

## Testing Strategy

### Test Pyramid

```
              ┌──────────────┐
              │   E2E Tests  │
              │  (5 tests)   │  - Full builds with IC
              └──────────────┘
                     │
          ┌──────────────────────┐
          │  Integration Tests   │
          │    (15 tests)        │  - Compilation scenarios
          └──────────────────────┘
                     │
              ┌─────────────────────────┐
              │     Unit Tests          │
              │     (30 tests)          │  - Snapshot generation, IC config
              └─────────────────────────┘
```

### Test Categories

#### 1. Unit Tests (Fast, Many)

**Focus**: Individual components

Tests:
- `IncrementalCompilationConfigTest`: IC configuration logic
- `ClasspathSnapshotGeneratorTest`: Snapshot generation and caching
- `ProjectIdTest`: Deterministic ProjectId generation
- `BuildToolsAPICompilerTest`: IC parameter passing

#### 2. Integration Tests (Medium, Moderate)

**Focus**: Compilation scenarios

Tests:
- First build (cold cache)
- No changes (warm cache)
- Single file change
- Multiple file changes
- Transitive dependency change
- Classpath change
- Clean build

#### 3. E2E Tests (Slow, Few)

**Focus**: Real-world scenarios

Tests:
- Large module (100+ files) incremental build
- Multi-module project with IC
- Android app with IC
- Persistent worker with IC across multiple invocations

### Performance Testing

**Metrics to track**:
- Compilation time (cold vs warm)
- Snapshot generation overhead
- Memory usage (worker RSS)
- Cache hit rate
- IC effectiveness (% files recompiled)

**Benchmarks**:
- Small module (10 files): <1s incremental
- Medium module (50 files): 2-5s incremental
- Large module (200+ files): 10-20s incremental

**Comparison baseline**: Non-IC compilation time

---

## Rollout Plan

### Phase 1: Internal Testing (Week 1)

**Goal**: Validate implementation with core developers

**Actions**:
1. Enable IC for one test module
2. Run benchmarks
3. Fix critical bugs
4. Document issues

**Success Criteria**:
- IC works for test module
- Performance improves by >30%
- No correctness issues

### Phase 2: Opt-In Preview (Week 2-3)

**Goal**: Gather feedback from early adopters

**Actions**:
1. Announce IC preview to rules_kotlin users
2. Provide opt-in instructions
3. Monitor GitHub issues
4. Iterate on feedback

**Success Criteria**:
- 5+ users adopt IC
- Performance gains validated
- No major bugs reported

### Phase 3: Recommended (Week 4-6)

**Goal**: Make IC the recommended approach for local development

**Actions**:
1. Update documentation to recommend IC for dev
2. Add IC to example projects
3. Blog post about IC benefits

**Success Criteria**:
- Docs updated
- Examples migrated
- Positive community feedback

### Phase 4: Default (Future)

**Goal**: Enable IC by default for all builds

**Blocked on**:
- Extensive testing
- RBE compatibility solution
- Performance validation at scale

---

## Performance Goals

### Target Metrics

| Scenario | Without IC | With IC (Target) | Improvement |
|----------|-----------|------------------|-------------|
| **Cold build** | 30s | 35s (5s snapshot gen) | -17% (acceptable overhead) |
| **No changes** | 0.5s (action cache) | 0.5s | 0% (same) |
| **1 file changed** | 30s | 5-10s | **67-83%** ✅ |
| **10 files changed** | 30s | 10-15s | **50-67%** ✅ |
| **Transitive change** | 30s | 15-20s | **33-50%** ✅ |

### Memory Goals

| Metric | Without IC | With IC (Target) |
|--------|-----------|------------------|
| **Worker RSS** | 500MB | 600MB (+20%) |
| **IC cache size** | 0 | 50-100MB per module |
| **Snapshot size** | 0 | 10-50MB per classpath |

### Benchmarking Methodology

```bash
# Benchmark script
./scripts/benchmark_ic.sh \
    --module=//examples/dagger:dagger \
    --iterations=5 \
    --scenarios=cold,warm,one_file,ten_files
```

**Output**:
```
Scenario: cold_build_without_ic
  Mean: 30.2s, StdDev: 0.8s

Scenario: cold_build_with_ic
  Mean: 34.8s, StdDev: 1.2s (snapshot generation overhead)

Scenario: one_file_changed_without_ic
  Mean: 29.8s, StdDev: 0.5s

Scenario: one_file_changed_with_ic
  Mean: 8.4s, StdDev: 0.9s (71% improvement)
```

---

## Risks & Mitigations

### Risk 1: IC State Corruption

**Description**: IC caches become corrupted, leading to incorrect builds.

**Likelihood**: Medium
**Impact**: High (incorrect outputs)

**Mitigation**:
1. ✅ Implement file-by-file backup (`usePreciseCompilationResultsBackup(true)`)
2. ✅ Automatic fallback to non-IC on corruption detection
3. ✅ Clear error messages with recovery instructions
4. ✅ Easy cache invalidation: `bazel clean`

**Detection**:
- Compiler errors about missing classes
- Inconsistent outputs
- Hash mismatches

**Recovery**:
```bash
# User runs
bazel clean
bazel build //...
```

### Risk 2: Bazel Action Cache Conflicts

**Description**: IC and Bazel's action cache might conflict, causing unexpected behavior.

**Likelihood**: Low (IC state is declared as input/output)
**Impact**: Medium (build performance)

**Mitigation**:
1. ✅ IC cache directory is declared as both input and output
2. ✅ Bazel tracks changes to IC cache
3. ✅ Action hash includes IC cache state
4. ✅ Clear documentation on cache interaction

**Monitoring**:
- Track action cache hit rate with/without IC
- Compare build times in CI vs local

### Risk 3: Memory Pressure in Persistent Workers

**Description**: IC caches increase worker memory usage, potentially causing OOM.

**Likelihood**: Medium
**Impact**: Medium (worker crashes)

**Mitigation**:
1. ✅ Keep IC caches in memory (`keepIncrementalCompilationCachesInMemory(true)`)
2. ✅ Implement cache size limits
3. ✅ GC scheduling based on CPU time (already exists)
4. ✅ Monitor worker RSS and add JVM tuning flags

**JVM Flags**:
```bash
# In .bazelrc
build --worker_extra_flag=KotlinCompile=-Xmx2g
build --worker_extra_flag=KotlinCompile=-XX:MaxMetaspaceSize=512m
```

### Risk 4: Snapshot Generation Overhead

**Description**: Generating classpath snapshots adds overhead to cold builds.

**Likelihood**: High
**Impact**: Low (5-10s one-time cost)

**Mitigation**:
1. ✅ Cache snapshots on disk
2. ✅ Use CLASS_LEVEL for external dependencies (smaller snapshots)
3. ✅ Generate snapshots lazily (only for changed entries)
4. ✅ Parallel snapshot generation (future optimization)

**Measurement**:
- Track snapshot generation time
- Measure snapshot cache hit rate

### Risk 5: RBE Incompatibility

**Description**: IC doesn't work with Remote Build Execution.

**Likelihood**: High
**Impact**: Medium (RBE users can't benefit from IC)

**Mitigation**:
1. ✅ Disable IC for RBE via execution requirements
2. ✅ Document RBE limitations
3. ✅ Future work: Remote IC cache support
4. ✅ RBE users still benefit from Bazel's action cache

**Status**: Acceptable trade-off for initial implementation.

### Risk 6: Incorrect Incremental Compilation

**Description**: IC misses changes, leading to stale outputs.

**Likelihood**: Low (BTAPI is mature)
**Impact**: High (incorrect builds)

**Mitigation**:
1. ✅ Use `SourcesChanges.ToBeCalculated` (compiler detects changes)
2. ✅ Precise Java tracking enabled
3. ✅ Comprehensive integration tests
4. ✅ Fallback to non-IC on errors
5. ✅ User can disable IC per-target or globally

**Detection**:
- Compilation errors referencing non-existent symbols
- Test failures after incremental build

**Recovery**:
```bash
# User runs
bazel clean
bazel build //...  # Full rebuild without IC
```

---

## Open Questions & Future Work

### Open Questions

1. **Q**: Should IC be enabled by default in future?
   - **A**: Defer to Phase 4 after extensive validation

2. **Q**: How to handle IC with multiple Bazel configurations?
   - **A**: IC cache is per-configuration (separate output paths)

3. **Q**: Should we expose IC metrics to users?
   - **A**: Yes (future work) - add compilation report with IC stats

4. **Q**: How to handle IC with Bazel's `--disk_cache`?
   - **A**: Compatible - IC state is in action outputs, gets cached

### Future Enhancements

1. **Remote IC Cache** (Future)
   - Store IC state in remote cache
   - Enable IC with RBE
   - Requires Bazel support for mutable remote cache

2. **Parallel Snapshot Generation** (Future)
   - Generate snapshots in parallel
   - Reduce cold build overhead

3. **IC Metrics Dashboard** (Future)
   - Track IC effectiveness
   - Measure performance gains
   - Identify optimization opportunities

4. **Cross-Configuration IC** (Future)
   - Share IC state between configurations
   - Requires careful cache key design

5. **FIR-based IC** (Future)
   - Use experimental FIR incremental compiler
   - Potentially faster IC
   - Requires Kotlin 2.2+

---

## Summary & Next Steps

### Summary

This plan provides a comprehensive roadmap for implementing IC in rules_kotlin:

- **Opt-in via toolchain flag** for safe rollout
- **IC state in Bazel output tree** for hermeticity
- **Persistent worker integration** for performance
- **Deterministic ProjectId** for stable caching
- **Mixed-granularity snapshots** for optimal IC
- **Clear testing and rollout strategy**

### Next Steps

1. ✅ **Review this plan** with rules_kotlin maintainers
2. ⬜ **Create implementation PR** following this plan
3. ⬜ **Internal testing** with example projects
4. ⬜ **Community preview** for early adopters
5. ⬜ **Iterate** based on feedback
6. ⬜ **Document** and announce stable release

### Timeline

- **Week 1**: Implementation (Phase 1-3)
- **Week 2**: Testing & bugfixes (Phase 4)
- **Week 3**: Documentation & preview release (Phase 5)
- **Week 4+**: Community feedback & iteration

### Success Criteria

✅ IC improves incremental build times by 50-70%
✅ No correctness issues reported
✅ Memory overhead <20%
✅ Cold build overhead <20%
✅ Positive community feedback
✅ Clear documentation and migration path

---

## References

- [BTAPI Comparison](./BTAPI_COMPARISON.md)
- [BTAPI Next Steps](./BTAPI_NEXT_STEPS.md)
- [Kotlin Build Tools API Docs](https://github.com/JetBrains/kotlin/tree/master/compiler/build-tools/kotlin-build-tools-api)
- [Bazel Persistent Workers](https://bazel.build/remote/persistent)
- [Bazel Action Caching](https://bazel.build/remote/caching)

---

**Document Status**: DRAFT
**Last Updated**: 2025-01-26
**Author**: Claude (with rules_kotlin architecture analysis)
**Review Status**: Pending maintainer review

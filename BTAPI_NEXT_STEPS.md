# Build Tools API: Next Features to Port to rules_kotlin

## Executive Summary

The rules_kotlin Build Tools API implementation is currently minimal but well-architected. Based on the comparison with Maven and Gradle implementations, here are the recommended next features to port, prioritized by value and feasibility.

## Current State

**Implemented** (185 lines):
- ✅ Basic BTAPI integration with `BuildToolsAPICompiler`
- ✅ Classloader isolation via `SharedApiClassesClassLoader`
- ✅ IN_PROCESS execution strategy
- ✅ Deterministic ProjectId (unique innovation!)
- ✅ Custom logger integration
- ✅ Resource cleanup via `finishProjectCompilation()`

**Not Yet Implemented**:
- ❌ Incremental compilation
- ❌ Classpath snapshots
- ❌ Custom Kotlin script extensions support
- ❌ Compiler version reporting
- ❌ Build metrics/telemetry

---

## Priority 1: Incremental Compilation (High Impact)

**Status**: ✅ **COMPREHENSIVE PLAN COMPLETE**

**See**: [IC_IMPLEMENTATION_PLAN.md](./IC_IMPLEMENTATION_PLAN.md) for the complete technical implementation plan.

**Summary**: [IC_IMPLEMENTATION_SUMMARY.md](./IC_IMPLEMENTATION_SUMMARY.md) for a quick overview.

### Why This Matters

1. **Persistent Workers Already Exist**: rules_kotlin has `PersistentWorker` implementation that maintains state across invocations
2. **Deterministic ProjectId Ready**: The UUID based on module name enables consistent caching
3. **Biggest Performance Win**: Both Maven and Gradle show significant speedups with IC
4. **BTAPI Handles Complexity**: The Build Tools API abstracts away most IC complexity

### Implementation Plan Summary

The comprehensive plan covers:
- **5 implementation phases** (15 days total)
- **5 key design decisions** (storage, opt-in, RBE, snapshots, change detection)
- **Complete code changes** (Starlark, Kotlin, Proto)
- **Testing strategy** (unit, integration, E2E, performance)
- **Rollout strategy** (internal → preview → recommended → default)
- **Risk mitigation** (6 major risks addressed)
- **Performance targets** (50-70% faster incremental builds)

### What Needs to Be Done (High-Level)

#### 1. Add Incremental Compilation Configuration

**File**: `BuildToolsAPICompiler.kt`

Add IC configuration to `exec()` method:

```kotlin
@OptIn(ExperimentalBuildToolsApi::class)
fun exec(
    errStream: PrintStream,
    workingDir: File,  // NEW: Working directory for IC state
    classpathEntries: List<File>,  // NEW: For snapshot generation
    enableIncrementalCompilation: Boolean,  // NEW: Feature flag
    vararg args: String,
): CompilationResult {
    // ... existing classloader setup ...

    val compilationConfig = kotlinService
        .makeJvmCompilationConfiguration()
        .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))

    // NEW: Configure incremental compilation if enabled
    if (enableIncrementalCompilation) {
        val icWorkingDir = File(workingDir, "kotlin-ic/${parsedArgs.moduleName}")
        icWorkingDir.mkdirs()

        // Generate classpath snapshots
        val classpathSnapshots = generateClasspathSnapshots(
            kotlinService,
            classpathEntries,
            icWorkingDir
        )

        val icConfig = compilationConfig
            .makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
            .setRootProjectDir(workingDir.parentFile)  // Project root
            .setBuildDir(workingDir)  // Build directory
            .usePreciseJavaTracking(true)  // Track Java changes precisely
            .keepIncrementalCompilationCachesInMemory(true)  // Performance optimization

        val icParams = ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
            classpathSnapshots,  // Current snapshots
            File(icWorkingDir, "shrunk-classpath-snapshot.bin")  // Shrunk snapshot
        )

        compilationConfig.useIncrementalCompilation(
            icWorkingDir,
            SourcesChanges.ToBeCalculated,  // Let compiler detect changes
            icParams,
            icConfig
        )
    }

    return kotlinService.compileJvm(...)
}

private fun generateClasspathSnapshots(
    service: CompilationService,
    classpathEntries: List<File>,
    cacheDir: File
): List<File> {
    val snapshotsDir = File(cacheDir, "classpath-snapshots")
    snapshotsDir.mkdirs()

    return classpathEntries.map { entry ->
        val snapshotFile = File(snapshotsDir, "${entry.name}.snapshot")

        if (!snapshotFile.exists() || entry.lastModified() > snapshotFile.lastModified()) {
            // Regenerate snapshot
            val granularity = if (entry.path.contains("external/")) {
                ClassSnapshotGranularity.CLASS_LEVEL  // External deps: coarser
            } else {
                ClassSnapshotGranularity.CLASS_MEMBER_LEVEL  // Local: finer
            }

            val snapshot = service.calculateClasspathSnapshot(entry, granularity, true)
            snapshot.saveSnapshot(snapshotFile)
        }

        snapshotFile
    }
}
```

#### 2. Update Task Executor Integration

**File**: `KotlinJvmTaskExecutor.kt`

```kotlin
fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
) {
    // Determine working directory from context
    val workingDir = context.workerContext.directory.toFile()

    // Extract classpath entries from task
    val classpathEntries = task.inputs.classpathList.map { File(it) }

    // Check if IC should be enabled (could be from toolchain config)
    val enableIC = task.info.toolchainInfo.enableIncrementalCompilation

    val compiler = BuildToolsAPICompiler(
        toolchain.kotlinCompilerJar,
        toolchain.buildToolsImplJar
    )

    // Pass IC parameters to compiler
    val result = compiler.exec(
        context.out,
        workingDir,
        classpathEntries,
        enableIC,
        *args
    )

    // ... rest of execution ...
}
```

#### 3. Add Starlark Configuration

**File**: `kotlin/internal/toolchains.bzl`

```python
def _kt_jvm_toolchain_impl(ctx):
    return [
        platform_common.ToolchainInfo(
            # ... existing fields ...
            enable_incremental_compilation = ctx.attr.enable_incremental_compilation,
        ),
    ]

kt_jvm_toolchain = rule(
    implementation = _kt_jvm_toolchain_impl,
    attrs = {
        # ... existing attrs ...
        "enable_incremental_compilation": attr.bool(
            default = False,  # Start with opt-in
            doc = "Enable Build Tools API incremental compilation",
        ),
    },
)
```

#### 4. Update Protocol Buffer Schema

**File**: `src/main/protobuf/kotlin_model.proto`

Add IC configuration to `ToolchainInfo`:

```protobuf
message ToolchainInfo {
    // ... existing fields ...
    bool enable_incremental_compilation = 10;
}
```

### Testing Strategy

1. **Unit Tests**: Test snapshot generation and IC configuration
2. **Integration Tests**: Compare build times with/without IC
3. **Persistent Worker Tests**: Verify IC state persists across worker invocations
4. **Correctness Tests**: Ensure incremental builds produce identical outputs

### Estimated Effort

- **Implementation**: 2-3 days
- **Testing**: 1-2 days
- **Documentation**: 1 day
- **Total**: ~1 week

### Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| IC state conflicts with Bazel action caching | Make IC opt-in, document interaction |
| Snapshot generation overhead | Cache snapshots, use CLASS_LEVEL for external deps |
| Non-hermetic behavior | Store all IC state in designated working directory |
| Persistent worker memory usage | Add GC scheduling, configurable cache limits |

---

## Priority 2: Compiler Version Reporting (Low Effort, High Value)

### Why This Matters

1. **Debugging**: Helps users diagnose version-related issues
2. **Compatibility Checks**: Rules can verify minimum compiler version
3. **Trivial to Implement**: Single method call to BTAPI

### Implementation

**File**: `BuildToolsAPICompiler.kt`

```kotlin
class BuildToolsAPICompiler(
    private val kotlinCompilerJar: File,
    private val buildToolsImplJar: File,
) {
    // Lazy initialization to avoid overhead
    private val compilationService: CompilationService by lazy {
        val urls = arrayOf(
            buildToolsImplJar.toURI().toURL(),
            kotlinCompilerJar.toURI().toURL()
        )
        val btapiClassLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
        CompilationService.loadImplementation(btapiClassLoader)
    }

    /**
     * Get the Kotlin compiler version.
     * @return Version string like "2.1.0" or "2.2.0-Beta1"
     */
    fun getCompilerVersion(): String = compilationService.getCompilerVersion()

    fun compile(args: Array<String>, out: PrintStream): Int {
        // Use cached compilationService instead of creating new one
        val result = exec(out, *args)
        return when (result) {
            CompilationResult.COMPILATION_SUCCESS -> 0
            CompilationResult.COMPILATION_ERROR -> 1
            CompilationResult.COMPILATION_OOM_ERROR -> 3
            CompilationResult.COMPILER_INTERNAL_ERROR -> 4
        }
    }

    @OptIn(ExperimentalBuildToolsApi::class)
    fun exec(errStream: PrintStream, vararg args: String): CompilationResult {
        // Use compilationService from lazy property
        // ... rest of implementation ...
    }
}
```

**File**: `KotlinToolchain.kt`

```kotlin
class KotlinToolchain private constructor(
    // ... existing fields ...
) {
    /**
     * Get the Kotlin compiler version from Build Tools API.
     */
    fun getCompilerVersion(): String {
        val compiler = BuildToolsAPICompiler(kotlinCompilerJar, buildToolsImplJar)
        return compiler.getCompilerVersion()
    }
}
```

**Usage in Rules**:

```python
# kotlin/internal/jvm/compile.bzl
def _kt_jvm_compile_impl(ctx):
    toolchain = ctx.toolchains["@rules_kotlin//kotlin:toolchain_type"]
    compiler_version = toolchain.compiler_version  # Exposed from toolchain

    # Version check example
    if not version_at_least(compiler_version, "2.0.0"):
        fail("Kotlin 2.0+ required, got: " + compiler_version)
```

### Estimated Effort

- **Implementation**: 2-4 hours
- **Testing**: 1-2 hours
- **Documentation**: 1 hour
- **Total**: ~1 day

---

## Priority 3: Custom Kotlin Script Extensions (Medium Impact)

### Why This Matters

1. **Script Template Support**: Gradle build scripts, custom DSLs
2. **IDE Integration**: Proper syntax highlighting for custom scripts
3. **BTAPI Feature Parity**: Maven and Gradle both support this

### Implementation

**File**: `BuildToolsAPICompiler.kt`

```kotlin
/**
 * Discover custom Kotlin script filename extensions from classpath.
 * @param classpath Script definition classpath
 * @return Collection of extensions (e.g., [".gradle.kts", ".space.kts"])
 */
fun getCustomScriptExtensions(classpath: List<File>): Collection<String> {
    return compilationService.getCustomKotlinScriptFilenameExtensions(classpath)
}

@OptIn(ExperimentalBuildToolsApi::class)
fun exec(
    errStream: PrintStream,
    scriptDefinitionClasspath: List<File>?,  // NEW parameter
    vararg args: String,
): CompilationResult {
    // ... existing setup ...

    val compilationConfig = kotlinService
        .makeJvmCompilationConfiguration()
        .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))

    // NEW: Configure custom script extensions if provided
    if (scriptDefinitionClasspath != null && scriptDefinitionClasspath.isNotEmpty()) {
        val customExtensions = kotlinService.getCustomKotlinScriptFilenameExtensions(
            scriptDefinitionClasspath
        )
        if (customExtensions.isNotEmpty()) {
            compilationConfig.useKotlinScriptFilenameExtensions(customExtensions)
        }
    }

    return kotlinService.compileJvm(...)
}
```

**Starlark Configuration**:

```python
# kotlin/jvm.bzl
kt_jvm_library(
    name = "my_scripts",
    srcs = ["build.gradle.kts"],
    script_definition_deps = [
        "@gradle_script_kotlin//jar",  # Provides .gradle.kts extension
    ],
)
```

### Estimated Effort

- **Implementation**: 1 day
- **Testing**: 1 day
- **Documentation**: 0.5 day
- **Total**: 2-3 days

---

## Priority 4: Build Metrics & Telemetry (Low Priority)

### Why This Matters

1. **Performance Insights**: Understand compilation bottlenecks
2. **Debugging**: Diagnose slow builds
3. **Optimization**: Data-driven performance improvements

### Implementation Sketch

**File**: `BuildToolsAPICompiler.kt`

```kotlin
data class CompilationMetrics(
    val durationMs: Long,
    val sourceFiles: Int,
    val linesOfCode: Int,
    val result: CompilationResult,
    val compilerVersion: String,
)

class BazelKotlinLogger(
    private val errStream: PrintStream,
    private val moduleName: String,
    private val collectMetrics: Boolean = false,
) : KotlinLogger {
    private val metrics = mutableListOf<String>()

    override fun lifecycle(msg: String) {
        if (collectMetrics && msg.startsWith("PERF:")) {
            metrics.add(msg)
        }
        errStream.println(msg)
    }

    fun getMetrics(): List<String> = metrics
}
```

**Note**: This is lower priority because Bazel already has extensive build metrics and profiling tools.

### Estimated Effort

- **Implementation**: 2-3 days
- **Integration with Bazel metrics**: 2 days
- **Total**: 4-5 days

---

## Implementation Roadmap

### Phase 1: Foundation (Week 1)
- ✅ DONE: Basic BTAPI integration
- ✅ DONE: Add compiler version reporting (1 day)
- ✅ DONE: Refactor to cache CompilationService instance (0.5 day)

### Phase 2: Incremental Compilation (Weeks 2-3)
- ⬜ Implement classpath snapshot generation (2 days)
- ⬜ Add IC configuration to BuildToolsAPICompiler (1 day)
- ⬜ Integrate with persistent workers (1 day)
- ⬜ Update proto schema and Starlark rules (1 day)
- ⬜ Testing and validation (3 days)
- ⬜ Documentation (1 day)

### Phase 3: Polish (Week 4)
- ⬜ Add custom script extensions support (2 days)
- ⬜ Performance benchmarking (2 days)
- ⬜ Documentation updates (1 day)

### Phase 4: Optional Enhancements (Future)
- ⬜ Build metrics/telemetry
- ⬜ Advanced IC options (FIR runner, precise backup)
- ⬜ IC performance tuning

---

## Key Design Decisions

### 1. Keep Hermetic Builds by Default

**Decision**: Incremental compilation should be **opt-in** and not interfere with Bazel's action caching.

**Rationale**:
- Bazel's action cache provides strong hermeticity guarantees
- BTAPI IC adds additional performance layer for persistent workers
- Opt-in allows gradual rollout and A/B testing

### 2. Store IC State in Designated Directory

**Decision**: All IC state goes in `{working_dir}/kotlin-ic/{module_name}/`

**Rationale**:
- Clear separation from Bazel's action outputs
- Easy to clean/invalidate
- Works with both local and remote execution

### 3. Deterministic ProjectId Enables Future Features

**Decision**: Keep UUID based on module name (not random)

**Rationale**:
- Enables persistent worker to cache per-module
- IC state can be keyed by stable ProjectId
- Potential for cross-invocation caching (with care)

### 4. Classpath Snapshots: Lazy Generation

**Decision**: Generate snapshots on-demand, cache them

**Rationale**:
- External dependencies rarely change (CLASS_LEVEL granularity)
- Local code changes frequently (CLASS_MEMBER_LEVEL granularity)
- Snapshot generation is expensive, caching is essential

---

## Success Metrics

### Incremental Compilation

| Metric | Target |
|--------|--------|
| **Cold build time** | No regression (within 5%) |
| **Incremental build time (1 file change)** | 50-70% faster |
| **Incremental build time (10 file change)** | 30-50% faster |
| **Correctness** | 100% identical outputs |
| **Memory overhead (persistent worker)** | <20% increase |

### Compiler Version Reporting

| Metric | Target |
|--------|--------|
| **Overhead** | <1ms per invocation |
| **Accuracy** | Exact version string |

---

## Open Questions

1. **IC and Remote Execution**: How should IC interact with RBE?
   - *Proposal*: Disable IC for RBE builds, enable for local persistent workers

2. **IC State Sharing**: Can IC state be shared across Bazel invocations?
   - *Proposal*: Start with single-invocation IC, explore cross-invocation later

3. **Snapshot Storage**: Should snapshots be stored in Bazel's output tree or separate cache?
   - *Proposal*: Store in working directory for isolation

4. **Performance Monitoring**: How to measure IC effectiveness?
   - *Proposal*: Add optional timing metrics to logger

5. **Fallback Strategy**: What happens if IC fails?
   - *Proposal*: Automatic fallback to non-IC compilation, log warning

---

## References

- [BTAPI Comparison Document](./BTAPI_COMPARISON.md)
- [Maven Plugin IC Implementation](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/K2JVMCompileMojo.java)
- [Gradle Plugin IC Implementation](https://github.com/JetBrains/kotlin/tree/master/libraries/tools/kotlin-gradle-plugin)
- [Bazel Persistent Workers](https://bazel.build/remote/persistent)
- [rules_kotlin Roadmap](./ROADMAP.md)

---

## Conclusion

The most impactful next feature to port is **Incremental Compilation**, leveraging:
1. Existing persistent worker infrastructure
2. Deterministic ProjectId (unique to rules_kotlin)
3. BTAPI's classpath snapshot mechanism

Starting with opt-in IC, we can validate performance benefits without disrupting existing hermetic builds. The implementation is estimated at ~1 week and could provide 50-70% faster incremental builds for local development.

The other priorities (compiler version reporting, script extensions) are smaller wins that can be implemented in parallel or as quick wins.

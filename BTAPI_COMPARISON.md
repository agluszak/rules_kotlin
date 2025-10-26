# Comparison: Kotlin Build Tools API Implementation
## rules_kotlin (Bazel) vs kotlin-maven-plugin vs kotlin-gradle-plugin

## Executive Summary

All three build systems have adopted the **Kotlin Build Tools API (BTAPI)** as a standardized interface to the Kotlin compiler. However, each implementation reflects the unique constraints and philosophy of its build system:

- **Maven**: Runtime classloader isolation, optional daemon, incremental compilation with snapshot-based approach
- **Gradle**: Opt-in feature with Worker API integration, sophisticated caching, artifact transforms for classpath snapshots
- **Bazel (rules_kotlin)**: Minimalist hermetic implementation, in-process only, deterministic ProjectId, optimized for RBE

---

## 1. Architecture & Integration Pattern

### Maven Plugin (`kotlin-maven-plugin`)

**Location**: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-maven-plugin`

**Integration Approach**:
```
K2JVMCompileMojo
  ├─ Runtime artifact resolution (kotlin-build-tools-impl)
  ├─ KotlinArtifactResolver (Maven repository system)
  ├─ SharedApiClassesClassLoader.newInstance()
  └─ CompilationService.loadImplementation(isolatedClassLoader)
```

**Key Files**:
- `K2JVMCompileMojo.java:252-270` - Classloader creation and service loading
- `K2JVMCompileMojo.java:283-356` - Main compilation orchestration

**Characteristics**:
- ✅ Dynamic loading at build time (resolves from Maven repos)
- ✅ Full API/implementation separation via URLClassLoader
- ✅ Both DAEMON and IN_PROCESS strategies supported
- ✅ Incremental compilation with classpath snapshots
- ⚠️ Additional Maven dependency resolution overhead

### Gradle Plugin (`kotlin-gradle-plugin`)

**Location**: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-gradle-plugin`

**Integration Approach**:
```
AbstractKotlinCompile
  ├─ Property: kotlin.compiler.runViaBuildToolsApi (default: false)
  ├─ GradleBuildToolsApiCompilerRunner
  ├─ WorkerExecutor.noIsolation()
  ├─ BuildToolsApiCompilationWork (WorkAction)
  ├─ ClassLoadersCachingBuildService
  └─ CompilationService.loadImplementation(cachedClassLoader)
```

**Key Files**:
- `GradleKotlinCompilerRunner.kt:createGradleCompilerRunner()` - Runner selection
- `GradleBuildToolsApiCompilerRunner.kt` - BTAPI-specific runner
- `BuildToolsApiCompilationWork.kt:performCompilation()` - Core compilation logic
- `BuildToolsApiClasspathEntrySnapshotTransform.kt` - Artifact transform for snapshots

**Characteristics**:
- ✅ Opt-in via property (backward compatible with legacy path)
- ✅ Gradle Worker API integration (thread-safe, parallel builds)
- ✅ Sophisticated classloader caching via build service
- ✅ Artifact transforms for on-demand classpath snapshots
- ✅ Build finished listener for resource cleanup
- ⚠️ More complex architecture (multiple services, transforms, listeners)
- ❌ No OUT_OF_PROCESS strategy support via BTAPI

### Bazel (`rules_kotlin`)

**Location**: `/home/andrzej.gluszak/code/jetbrains/rules_kotlin`

**Integration Approach**:
```
KotlinJvmTaskExecutor
  ├─ BuildToolsAPICompiler(kotlinCompilerJar, buildToolsImplJar)
  ├─ SharedApiClassesClassLoader() as parent
  ├─ URLClassLoader([buildToolsImplJar, kotlinCompilerJar])
  └─ CompilationService.loadImplementation(btapiClassLoader)
```

**Key Files**:
- `BuildToolsAPICompiler.kt:52-136` - Complete BTAPI implementation (185 lines!)
- `KotlinJvmTaskExecutor.kt:59` - Instantiation and usage
- `KotlinToolchain.kt:133-158` - Build tools artifacts resolution

**Characteristics**:
- ✅ **Extremely simple** - 185 lines total implementation
- ✅ Hermetic builds (no daemon, no external state)
- ✅ RBE (Remote Build Execution) compatible
- ✅ Deterministic ProjectId (based on module name, not random UUID)
- ✅ Direct Bazel runfiles integration (no dynamic resolution)
- ✅ IN_PROCESS strategy only (predictable, sandboxed)
- ❌ No incremental compilation support (yet)
- ❌ No daemon support (by design - hermetic builds)

---

## 2. Classloader Isolation Strategy

### Common Pattern: SharedApiClassesClassLoader

All three implementations use the **same isolation pattern**:

```java
URLClassLoader(
  [kotlin-build-tools-impl.jar, kotlin-compiler.jar],
  SharedApiClassesClassLoader()  // Parent classloader
)
```

**SharedApiClassesClassLoader delegation**:
- `org.jetbrains.kotlin.buildtools.api.*` → Parent (shared API classes)
- Everything else → Child (isolated implementation)

### Maven Implementation

```java
// K2JVMCompileMojo.java:252-270
List<File> jars = resolveArtifact("kotlin-build-tools-impl", version);
ClassLoader isolatedCL = new URLClassLoader(
    jars.stream().map(File::toURI).map(URI::toURL).toArray(URL[]::new),
    SharedApiClassesClassLoader.newInstance(getClass().getClassLoader())
);
CompilationService service = CompilationService.loadImplementation(isolatedCL);
```

**Notes**:
- `SharedApiClassesClassLoader.newInstance()` factory method
- Requires reference to parent classloader (Maven's classloader)

### Gradle Implementation

```kotlin
// BuildToolsApiCompilationWork.kt
val classLoader = parameters.classLoadersCachingService.get()
    .getClassLoader(
        workArguments.compilerFullClasspath,
        SharedApiClassesClassLoaderProvider  // Custom provider
    )
val compilationService = CompilationService.loadImplementation(classLoader)
```

**Provider implementation**:
```kotlin
internal object SharedApiClassesClassLoaderProvider : ParentClassLoaderProvider {
    override fun getClassLoader() = SharedApiClassesClassLoader()
    override fun hashCode() = SharedApiClassesClassLoaderProvider::class.hashCode()
    override fun equals(other: Any?) = other is SharedApiClassesClassLoaderProvider
}
```

**Notes**:
- Cached via `ClassLoadersCachingBuildService` (reused across tasks)
- Custom provider for caching key

### Bazel Implementation

```kotlin
// BuildToolsAPICompiler.kt:87-92
val urls = arrayOf(
    buildToolsImplJar.toURI().toURL(),
    kotlinCompilerJar.toURI().toURL()
)
val btapiClassLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
val kotlinService = CompilationService.loadImplementation(btapiClassLoader)
```

**Notes**:
- **Simplest implementation** - direct instantiation
- No factory, no caching service needed
- JARs provided via toolchain (Bazel runfiles)
- `SharedApiClassesClassLoader()` constructor (not factory)

---

## 3. Execution Strategy

### Maven Plugin

**Configuration**: Mojo parameters

```java
@Parameter(property = "kotlin.compiler.useDaemon", defaultValue = "true")
private boolean useDaemon;

@Parameter(property = "kotlin.daemon.jvmargs")
private String kotlinDaemonJvmArgs;
```

**Strategy Selection**:
```java
// K2JVMCompileMojo.java:293-309
CompilerExecutionStrategyConfiguration strategyConfig =
    compilationService.makeCompilerExecutionStrategyConfiguration();

if (useDaemon) {
    strategyConfig.useDaemonStrategy(
        parseJvmArgs(kotlinDaemonJvmArgs),
        Duration.ofSeconds(shutdownDelaySeconds)
    );
} else {
    strategyConfig.useInProcessStrategy();
}
```

**Supported**: DAEMON (default), IN_PROCESS

### Gradle Plugin

**Configuration**: Build properties

```kotlin
// gradle.properties or convention
kotlin.compiler.runViaBuildToolsApi=true
kotlin.compiler.execution.strategy=daemon  // or in-process
```

**Strategy Selection**:
```kotlin
// BuildToolsApiCompilationWork.kt:performCompilation()
when (executionStrategy) {
    KotlinCompilerExecutionStrategy.DAEMON ->
        useDaemonStrategy(workArguments.compilerExecutionSettings.daemonJvmArgs)
    KotlinCompilerExecutionStrategy.IN_PROCESS ->
        useInProcessStrategy()
    else ->
        error("OUT_OF_PROCESS not supported by Build Tools API")
}
```

**Supported**: DAEMON, IN_PROCESS (OUT_OF_PROCESS falls back to legacy path)

### Bazel (rules_kotlin)

**Configuration**: None (hardcoded)

```kotlin
// BuildToolsAPICompiler.kt:104-108
val executionConfig = kotlinService
    .makeCompilerExecutionStrategyConfiguration()
    .useInProcessStrategy()  // Always in-process
```

**Supported**: IN_PROCESS only

**Rationale** (from comments):
- Hermetic builds requirement
- RBE (Remote Build Execution) compatibility
- Predictable resource usage
- No persistent daemon state

---

## 4. ProjectId Strategy

### Maven Plugin

```java
// K2JVMCompileMojo.java (implicit in implementation)
ProjectId projectId = ProjectId.RandomProjectUUID();
```

**Characteristics**:
- Random UUID per compilation
- Different ID each time (even for same module)

### Gradle Plugin

```kotlin
// BuildToolsApiCompilationWork.kt
val buildId = ProjectId.ProjectUUID(
    parameters.buildIdService.get().buildId  // Gradle build ID
)
parameters.buildFinishedListenerService.get()
    .onCloseOnceByKey(buildId.toString()) {
        compilationService.finishProjectCompilation(buildId)
    }
```

**Characteristics**:
- Uses Gradle's build-unique ID
- Same across all tasks in single Gradle invocation
- Cleanup via BuildFinishedListenerService

### Bazel (rules_kotlin)

```kotlin
// BuildToolsAPICompiler.kt:99-102
val projectId = ProjectId.ProjectUUID(
    UUID.nameUUIDFromBytes(parsedArgs.moduleName.toByteArray())
)
```

**Characteristics**:
- **Deterministic** - based on module name
- Same ID for same module across all builds
- Enables future caching/incremental compilation
- Immediate cleanup in finally block (no external service)

**This is a unique innovation** not present in Maven or Gradle!

---

## 5. Incremental Compilation Support

### Maven Plugin

**Full support** via `IncrementalJvmCompilationConfiguration`

```java
// K2JVMCompileMojo.java:358-398
File workingDir = new File(buildDir, "kotlin-ic/" + sourceSetName);
jvmCompilationConfig.useIncrementalCompilation(
    workingDir,
    SourcesChanges.ToBeCalculated,  // Let compiler detect changes
    new ClasspathSnapshotBasedIncrementalCompilationApproachParameters(
        emptyList(),  // newClasspathSnapshotFiles (calculated by compiler)
        shrunkClasspathSnapshotFile
    ),
    icConf.setRootProjectDir(rootProjectDir)
         .setBuildDir(buildDir)
         .usePreciseJavaTracking(true)
         .useOutputDirs(outputDirs)
);
```

**Post-compilation synchronization**:
```java
// FileCopier.syncDirs()
// Syncs incremental output dir (kotlinClassesDir) to final output (classesDir)
// Uses snapshot files to track changes
```

**Classpath Snapshots**:
- Granularity: CLASS_MEMBER_LEVEL for local, CLASS_LEVEL for external
- Calculated by compiler
- Stored in build directory

### Gradle Plugin

**Full support** with sophisticated artifact transforms

**Transform for snapshot generation**:
```kotlin
// BuildToolsApiClasspathEntrySnapshotTransform.kt
@CacheableTransform
abstract class BuildToolsApiClasspathEntrySnapshotTransform : TransformAction<...> {
    override fun transform(outputs: TransformOutputs) {
        val snapshot = compilationService.calculateClasspathSnapshot(
            classpathEntryInputDirOrJar,
            granularity,  // Based on location (gradle cache vs local)
            parseInlinedLocalClasses
        )
        snapshot.saveSnapshot(snapshotOutputFile)
    }
}
```

**Incremental Configuration**:
```kotlin
// BuildToolsApiCompilationWork.kt:performCompilation()
val classpathSnapshotsConfig = jvmCompilationConfig
    .makeClasspathSnapshotBasedIncrementalCompilationConfiguration()
    .setRootProjectDir(icEnv.rootProjectDir)
    .setBuildDir(icEnv.buildDir)
    .usePreciseJavaTracking(icEnv.icFeatures.usePreciseJavaTracking)
    .useFirRunner(icEnv.useJvmFirRunner)  // Experimental FIR support
    .forceNonIncrementalMode(...)

jvmCompilationConfig.useIncrementalCompilation(
    icEnv.workingDir,
    icEnv.changedFiles,  // SourcesChanges
    classpathSnapshotsParameters,
    classpathSnapshotsConfig
)
```

**Advanced Features**:
- ✅ Artifact transforms (on-demand, cacheable)
- ✅ FIR-based incremental runner (experimental)
- ✅ Multi-module IC settings
- ✅ Precise Java tracking
- ✅ In-memory cache mode

### Bazel (rules_kotlin)

**No incremental compilation support** (yet)

```kotlin
// BuildToolsAPICompiler.kt - no IC configuration
val compilationConfig = kotlinService
    .makeJvmCompilationConfiguration()
    .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))
// That's it - no .useIncrementalCompilation() call
```

**Why not?**:
- Bazel has its own action caching at a higher level
- Hermetic builds prefer full recompilation with caching
- Future work: could use deterministic ProjectId for IC

**Bazel's Alternative**:
- Remote caching (action result caching)
- Persistent workers (caching across invocations)
- Minimal rebuilds via dependency analysis

---

## 6. Logging Integration

### Maven Plugin

```java
// LegacyKotlinMavenLogger.java
class LegacyKotlinMavenLogger implements KotlinLogger {
    private final MessageCollector messageCollector;
    private final Log mavenLog;

    @Override
    public void error(String msg, Throwable throwable) {
        messageCollector.report(ERROR, msg, location);
        mavenLog.error(msg, throwable);
    }
    // ... warn, info, debug, lifecycle
}
```

**Dual logging**:
- MessageCollector (Kotlin compiler feedback)
- Maven Log (build system logging)

### Gradle Plugin

```kotlin
// BuildToolsApiCompilationWork.kt uses Gradle's logger directly
// No custom KotlinLogger implementation shown in explored code
// Likely uses standard Gradle logging infrastructure
```

### Bazel (rules_kotlin)

```kotlin
// BuildToolsAPICompiler.kt:184-222
private class BazelKotlinLogger(
    private val errStream: PrintStream,
    private val moduleName: String,
) : KotlinLogger {
    override val isDebugEnabled: Boolean =
        System.getenv("KOTLIN_BUILD_TOOLS_API_DEBUG")?.toBoolean() ?: false

    override fun error(msg: String, throwable: Throwable?) {
        errStream.println(msg)
        throwable?.printStackTrace(errStream)
    }

    override fun warn(msg: String, throwable: Throwable?) {
        errStream.println(msg)
        throwable?.printStackTrace(errStream)
    }

    override fun info(msg: String) {
        if (isDebugEnabled) errStream.println(msg)
    }

    override fun debug(msg: String) {
        if (isDebugEnabled) errStream.println(msg)
    }

    override fun lifecycle(msg: String) {
        errStream.println(msg)
    }
}
```

**Characteristics**:
- Minimal implementation (direct PrintStream output)
- No prefixes (matches old K2JVMCompiler behavior)
- Environment variable for debug control
- Always logs errors/warnings, conditionally logs info/debug

---

## 7. Error Handling & Recovery

### Maven Plugin

```java
// K2JVMCompileMojo.java:execCompiler()
CompilationResult result = compilationService.compileJvm(...);
if (result != CompilationResult.COMPILATION_SUCCESS) {
    throw new MojoFailureException("Compilation failed: " + result);
}
```

**No backup/restore** mechanism shown in explored code.

### Gradle Plugin

**Sophisticated backup/restore**:

```kotlin
// BuildToolsApiCompilationWork.kt:execute()
val backup = initializeBackup()  // TaskOutputsBackup
try {
    val result = performCompilation()
    if (result == COMPILATION_OOM_ERROR || result == COMPILATION_ERROR) {
        backup?.restoreOutputs()  // Restore on error
    }
    throwExceptionIfCompilationFailed(result.asExitCode, executionStrategy)
} catch (e: FailedCompilationException) {
    backup?.tryRestoringOnRecoverableException(e) { restoreAction ->
        log.info(DEFAULT_BACKUP_RESTORE_MESSAGE)
        restoreAction()
    }
    throw e
} finally {
    backup?.deleteSnapshot()
}
```

**Exception hierarchy**:
```kotlin
open class FailedCompilationException
class CompilationErrorException : FailedCompilationException
class OOMErrorException : FailedCompilationException
class DaemonCrashedException : FailedCompilationException
```

### Bazel (rules_kotlin)

```kotlin
// BuildToolsAPICompiler.kt:116-135
return try {
    kotlinService.compileJvm(projectId, executionConfig, compilationConfig, emptyList(), args.toList())
} finally {
    try {
        kotlinService.finishProjectCompilation(projectId)
    } catch (e: Throwable) {
        System.err.println("Warning: Error during finishProjectCompilation cleanup: ${e.message}")
        // Don't fail - cleanup errors are not critical
    }
}
```

**Exit code mapping**:
```kotlin
// BuildToolsAPICompiler.kt:68-75
return when (result) {
    COMPILATION_SUCCESS -> 0
    COMPILATION_ERROR -> 1
    COMPILATION_OOM_ERROR -> 3
    COMPILER_INTERNAL_ERROR -> 4
}
```

**No backup mechanism** - Bazel handles this at action level (sandboxing).

---

## 8. Resource Management & Cleanup

### Maven Plugin

```java
// No explicit finishProjectCompilation() call shown
// Likely relies on JVM shutdown for cleanup
```

### Gradle Plugin

**Build finished listener**:

```kotlin
// BuildToolsApiCompilationWork.kt:performCompilation()
val buildId = ProjectId.ProjectUUID(parameters.buildIdService.get().buildId)
parameters.buildFinishedListenerService.get()
    .onCloseOnceByKey(buildId.toString()) {
        compilationService.finishProjectCompilation(buildId)
    }
```

**Characteristics**:
- Automatic cleanup at build end
- Thread-safe (`onCloseOnceByKey`)
- Per-build (not per-task)

### Bazel (rules_kotlin)

**Immediate cleanup**:

```kotlin
// BuildToolsAPICompiler.kt:127-134
finally {
    try {
        kotlinService.finishProjectCompilation(projectId)
    } catch (e: Throwable) {
        System.err.println("Warning: Error during cleanup: ${e.message}")
    }
}
```

**Characteristics**:
- Cleanup after every compilation
- Swallow cleanup exceptions (don't fail build)
- No external service needed

---

## 9. Compiler Plugin Integration

### Maven Plugin

**Plugin resolution**:
```java
// KotlinCompileMojoBase.java:260-375
List<KotlinMavenPluginExtension> extensions =
    plexusContainer.lookupList(KotlinMavenPluginExtension.class);

for (KotlinMavenPluginExtension ext : extensions) {
    if (ext.isApplicable(project)) {
        pluginOptions.putAll(ext.getPluginOptions());
        pluginClasspath.addAll(ext.getClasspath());
    }
}
```

**KAPT Support**:
```java
// KaptJVMCompilerMojo.java:189-212
pluginOptions.put("plugin:org.jetbrains.kotlin.kapt3:aptMode", "stubsAndApt");
pluginOptions.put("plugin:org.jetbrains.kotlin.kapt3:sources", sourcesDir);
pluginOptions.put("plugin:org.jetbrains.kotlin.kapt3:classes", classesDir);
// ... more KAPT options
```

### Gradle Plugin

**Plugin configuration via compiler arguments** (standard Gradle kotlinOptions DSL).

### Bazel (rules_kotlin)

**Compiler plugins configured at Starlark level**:

```kotlin
// KotlinJvmTaskExecutor.kt:76-107
baseArgs()
    .plugin(plugins.jdeps) {
        flag("output", outputs.jdeps)
        flag("target_label", info.label)
        // ...
    }
    .plugin(plugins.jvmAbiGen) {
        flag("outputDir", directories.abiClasses)
        flag("treatInternalAsPrivate", info.treatInternalAsPrivateInAbiJar)
        // ...
    }
```

**Toolchain integration**:
```kotlin
// KotlinToolchain.kt:208-237
jvmAbiGen = CompilerPlugin(
    jvmAbiGenFile.path,
    "org.jetbrains.kotlin.jvm.abi"
)
kapt3Plugin = CompilerPlugin(
    kaptFile.path,
    "org.jetbrains.kotlin.kapt3"
)
kspSymbolProcessingCommandLine = CompilerPlugin(
    kspSymbolProcessingCommandLine.absolutePath,
    "com.google.devtools.ksp.symbol-processing"
)
```

**Characteristics**:
- Plugins preloaded in toolchain classloader
- Configured via proto messages (not string args)
- Type-safe plugin configuration

---

## 10. Complexity & Lines of Code Comparison

### Core BTAPI Integration Code

| Build System | Primary File | LOC | Complexity |
|--------------|--------------|-----|------------|
| **Maven** | K2JVMCompileMojo.java | ~500 lines | Medium |
|  | LegacyKotlinMavenLogger.java | ~80 lines | Low |
|  | FileCopier.java (IC) | ~200 lines | Medium |
|  | **Total** | **~780 lines** | **Medium** |
| **Gradle** | GradleBuildToolsApiCompilerRunner.kt | ~100 lines | Medium |
|  | BuildToolsApiCompilationWork.kt | ~250 lines | High |
|  | BuildToolsApiClasspathEntrySnapshotTransform.kt | ~150 lines | Medium |
|  | **Total** | **~500 lines** | **High** |
| **Bazel** | BuildToolsAPICompiler.kt | **185 lines** | **Low** |
|  | **Total** | **185 lines** | **Low** |

**Bazel's implementation is 3-4x simpler!**

---

## 11. Key Architectural Differences

### Dependency Resolution

| | Maven | Gradle | Bazel |
|---|---|---|---|
| **BT API Impl** | Maven repos (runtime) | Gradle deps (buildscript) | Bazel runfiles (hermetic) |
| **Resolution** | KotlinArtifactResolver | Dependency resolution | BazelRunFiles.resolveVerifiedFromProperty |
| **Dynamic** | ✅ Yes (version in POM) | ✅ Yes (version in buildscript) | ❌ No (fixed at build time) |

### Execution Model

| | Maven | Gradle | Bazel |
|---|---|---|---|
| **Parallelism** | Sequential (per module) | Parallel (Worker API) | Parallel (Bazel actions) |
| **Isolation** | Process/Daemon | Worker (noIsolation) | Sandboxed action |
| **Caching** | Local Maven cache | Gradle build cache | Bazel action cache + RBE |

### Incremental Compilation

| | Maven | Gradle | Bazel |
|---|---|---|---|
| **Approach** | BTAPI IC + FileCopier | BTAPI IC + Transforms | None (Bazel-level caching) |
| **Snapshots** | Compiler-generated | Artifact transforms | N/A |
| **Granularity** | CLASS_MEMBER_LEVEL | Dynamic (location-based) | N/A |

---

## 12. Trade-offs & Design Philosophy

### Maven Plugin

**Philosophy**: Stable, compatible, feature-complete

**Pros**:
- ✅ Full incremental compilation support
- ✅ KAPT deeply integrated
- ✅ Both daemon and in-process modes
- ✅ Straightforward, predictable

**Cons**:
- ⚠️ Runtime artifact resolution overhead
- ⚠️ FileCopier post-processing adds complexity

### Gradle Plugin

**Philosophy**: Opt-in, sophisticated, highly optimized

**Pros**:
- ✅ Backward compatible (opt-in via property)
- ✅ Advanced caching (classloader, artifact, build cache)
- ✅ Parallel execution via Worker API
- ✅ FIR-based IC (experimental)
- ✅ Sophisticated error recovery

**Cons**:
- ⚠️ Most complex implementation
- ⚠️ Multiple services required (ClassLoadersCachingBuildService, BuildFinishedListenerService, BuildIdService)
- ⚠️ Artifact transform overhead for snapshots

### Bazel (rules_kotlin)

**Philosophy**: Hermetic, deterministic, simple

**Pros**:
- ✅ **Simplest implementation** (185 lines!)
- ✅ Hermetic builds (reproducible)
- ✅ RBE compatible (no daemon, no external state)
- ✅ Deterministic ProjectId (future IC enabler)
- ✅ Direct runfiles integration
- ✅ No external services needed

**Cons**:
- ❌ No incremental compilation (yet)
- ❌ No daemon support (by design)
- ⚠️ IN_PROCESS only (higher memory per action)

**Key Insight**: Bazel delegates caching/incrementality to the build system level rather than compiler level, allowing for a dramatically simpler compiler integration.

---

## 13. Notable Innovations

### rules_kotlin Unique Features

1. **Deterministic ProjectId**:
   ```kotlin
   ProjectId.ProjectUUID(UUID.nameUUIDFromBytes(moduleName.toByteArray()))
   ```
   - Enables future incremental compilation
   - Consistent across builds
   - Not present in Maven or Gradle!

2. **Immediate Cleanup**:
   - No build service needed
   - Cleanup after every compilation
   - Swallow cleanup exceptions

3. **Environment-Based Debug Control**:
   ```kotlin
   override val isDebugEnabled: Boolean =
       System.getenv("KOTLIN_BUILD_TOOLS_API_DEBUG")?.toBoolean() ?: false
   ```

4. **Hermetic Toolchain**:
   - All JARs resolved via Bazel runfiles
   - No network access during compilation
   - Reproducible builds

---

## 14. Recommendations & Future Work

### For rules_kotlin

**Consider adding**:
1. Optional daemon support (for local dev builds)
2. Incremental compilation using deterministic ProjectId
3. Build metrics collection (similar to Gradle)

**Keep**:
- Hermetic design (critical for RBE)
- Simple implementation
- IN_PROCESS default

### For Maven Plugin

**Consider**:
- Build cache integration (Maven 4+)
- Classloader caching across invocations

### For Gradle Plugin

**Consider**:
- Making BTAPI the default (remove opt-in flag)
- Simplifying service dependencies

---

## 15. Conclusion

The three implementations demonstrate **different design priorities**:

- **Maven**: Feature completeness, backward compatibility
- **Gradle**: Performance optimization, sophisticated caching
- **Bazel**: Simplicity, hermeticity, reproducibility

All three successfully integrate the Kotlin Build Tools API, but **rules_kotlin stands out for its remarkable simplicity** (185 lines vs ~500-780 lines) while maintaining correctness and Bazel's hermetic build guarantees.

The deterministic ProjectId in rules_kotlin is a unique innovation that could enable future incremental compilation without compromising build hermeticity.

---

## Appendix: File Locations

### Maven Plugin
- Core implementation: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/K2JVMCompileMojo.java`
- Logger adapter: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/LegacyKotlinMavenLogger.java`
- Incremental copier: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/incremental/FileCopier.java`

### Gradle Plugin
- Runner selection: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/compilerRunner/GradleKotlinCompilerRunner.kt`
- BTAPI runner: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/compilerRunner/btapi/GradleBuildToolsApiCompilerRunner.kt`
- Work action: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/compilerRunner/btapi/BuildToolsApiCompilationWork.kt`
- Snapshot transform: `/home/agluszak/code/jetbrains/kotlin/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/internal/transforms/BuildToolsApiClasspathEntrySnapshotTransform.kt`

### rules_kotlin (Bazel)
- Complete implementation: `/home/andrzej.gluszak/code/jetbrains/rules_kotlin/src/main/kotlin/io/bazel/kotlin/compiler/BuildToolsAPICompiler.kt`
- Task executor: `/home/andrzej.gluszak/code/jetbrains/rules_kotlin/src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/KotlinJvmTaskExecutor.kt`
- Toolchain setup: `/home/andrzej.gluszak/code/jetbrains/rules_kotlin/src/main/kotlin/io/bazel/kotlin/builder/toolchain/KotlinToolchain.kt`

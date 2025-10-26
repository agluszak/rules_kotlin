# Kotlin Build Tools API 2.2.x - Incremental Compilation API Reference

This document describes the correct API for implementing Incremental Compilation using the Kotlin Build Tools API in version 2.2.x, based on analysis of the official Maven and Gradle plugins plus Meta's Buck2 implementation.

## API Overview

The BTAPI 2.2.x IC implementation differs significantly from earlier designs:

1. **Operation-based API**: IC is configured on `JvmCompilationOperation`, not `JvmCompilationConfiguration`
2. **Snapshot-based approach**: Uses `JvmSnapshotBasedIncrementalCompilationConfiguration`
3. **No direct classpath snapshot calculation**: Snapshots are managed internally by the compilation operation

## Core Types

### JvmCompilationOperation

Entry point for JVM compilation created via:

```java
JvmPlatformToolchain jvmToolchain = JvmPlatformToolchain.from(kotlinToolchains);
JvmCompilationOperation compileOperation = jvmToolchain.createJvmCompilationOperation(
    allSources,      // List<Path>: All source files
    destination      // Path: Output directory
);
```

### JvmSnapshotBasedIncrementalCompilationConfiguration

Configuration object for IC with the following constructor:

```java
public JvmSnapshotBasedIncrementalCompilationConfiguration(
    Path rootProjectDir,                                    // Base directory for caches
    SourcesChanges sourcesChanges,                          // Source change tracking
    List<ModuleSnapshot> classpathChanges,                  // Classpath snapshot changes (usually empty)
    Path shrunkClasspathSnapshotFile,                       // Path for classpath snapshot
    JvmSnapshotBasedIncrementalCompilationOptions options   // Additional IC options
)
```

### SourcesChanges

Union type indicating how source changes are handled:

- `SourcesChanges.ToBeCalculated.INSTANCE` - Let the compiler auto-detect changes (Kotlin 2.1.20+)
- `SourcesChanges.Known(Collection<Path>)` - Provide explicit list of changed files

### JvmSnapshotBasedIncrementalCompilationOptions

Created via:

```java
JvmSnapshotBasedIncrementalCompilationOptions options =
    compileOperation.createSnapshotBasedIcOptions();
```

Additional configuration can be set on this object.

## Implementation Pattern

### Basic IC Setup (Maven Plugin Pattern)

```java
// 1. Create compilation operation
JvmPlatformToolchain jvmToolchain = JvmPlatformToolchain.from(kotlinToolchains);
JvmCompilationOperation compileOperation = jvmToolchain.createJvmCompilationOperation(
    allSources,
    destination
);

// 2. Configure incremental compilation
Path cachesDir = Paths.get(incrementalCachesRoot, sourceSetName);
Files.createDirectories(cachesDir);

// 3. Create IC options
JvmSnapshotBasedIncrementalCompilationOptions icOptions =
    compileOperation.createSnapshotBasedIcOptions();

// 4. Configure IC on the operation
compileOperation.set(
    JvmCompilationOperation.INCREMENTAL_COMPILATION,
    new JvmSnapshotBasedIncrementalCompilationConfiguration(
        cachesDir,                                              // Root dir for IC caches
        SourcesChanges.ToBeCalculated.INSTANCE,                 // Auto-detect source changes
        Collections.emptyList(),                                // No explicit classpath changes
        cachesDir.resolve("shrunk-classpath-snapshot.bin"),     // Snapshot file location
        icOptions                                               // IC options
    )
);

// 5. Execute compilation
CompilationResult result = buildSession.executeOperation(
    compileOperation,
    executionPolicy,
    logger
);
```

### Advanced: Manual Source Change Tracking

If your build system already knows which files changed (like Buck2 with incremental actions):

```java
// Collect changed files from your build system
Set<Path> changedFiles = detectChangedFiles();

// Use explicit source changes instead of auto-detection
compileOperation.set(
    JvmCompilationOperation.INCREMENTAL_COMPILATION,
    new JvmSnapshotBasedIncrementalCompilationConfiguration(
        cachesDir,
        new SourcesChanges.Known(changedFiles),  // <-- Explicit changes
        Collections.emptyList(),
        cachesDir.resolve("shrunk-classpath-snapshot.bin"),
        icOptions
    )
);
```

## Key Differences from Earlier Designs

### What Changed in 2.2.x

| Aspect | Old API (Pre-2.2) | New API (2.2.x) |
|--------|------------------|-----------------|
| Configuration point | `JvmCompilationConfiguration` | `JvmCompilationOperation` |
| Classpath snapshots | Manual `calculateClasspathSnapshot()` | Automatic via `JvmSnapshotBasedIncrementalCompilationConfiguration` |
| IC activation | `useIncrementalCompilation()` method | `set(INCREMENTAL_COMPILATION, ...)` |
| Source change detection | Manual only | Auto-detect or manual |

### Why These Changes?

1. **Simplified Integration**: No need to manually calculate classpath snapshots
2. **Better Encapsulation**: IC state management is internal to the operation
3. **Flexible Source Tracking**: Support both auto-detection and explicit change lists
4. **Operation Lifecycle**: IC state tied to compilation operation lifecycle

## Buck2/Meta Implementation Notes

From [Meta's blog post](https://engineering.fb.com/2025/08/26/open-source/enabling-kotlin-incremental-compilation-on-buck2/):

### Classpath Snapshot Generation

Buck2 creates separate actions for classpath snapshot generation:

```java
// Separate action to generate classpath snapshot from library outputs
Path snapshotFile = generateClasspathSnapshot(libraryJars);

// Pass snapshot as input to dependent compilation
// The snapshot is used internally by JvmSnapshotBasedIncrementalCompilationConfiguration
```

### Relocatable Caches

To support distributed builds, Buck2 explicitly configures project and build directories:

```java
// Configure absolute paths for relocatable caches
Path rootProjectDir = getAbsoluteProjectRoot();
Path cachesDir = getAbsoluteBuildDir().resolve("ic-caches");

// Use these in IC configuration
new JvmSnapshotBasedIncrementalCompilationConfiguration(
    rootProjectDir,  // Stable across different machines
    sourcesChanges,
    classpathChanges,
    cachesDir.resolve("snapshot.bin"),
    icOptions
);
```

### Multiple Compilation Rounds

IC may compile in multiple rounds to handle circular dependencies:

1. **Round 1**: Compile changed files
2. **Round 2+**: Compile files affected by changes from Round 1

**Impact on compiler plugins**: Plugins must accumulate results across rounds, not replace them.

## Execution Strategies and IC

### In-Process Strategy

```java
ExecutionPolicy executionPolicy = kotlinToolchains.createInProcessExecutionPolicy();
```

- Runs compiler in the same process
- **Kotlin 2.2+ supports IC** with in-process (previously didn't)
- Best for hermetic builds (Bazel, Buck2)
- No daemon overhead

### Daemon Strategy

```java
ExecutionPolicy.WithDaemon daemonPolicy = kotlinToolchains.createDaemonExecutionPolicy();
daemonPolicy.set(ExecutionPolicy.WithDaemon.JVM_ARGUMENTS, jvmArgs);
daemonPolicy.set(ExecutionPolicy.WithDaemon.SHUTDOWN_DELAY_MILLIS, shutdownDelay);
```

- Runs compiler in a long-lived daemon process
- Maintains warm JVM and loaded classes
- Maven plugin uses daemon by default

### RBE/Remote Execution

IC is typically **disabled for remote execution** because:
- Cache state cannot be reliably shared across remote machines
- Hermetic builds require clean compilation
- Local IC provides the speedup; remote builds focus on parallelization

## Bazel-Specific Considerations

### Persistent Workers

Bazel's persistent workers naturally support IC:
- Worker process stays alive between builds
- IC caches persist in worker memory/disk
- Perfect fit for in-process execution strategy

### Hermetic Builds

Bazel's hermetic principle conflicts with shared IC state:
- **Solution**: Store IC caches in Bazel's output tree
- Caches are invalidated on `bazel clean`
- No cross-machine cache sharing (RBE incompatible)

### Action Caching

Bazel's action caching provides module-level incrementality:
- IC provides file-level incrementality within a module
- Complementary strategies, not competing

## Error Handling

```java
try {
    CompilationResult result = buildSession.executeOperation(
        compileOperation,
        executionPolicy,
        logger
    );

    switch (result) {
        case COMPILATION_SUCCESS:
            return ExitCode.OK;
        case COMPILATION_ERROR:
            return ExitCode.COMPILATION_ERROR;
        case COMPILATION_OOM_ERROR:
            return ExitCode.OOM_ERROR;
        default:
            return ExitCode.INTERNAL_ERROR;
    }
} catch (Throwable t) {
    logger.error("IC setup failed", t);
    // Fall back to non-incremental compilation
    return compileWithoutIC();
}
```

## Complete Example

Here's a complete example adapted for Bazel/rules_kotlin:

```java
@OptIn(ExperimentalBuildToolsApi.class)
public CompilationResult compileWithIC(
    File kotlinCompilerJar,
    File buildToolsImplJar,
    List<Path> sources,
    Path outputDir,
    Path icCacheDir,
    List<File> classpathEntries,
    PrintStream logger
) throws Exception {
    // 1. Load Build Tools API
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true");
    URL[] urls = new URL[] {
        buildToolsImplJar.toURI().toURL(),
        kotlinCompilerJar.toURI().toURL()
    };
    ClassLoader btapiClassLoader = new URLClassLoader(urls, SharedApiClassesClassLoader());
    KotlinToolchains kotlinToolchains = KotlinToolchains.loadImplementation(btapiClassLoader);

    // 2. Create execution policy (in-process for hermetic builds)
    ExecutionPolicy executionPolicy = kotlinToolchains.createInProcessExecutionPolicy();

    // 3. Create JVM toolchain and compilation operation
    JvmPlatformToolchain jvmToolchain = JvmPlatformToolchain.from(kotlinToolchains);
    JvmCompilationOperation compileOperation = jvmToolchain.createJvmCompilationOperation(
        sources,
        outputDir
    );

    // 4. Configure incremental compilation
    Files.createDirectories(icCacheDir);
    JvmSnapshotBasedIncrementalCompilationOptions icOptions =
        compileOperation.createSnapshotBasedIcOptions();

    compileOperation.set(
        JvmCompilationOperation.INCREMENTAL_COMPILATION,
        new JvmSnapshotBasedIncrementalCompilationConfiguration(
            icCacheDir,
            SourcesChanges.ToBeCalculated.INSTANCE,
            Collections.emptyList(),
            icCacheDir.resolve("classpath-snapshot.bin"),
            icOptions
        )
    );

    // 5. Apply compiler arguments
    compileOperation.getCompilerArguments().applyArgumentStrings(compilerArgs);

    // 6. Execute compilation
    try (KotlinToolchains.BuildSession buildSession = kotlinToolchains.createBuildSession()) {
        return buildSession.executeOperation(
            compileOperation,
            executionPolicy,
            new BazelKotlinLogger(logger)
        );
    }
}
```

## References

- [Official Build Tools API Documentation](https://kotlinlang.org/docs/build-tools-api.html)
- [Kotlin Maven Plugin Source](https://github.com/JetBrains/kotlin/blob/master/libraries/tools/kotlin-maven-plugin/src/main/java/org/jetbrains/kotlin/maven/K2JVMCompileMojo.java)
- [Meta Blog: IC on Buck2](https://engineering.fb.com/2025/08/26/open-source/enabling-kotlin-incremental-compilation-on-buck2/)
- [BTAPI KEEP Proposal](https://github.com/Kotlin/KEEP/issues/421)
- [Kotlin Blog: Fast Compilation Internals](https://blog.jetbrains.com/kotlin/2020/09/the-dark-secrets-of-fast-compilation-for-kotlin/)

## Implementation Checklist for rules_kotlin

- [x] Research BTAPI 2.2.x IC API
- [ ] Update `BuildToolsAPICompiler.kt` to use `JvmCompilationOperation` API
- [ ] Remove incorrect `calculateClasspathSnapshot()` and `useIncrementalCompilation()` calls
- [ ] Implement `JvmSnapshotBasedIncrementalCompilationConfiguration` setup
- [ ] Test with simple Kotlin targets
- [ ] Add integration tests
- [ ] Document IC feature for users
- [ ] Measure performance improvements

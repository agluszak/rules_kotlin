# Kotlin Compiler Version Reporting

## Overview

As of the Build Tools API integration, rules_kotlin now provides the ability to query the Kotlin compiler version at runtime. This feature is useful for debugging, version checks, and compatibility verification.

## API

### BuildToolsAPICompiler

The `BuildToolsAPICompiler` class provides direct access to the compiler version:

```kotlin
val compiler = BuildToolsAPICompiler(kotlinCompilerJar, buildToolsImplJar)
val version: String = compiler.getCompilerVersion()
```

**Returns**: A version string following Kotlin's versioning scheme:
- Stable release: `"2.1.0"`, `"2.0.20"`
- Pre-release: `"2.2.0-Beta1"`, `"2.0.0-RC2"`

### KotlinToolchain

The `KotlinToolchain` class also exposes the compiler version:

```kotlin
val toolchain = KotlinToolchain.createToolchain()
val version: String = toolchain.getCompilerVersion()
```

## Implementation Details

### Performance Optimization

The `CompilationService` is cached as a lazy property to avoid repeated classloader creation:

```kotlin
@OptIn(ExperimentalBuildToolsApi::class)
private val compilationService: CompilationService by lazy {
    // Classloader creation only happens once
    val urls = arrayOf(buildToolsImplJar.toURI().toURL(), kotlinCompilerJar.toURI().toURL())
    val btapiClassLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
    CompilationService.loadImplementation(btapiClassLoader)
}
```

**Benefits**:
- Classloader is created only once per `BuildToolsAPICompiler` instance
- Subsequent calls to `getCompilerVersion()` are fast (no classloader overhead)
- Optimal for persistent worker scenarios where the same compiler instance is reused

### Version Format

The version string is retrieved from the Kotlin compiler's metadata via the Build Tools API. The format follows Kotlin's official versioning:

- **Major.Minor.Patch**: `2.1.0`
- **With Qualifier**: `2.2.0-Beta1`, `2.0.20-RC2`

Version components:
- **Major**: Kotlin language version (e.g., 2)
- **Minor**: Feature release (e.g., 1)
- **Patch**: Bug fix release (e.g., 0)
- **Qualifier** (optional): Pre-release identifier (e.g., Beta1, RC2)

## Use Cases

### 1. Debugging Compilation Issues

When investigating compilation problems, knowing the exact compiler version helps:

```kotlin
val toolchain = KotlinToolchain.createToolchain()
println("Kotlin compiler version: ${toolchain.getCompilerVersion()}")
// Output: Kotlin compiler version: 2.1.0
```

### 2. Version Compatibility Checks

Rules can verify minimum compiler versions before attempting compilation:

```python
# Starlark example (future work)
def _kt_compile_impl(ctx):
    toolchain = ctx.toolchains["@rules_kotlin//kotlin:toolchain_type"]
    version = toolchain.compiler_version  # Exposed from toolchain

    # Check minimum version
    if not version_at_least(version, "2.0.0"):
        fail("Kotlin 2.0+ required for this feature, got: " + version)
```

### 3. Logging and Diagnostics

Include compiler version in build logs for reproducibility:

```kotlin
val version = toolchain.getCompilerVersion()
logger.info("Compiling with Kotlin $version")
```

### 4. CI/CD Verification

Verify that the correct compiler version is being used in CI pipelines:

```bash
# Extract version from build logs
bazel build //... --define=kt_trace=1 2>&1 | grep "Kotlin compiler version"
```

## Testing

Unit tests verify version reporting functionality:

```java
@Test
public void testGetCompilerVersion_returnsValidVersion() {
    KotlinToolchain toolchain = KotlinToolchain.createToolchain();
    String version = toolchain.getCompilerVersion();

    assertThat(version).isNotEmpty();
    assertThat(VERSION_PATTERN.matcher(version).matches()).isTrue();
}
```

Test coverage:
- ✅ Version string format validation (regex matching)
- ✅ Consistency across multiple calls
- ✅ Expected version range checks (Kotlin 2.0+)
- ✅ Caching performance verification

## Comparison with Other Build Systems

### Maven Plugin

```java
// maven-kotlin-plugin
CompilationService service = CompilationService.loadImplementation(classLoader);
String version = service.getCompilerVersion();
```

### Gradle Plugin

```kotlin
// kotlin-gradle-plugin
val version = project.getKotlinPluginVersion()  // Plugin version, not compiler version
```

**Note**: Gradle uses plugin version, not direct compiler version. The Build Tools API provides direct compiler version access.

### rules_kotlin

```kotlin
// rules_kotlin
val toolchain = KotlinToolchain.createToolchain()
val version = toolchain.getCompilerVersion()  // Direct compiler version via BTAPI
```

**Advantage**: rules_kotlin provides direct access to the actual compiler version via the Build Tools API, not the plugin/rules version.

## Future Enhancements

### Expose Version to Starlark Rules

Potential future work to expose version in Bazel rules:

```python
# Future: kotlin/internal/toolchains.bzl
def _kt_jvm_toolchain_impl(ctx):
    # Get compiler version and expose it
    version = get_compiler_version(ctx.files.kotlin_compiler)

    return [
        platform_common.ToolchainInfo(
            compiler_version = version,  # NEW: Exposed to rules
            # ... other fields
        ),
    ]
```

### Version-Specific Compilation Flags

Enable version-specific optimizations:

```python
# Future: Conditional compilation based on version
if version_at_least(toolchain.compiler_version, "2.2.0"):
    args.append("-Xcontext-receivers")  # Only available in 2.2.0+
```

### Automatic Version Compatibility Warnings

Warn users about potential compatibility issues:

```python
# Future: Version compatibility checks
if toolchain.compiler_version.startsWith("2.0") and uses_k2_features(ctx):
    print("Warning: Using K2 features with Kotlin 2.0 - consider upgrading to 2.1+")
```

## Related Documentation

- [Build Tools API Comparison](../BTAPI_COMPARISON.md) - Comparison with Maven and Gradle implementations
- [Build Tools API Next Steps](../BTAPI_NEXT_STEPS.md) - Future enhancement roadmap
- [Kotlin Build Tools API Official Docs](https://github.com/JetBrains/kotlin/tree/master/compiler/build-tools/kotlin-build-tools-api)

## Changelog

### 2025-01-26: Initial Implementation

- Added `getCompilerVersion()` method to `BuildToolsAPICompiler`
- Exposed version via `KotlinToolchain.getCompilerVersion()`
- Refactored to cache `CompilationService` for optimal performance
- Added comprehensive unit tests
- Documented version format and use cases

---

**See Also**:
- `src/main/kotlin/io/bazel/kotlin/compiler/BuildToolsAPICompiler.kt`
- `src/main/kotlin/io/bazel/kotlin/builder/toolchain/KotlinToolchain.kt`
- `src/test/kotlin/io/bazel/kotlin/compiler/BuildToolsAPICompilerTest.java`

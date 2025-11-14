KSP1 to KSP2 Migration Plan for rules_kotlin

Executive Summary

Based on my investigation, here's a comprehensive plan to migrate rules_kotlin from KSP1 (compiler plugin-based) to KSP2 (standalone). This is a significant architectural change since KSP2 is no longer a Kotlin compiler
plugin but a standalone tool with its own entry point.

  ---
Key Differences: KSP1 vs KSP2

Architectural Changes

| Aspect               | KSP1 (Current)                                                       | KSP2 (Target)                                         |
  |----------------------|----------------------------------------------------------------------|-------------------------------------------------------|
| Execution Model      | Compiler plugin loaded via -Xplugin                                  | Standalone tool with KSPJvmMain entry point           |
| Integration Point    | Runs within kotlinc compiler                                         | Runs as separate process before/alongside compilation |
| JARs Used            | symbol-processing-api.jar + symbol-processing-cmdline.jar as plugins | symbol-processing-cmdline.jar with main class         |
| Arguments            | Plugin options via -P plugin:id:key=value                            | Direct command-line arguments                         |
| Kotlin Version       | Currently downleveled to 1.9 for compatibility                       | Supports Kotlin 2.0+ natively                         |
| withCompilation flag | Currently set to false (separate compilation)                        | Not applicable (always separate)                      |

Benefits of KSP2

- Better alignment with K2 compiler APIs (same as IntelliJ, Lint)
- Native Kotlin 2.0+ support (no version downleveling needed)
- Improved error type resolution (Map<String, ErrorType> vs just ErrorType)
- Potentially better performance and memory usage
- Required for Kotlin 2.2+ support

  ---
Current KSP1 Implementation Analysis

Invocation Flow (Current State)

kt_jvm_library(srcs, plugins=[kt_ksp_plugin])
↓
Starlark: compile.bzl:833 _run_ksp_builder_actions()
↓
Create KotlinBuilder action with:
- kspSymbolProcessingApi as -Xplugin
- kspSymbolProcessingCommandLine as -Xplugin
- Plugin options as -P plugin:com.google.devtools.ksp.symbol-processing:key=value
↓
KotlinBuilder: runKspPlugin() in CompilationTask.kt:309
↓
Invoke kotlinc with KSP plugins loaded
↓
KSP runs within kotlinc process, generates sources/classes
↓
Collect outputs: generated_ksp_src.jar, generated_ksp_classes.jar

Key Files Involved

1. Repository Setup: src/main/starlark/core/repositories/ksp.bzl
   - Downloads 3 JARs: symbol-processing, symbol-processing-api, symbol-processing-cmdline
2. Kotlin Builder: src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/CompilationTask.kt:202-349
   - kspArgs(): Builds plugin arguments
   - runKspPlugin(): Executes KSP as compiler plugin
   - kspKotlinToolchainVersion(): Downlevels Kotlin 2.0+ to 1.9
3. Toolchain Setup: src/main/kotlin/io/bazel/kotlin/builder/toolchain/KotlinToolchain.kt:176-228
   - Registers two KSP plugins (api + cmdline) as CompilerPlugin objects
   - Preloads both JARs into classloader
4. Starlark Rules: kotlin/internal/jvm/compile.bzl:833+
   - Creates Bazel actions for KSP execution

Current Plugin Options Passed to KSP1

From CompilationTask.kt:208-237:
- apclasspath - Processor JARs
- projectBaseDir - Incremental data directory
- incremental - Always false (Bazel handles incrementality)
- classOutputDir - Generated classes output
- javaOutputDir - Generated Java sources
- kotlinOutputDir - Generated Kotlin sources
- resourceOutputDir - META-INF resources
- kspOutputDir - KSP internal output
- cachesDir - Cache directory (unused since incremental=false)
- withCompilation - Always false (separate compilation step)
- returnOkOnError - Always false (fail on errors)
- allWarningsAsErrors - Always false

  ---
Migration Plan

Phase 1: Infrastructure Changes

1.1 Update Repository Rule

File: src/main/starlark/core/repositories/ksp.bzl

Changes:
- Update to download KSP 2.x artifacts (version 2.1.20-2.0.0 or newer)
- Potentially simplify to only download necessary JARs for standalone mode
- May only need symbol-processing-cmdline.jar + dependencies

1.2 Update KotlinToolchain

File: src/main/kotlin/io/bazel/kotlin/builder/toolchain/KotlinToolchain.kt

Changes:
- Remove kspSymbolProcessingApi and kspSymbolProcessingCommandLine from CompilerPlugin list
- Add new field for KSP2 standalone tool (e.g., kspStandaloneTool: File)
- Remove KSP JARs from preloaded plugins (lines 193-194, 219-228)
- Keep JARs available for classpath but not as compiler plugins

1.3 Update InternalCompilerPlugins

File: src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/InternalCompilerPlugins.kt

Changes:
class InternalCompilerPlugins constructor(
val jvmAbiGen: KotlinToolchain.CompilerPlugin,
val skipCodeGen: KotlinToolchain.CompilerPlugin,
val kapt: KotlinToolchain.CompilerPlugin,
val jdeps: KotlinToolchain.CompilerPlugin,
// REMOVE:
// val kspSymbolProcessingApi: KotlinToolchain.CompilerPlugin,
// val kspSymbolProcessingCommandLine: KotlinToolchain.CompilerPlugin,

    // ADD:
    val kspStandaloneTool: File,  // Path to KSP2 entry point
)

  ---
Phase 2: KSP2 Invocation Implementation

2.1 Create KSP2 Invoker

New File: src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/Ksp2Invoker.kt

Create a new standalone KSP2 invocation class:

    class Ksp2Invoker {
    fun invoke(
        context: CompilationTaskContext,
        task: JvmCompilationTask,
        kspTool: File,
    ): Ksp2Result {
        val args = buildKsp2Args(task)
        val process = ProcessBuilder()
        .command("java", "-cp", buildClasspath(kspTool),
        "com.google.devtools.ksp.cmdline.KSPJvmMain",
        *args.toTypedArray())
        .directory(context.executionRoot.toFile())
        .start()

      // Handle output, errors, exit code
      // Return generated files
    }

    private fun buildKsp2Args(task: JvmCompilationTask): List<String> {
      // Map current plugin options to KSP2 CLI args
      return listOf(
        "-jvm-target", task.info.toolchainInfo.jvm.jvmTarget,
        "-module-name", task.info.moduleName,
        "-source-roots", task.inputs.kotlinSourcesList.joinToString(":"),
        "-project-base-dir", task.directories.temp,
        "-output-base-dir", task.directories.temp,
        "-caches-dir", task.directories.incrementalData,
        "-class-output-dir", task.directories.generatedClasses,
        "-kotlin-output-dir", task.directories.generatedSources,
        "-java-output-dir", task.directories.generatedJavaSources,
        "-language-version", task.info.toolchainInfo.common.languageVersion,
        "-api-version", task.info.toolchainInfo.common.apiVersion,
        "-libraries", task.inputs.classpathList.joinToString(":"),
        "-processor-options", buildProcessorOptions(task),
        task.inputs.processorpathsList.joinToString(" "),  // Processor JARs at end
      )
    }
}

2.2 Update CompilationTask.kt

File: src/main/kotlin/io/bazel/kotlin/builder/tasks/jvm/CompilationTask.kt

Changes:

1. Remove kspArgs() function (lines 202-245) - no longer needed
2. Replace runKspPlugin() function (lines 309-344):

    private fun JvmCompilationTask.runKsp2Standalone(
    context: CompilationTaskContext,
    plugins: InternalCompilerPlugins,
    ): JvmCompilationTask {
    return context.execute("KSP2 (${inputs.processorsList.joinToString(", ")})") {
    val invoker = Ksp2Invoker()
    val result = invoker.invoke(context, this, plugins.kspStandaloneTool)
    
          context.whenTracing {
        printLines("KSP2 output", result.outputLines)
      }

      return@let expandWithGeneratedSources()
    }
}

3. Remove kspKotlinToolchainVersion() function (lines 346-349) - no longer needed since KSP2 supports Kotlin 2.0+
4. Update runPlugins() function (line 250):
   internal fun JvmCompilationTask.runPlugins(
   context: CompilationTaskContext,
   plugins: InternalCompilerPlugins,
   compiler: KotlinToolchain.KotlincInvoker,
   ): JvmCompilationTask {
   // ... existing checks ...
   if (!outputs.generatedKspSrcJar.isNullOrEmpty()) {
   return runKsp2Standalone(context, plugins)  // CHANGED from runKspPlugin
   } else if (!outputs.generatedClassJar.isNullOrEmpty()) {
   return runKaptPlugin(context, plugins, compiler)
   }
   // ...
   }

  ---
Phase 3: Starlark Layer Updates

3.1 Update Compilation Action

File: kotlin/internal/jvm/compile.bzl

Changes (around line 833 in _run_ksp_builder_actions):
- Remove KSP plugin arguments from action args
- Ensure KSP2 standalone tool is passed via toolchain instead
- Output handling should remain the same (generated jars are still created)

3.2 Update Plugin Info Providers

File: src/main/starlark/core/plugin/providers.bzl

Review needed - May not require changes if processor configuration stays same at Starlark level

  ---
Phase 4: Testing & Validation

4.1 Update Unit Tests

File: src/test/kotlin/io/bazel/kotlin/KotlinJvmKspAssertionTest.kt

Changes:
- Update test expectations for new invocation method
- Verify generated sources/classes are still correct
- Test with multiple processors
- Validate META-INF file handling still works

4.2 Update Integration Tests

Files: Test data in src/test/data/jvm/ksp/

Changes:
- Update expected outputs if needed
- Add tests for KSP2-specific features

4.3 Update Examples

Files: examples/ksp/

Changes:
- Verify all examples (Dagger, Moshi, AutoService) still work
- Update documentation if API changes

  ---
Phase 5: Backwards Compatibility & Migration Path

5.1 Consider Feature Flag Approach

Add a toolchain option to choose between KSP1 and KSP2:

kt_toolchain_config(
ksp_version = "2",  # or "1" for backwards compat
)

This allows gradual migration and testing.

5.2 Version Detection

Auto-detect KSP version from downloaded artifacts and choose invocation path accordingly.

5.3 Deprecation Timeline

- Phase 1: Support both KSP1 and KSP2 (default KSP2)
- Phase 2: Deprecate KSP1, warn users
- Phase 3: Remove KSP1 support entirely

  ---
Implementation Checklist

Critical Path Items

- Remove Kotlin version downleveling (kspKotlinToolchainVersion() in CompilationTask.kt:346-349)
- Create KSP2 standalone invoker (new Ksp2Invoker.kt)
- Update toolchain to provide KSP2 tool (not as compiler plugin)
- Replace plugin-based invocation with standalone process execution
- Map KSP1 plugin options to KSP2 CLI args (see Phase 2.1)
- Test with Dagger (most complex - generates Java, has multiple processing rounds)
- Test with Moshi (simpler case - generates Kotlin)
- Verify META-INF handling (service files, proguard rules)

Supporting Changes

- Update repository rule to download KSP 2.x
- Remove KSP from preloaded compiler plugins
- Update InternalCompilerPlugins data class
- Update all unit tests
- Update integration tests
- Update examples and documentation
- Consider backwards compatibility approach

  ---
Risk Assessment

High Risk Areas

1. Classpath Management: KSP2 needs correct classpath including processor dependencies
2. Output Directory Structure: Ensure KSP2 outputs match current structure expectations
3. Error Handling: KSP2 may report errors differently than plugin-based KSP1
4. Dagger Compatibility: Dagger is the most complex processor, requires Java generation + multiple rounds

Testing Strategy

1. Start with simple processors (Moshi, AutoService)
2. Move to complex processors (Dagger)
3. Test multi-processor scenarios
4. Test with both Kotlin 2.0 and earlier versions
5. Validate incremental build behavior (even though KSP incremental is disabled)

  ---
Estimated Implementation Effort

- Phase 1 (Infrastructure): 1-2 days
- Phase 2 (KSP2 Invocation): 2-3 days
- Phase 3 (Starlark Updates): 1 day
- Phase 4 (Testing): 2-3 days
- Phase 5 (Compatibility): 1-2 days (if implemented)

Total: ~7-11 days of development + testing

  ---
References

- https://github.com/bazelbuild/rules_kotlin/issues/1275
- https://github.com/bazelbuild/rules_kotlin/pull/1394
- https://github.com/google/ksp/blob/main/docs/ksp2.md
- https://github.com/google/ksp/blob/main/docs/ksp2cmdline.md

  ---
Next Steps

1. Review this plan with the team
2. Prototype KSP2 invocation in isolation (Phase 2.1)
3. Test with simple processor (Moshi) to validate approach
4. Decide on backwards compatibility strategy
5. Implement full migration following phases above

/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.compiler

import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.CompilationService
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinLogger
import org.jetbrains.kotlin.buildtools.api.ProjectId
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader  // Top-level function
import java.io.File
import java.io.PrintStream
import java.net.URLClassLoader
import java.util.UUID

/**
 * Kotlin Build Tools API (BTAPI) compiler implementation.
 *
 * This replaces direct invocation of K2JVMCompiler and uses the official Kotlin Build Tools API
 * instead. The BTAPI provides a stable interface to the Kotlin compiler that shields build systems
 * from internal compiler changes (like the removal of trove4j in Kotlin 2.2).
 *
 * ## Classloader Isolation
 *
 * Following the official kotlin-maven-plugin pattern, this implementation:
 * 1. Creates an isolated URLClassLoader containing only kotlin-build-tools-impl.jar
 * 2. Uses SharedApiClassesClassLoader() as parent for proper API class sharing
 * 3. Loads CompilationService via ServiceLoader in this isolated environment
 *
 * The SharedApiClassesClassLoader ensures only org.jetbrains.kotlin.buildtools.api.* classes
 * are shared, with JDK classes as its parent. This provides maximum isolation between the
 * compiler implementation and user code, preventing classpath conflicts.
 *
 * ## Performance Optimization
 *
 * The CompilationService is cached as a lazy property to avoid repeated classloader creation
 * and service loading overhead across multiple compilations in persistent worker scenarios.
 *
 * @param kotlinCompilerJar Path to the kotlin-compiler jar file
 * @param buildToolsImplJar Path to the kotlin-build-tools-impl jar file
 * @see <a href="https://github.com/JetBrains/kotlin/tree/master/compiler/build-tools/kotlin-build-tools-api">BTAPI Documentation</a>
 * @see org.jetbrains.kotlin.maven.K2JVMCompileMojo#getCompilationService
 */
@Suppress("unused")
class BuildToolsAPICompiler(
  private val kotlinCompilerJar: File,
  private val buildToolsImplJar: File,
) {
  /**
   * Cached CompilationService instance to avoid repeated classloader creation.
   * Initialized lazily on first use for optimal startup performance.
   */
  @OptIn(ExperimentalBuildToolsApi::class)
  private val compilationService: CompilationService by lazy {
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")

    // Create isolated classloader following the official Maven plugin pattern:
    // URLClassLoader with kotlin-build-tools-impl.jar + kotlin-compiler.jar (which includes stdlib)
    // Parent is SharedApiClassesClassLoader for proper API class sharing
    val urls =
      arrayOf(
        buildToolsImplJar.toURI().toURL(),
        kotlinCompilerJar.toURI().toURL(),
      )
    val btapiClassLoader = URLClassLoader(urls, SharedApiClassesClassLoader())
    CompilationService.loadImplementation(btapiClassLoader)
  }
  /**
   * Get the Kotlin compiler version from the Build Tools API.
   *
   * This is useful for debugging, version checks, and compatibility verification.
   * The version is retrieved from the compiler's metadata and follows Kotlin's
   * versioning scheme (e.g., "2.1.0", "2.2.0-Beta1").
   *
   * @return Version string of the Kotlin compiler (e.g., "2.1.0" or "2.2.0-Beta1")
   */
  @OptIn(ExperimentalBuildToolsApi::class)
  fun getCompilerVersion(): String = compilationService.getCompilerVersion()

  /**
   * Compile with the signature expected by CompilationTaskContext.executeCompilerTask.
   *
   * @param args Compiler arguments
   * @param out Output stream for compiler messages
   * @return Exit code (0 for success, non-zero for failure)
   */
  fun compile(
    args: Array<String>,
    out: PrintStream,
  ): Int {
    val result = exec(out, *args)
    // Map BTAPI CompilationResult to exit codes matching K2JVMCompiler convention
    return when (result) {
      CompilationResult.COMPILATION_SUCCESS -> 0
      CompilationResult.COMPILATION_ERROR -> 1
      CompilationResult.COMPILATION_OOM_ERROR -> 3
      CompilationResult.COMPILER_INTERNAL_ERROR -> 4
    }
  }

  @OptIn(ExperimentalBuildToolsApi::class)
  fun exec(
    errStream: PrintStream,
    vararg args: String,
  ): CompilationResult {
    // Use the cached compilationService for optimal performance

    // Parse arguments to extract source files and module name for better integration
    val parsedArgs = parseArguments(args.toList())

    // Create stable ProjectId based on module name (instead of random UUID)
    // This enables proper resource management and future incremental compilation support
    val projectId =
      ProjectId.ProjectUUID(
        UUID.nameUUIDFromBytes(parsedArgs.moduleName.toByteArray()),
      )

    // Use in-process execution strategy (not daemon) for hermetic builds and RBE compatibility
    val executionConfig =
      compilationService
        .makeCompilerExecutionStrategyConfiguration()
        .useInProcessStrategy()

    // Configure JVM compilation with custom logger for better diagnostics
    val compilationConfig =
      compilationService
        .makeJvmCompilationConfiguration()
        .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))

    return try {
      // Sources are already in args, so pass empty list to avoid duplication
      // BTAPI will extract sources from the arguments
      compilationService.compileJvm(
        projectId,
        executionConfig,
        compilationConfig,
        emptyList(),
        args.toList(),
      )
    } finally {
      // Clean up resources and caches for this project compilation
      // Wrap in try-catch since cleanup failures shouldn't fail the build
      try {
        compilationService.finishProjectCompilation(projectId)
      } catch (e: Throwable) {
        // Log but don't fail - cleanup errors are not critical
        System.err.println("Warning: Error during finishProjectCompilation cleanup: ${e.message}")
      }
    }
  }

  /**
   * Parsed compilation arguments containing sources and module metadata.
   */
  private data class ParsedArguments(
    val sources: List<File>,
    val moduleName: String,
  )

  /**
   * Parse kotlinc arguments to extract source files and module name.
   *
   * Source files are identified by .kt or .java extensions.
   * Module name is extracted from the -module-name flag.
   */
  private fun parseArguments(args: List<String>): ParsedArguments {
    val sources = mutableListOf<File>()
    var moduleName = "unknown-module"

    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg == "-module-name" && i + 1 < args.size -> {
          moduleName = args[i + 1]
          i += 2
        }
        arg.endsWith(".kt") || arg.endsWith(".java") -> {
          sources.add(File(arg))
          i++
        }
        else -> {
          i++
        }
      }
    }

    return ParsedArguments(sources, moduleName)
  }

  /**
   * Custom KotlinLogger implementation for Bazel integration.
   *
   * Logs compilation messages directly to the error stream without prefixes,
   * matching the behavior of the old K2JVMCompiler direct invocation.
   * Debug logging is enabled via the KOTLIN_BUILD_TOOLS_API_DEBUG environment variable.
   */
  private class BazelKotlinLogger(
    private val errStream: PrintStream,
    private val moduleName: String,
  ) : KotlinLogger {
    override val isDebugEnabled: Boolean =
      System.getenv("KOTLIN_BUILD_TOOLS_API_DEBUG")?.toBoolean() ?: false

    override fun error(
      msg: String,
      throwable: Throwable?,
    ) {
      errStream.println(msg)
      throwable?.printStackTrace(errStream)
    }

    override fun warn(
      msg: String,
      throwable: Throwable?,
    ) {
      errStream.println(msg)
      throwable?.printStackTrace(errStream)
    }

    override fun info(msg: String) {
      if (isDebugEnabled) {
        errStream.println(msg)
      }
    }

    override fun debug(msg: String) {
      if (isDebugEnabled) {
        errStream.println(msg)
      }
    }

    override fun lifecycle(msg: String) {
      errStream.println(msg)
    }
  }
}

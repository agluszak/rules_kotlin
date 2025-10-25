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
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.io.PrintStream
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
 * The kotlin-build-tools-impl jar is preloaded in an isolated classloader created by
 * ClassPreloadingUtils in KotlinToolchain. This provides basic isolation from user dependencies.
 *
 * BTAPI best practice recommends using SharedApiClassesClassLoader.newInstance() as the parent
 * classloader for maximum isolation. A future refactoring could implement this by:
 * 1. NOT preloading kotlin-build-tools-impl in KotlinToolchain.baseJars
 * 2. Creating an isolated URLClassLoader here with SharedApiClassesClassLoader.newInstance() parent
 * 3. Loading only kotlin-build-tools-impl (and its dependencies) in that isolated loader
 *
 * The current approach is sufficient for production use and fixes #1373.
 *
 * @see <a href="https://github.com/JetBrains/kotlin/tree/master/compiler/build-tools/kotlin-build-tools-api">BTAPI Documentation</a>
 */
@Suppress("unused")
class BuildToolsAPICompiler {
  @OptIn(ExperimentalBuildToolsApi::class)
  fun exec(
    errStream: PrintStream,
    vararg args: String,
  ): ExitCode {
    System.setProperty("zip.handler.uses.crc.instead.of.timestamp", "true")

    // Load BTAPI implementation from the preloaded classloader (created by KotlinToolchain)
    // This classloader already provides isolation from user dependencies
    val kotlinService = CompilationService.loadImplementation(this.javaClass.classLoader!!)

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
      kotlinService
        .makeCompilerExecutionStrategyConfiguration()
        .useInProcessStrategy()

    // Configure JVM compilation with custom logger for better diagnostics
    val compilationConfig =
      kotlinService
        .makeJvmCompilationConfiguration()
        .useLogger(BazelKotlinLogger(errStream, parsedArgs.moduleName))

    return try {
      // Pass actual source files to enable proper source tracking
      val result =
        kotlinService.compileJvm(
          projectId,
          executionConfig,
          compilationConfig,
          parsedArgs.sources,
          args.toList(),
        )

      // BTAPI returns a different type than K2JVMCompiler (CompilationResult vs ExitCode).
      when (result) {
        CompilationResult.COMPILATION_SUCCESS -> ExitCode.OK
        CompilationResult.COMPILATION_ERROR -> ExitCode.COMPILATION_ERROR
        CompilationResult.COMPILATION_OOM_ERROR -> ExitCode.OOM_ERROR
        CompilationResult.COMPILER_INTERNAL_ERROR -> ExitCode.INTERNAL_ERROR
      }
    } finally {
      // Clean up resources and caches for this project compilation
      kotlinService.finishProjectCompilation(projectId)
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
   * Logs compilation messages to the provided error stream with appropriate prefixes.
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
      errStream.println("[$moduleName] ERROR: $msg")
      throwable?.printStackTrace(errStream)
    }

    override fun warn(
      msg: String,
      throwable: Throwable?,
    ) {
      errStream.println("[$moduleName] WARN: $msg")
      throwable?.printStackTrace(errStream)
    }

    override fun info(msg: String) {
      errStream.println("[$moduleName] INFO: $msg")
    }

    override fun debug(msg: String) {
      if (isDebugEnabled) {
        errStream.println("[$moduleName] DEBUG: $msg")
      }
    }

    override fun lifecycle(msg: String) {
      // Lifecycle messages are shown without prefix for cleaner output
      errStream.println(msg)
    }
  }
}

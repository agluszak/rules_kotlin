/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.toolchain.BtapiRuntimeSpec
import io.bazel.kotlin.builder.toolchain.BtapiToolchainsCache
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.model.JvmCompilationTask
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Due to an inconsistency in the handling of -Xfriends-path, jvm uses a comma (property list
 * separator)
 */
const val X_FRIENDS_PATH_SEPARATOR = ","

@OptIn(ExperimentalBuildToolsApi::class)
class KotlinJvmTaskExecutor private constructor(
  private val compilerBuilder: KotlinToolchain.KotlincInvokerBuilder?,
  private val legacyPlugins: InternalCompilerPlugins?,
  private val defaultRuntimeSpec: BtapiRuntimeSpec?,
  private val defaultBtapiPlugins: InternalCompilerPlugins?,
  private val btapiToolchainsCache: BtapiToolchainsCache,
) : AutoCloseable {
  private data class LegacyRuntime(
    val compilerBuilder: KotlinToolchain.KotlincInvokerBuilder,
    val plugins: InternalCompilerPlugins,
  )

  private data class LegacyRuntimeInputKey(
    val runtimeSpec: BtapiRuntimeSpec,
    val jvmAbiGenJar: String,
    val skipCodeGenJar: String,
    val kaptJar: String,
    val jdepsJar: String,
  )

  constructor(
    compilerBuilder: KotlinToolchain.KotlincInvokerBuilder,
    plugins: InternalCompilerPlugins,
    btapiToolchainsCache: BtapiToolchainsCache = BtapiToolchainsCache(),
  ) : this(
    compilerBuilder = compilerBuilder,
    legacyPlugins = plugins,
    defaultRuntimeSpec = null,
    defaultBtapiPlugins = null,
    btapiToolchainsCache = btapiToolchainsCache,
  )

  @JvmOverloads
  constructor(
    defaultRuntimeSpec: BtapiRuntimeSpec? = null,
    defaultPlugins: InternalCompilerPlugins? = null,
    btapiToolchainsCache: BtapiToolchainsCache = BtapiToolchainsCache(),
  ) : this(
    compilerBuilder = null,
    legacyPlugins = null,
    defaultRuntimeSpec = defaultRuntimeSpec,
    defaultBtapiPlugins = defaultPlugins,
    btapiToolchainsCache = btapiToolchainsCache,
  )

  private val btapiCompilers = ConcurrentHashMap<BtapiRuntimeSpec, BtapiCompiler>()
  private val legacyCompilerBuilders = ConcurrentHashMap<LegacyRuntimeInputKey, KotlinToolchain.KotlincInvokerBuilder>()
  private val defaultLegacyRuntime: LegacyRuntime by lazy {
    val toolchain = KotlinToolchain.createToolchain()
    LegacyRuntime(
      compilerBuilder = KotlinToolchain.KotlincInvokerBuilder(toolchain),
      plugins =
        InternalCompilerPlugins(
          toolchain.jvmAbiGen,
          toolchain.skipCodeGen,
          toolchain.kapt3Plugin,
          toolchain.jdepsGen,
        ),
    )
  }

  override fun close() {
    btapiCompilers.values.forEach { it.close() }
    btapiCompilers.clear()
  }

  fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
  ) {
    if (task.info.buildToolsApi) {
      executeBtapi(
        context = context,
        task = task,
        runtimeSpec =
          defaultRuntimeSpec
            ?: error(
              "Btapi runtime spec must be provided either in KotlinJvmTaskExecutor constructor or per execute(...) call.",
            ),
        plugins =
          defaultBtapiPlugins
            ?: legacyPlugins
            ?: error(
              "Internal compiler plugins must be provided either in KotlinJvmTaskExecutor constructor or per execute(...) call.",
            ),
      )
    } else {
      executeLegacy(
        context = context,
        task = task,
        plugins = legacyPlugins,
        runtimeSpec = defaultRuntimeSpec,
      )
    }
  }

  fun execute(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtimeSpec: BtapiRuntimeSpec,
    plugins: InternalCompilerPlugins,
  ) {
    if (task.info.buildToolsApi) {
      executeBtapi(context, task, runtimeSpec, plugins)
    } else {
      executeLegacy(context, task, plugins, runtimeSpec)
    }
  }

  private fun executeLegacy(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    plugins: InternalCompilerPlugins?,
    runtimeSpec: BtapiRuntimeSpec?,
  ) {
    val resolvedPlugins = resolveLegacyPlugins(plugins)
    val compiler =
      resolveLegacyCompilerBuilder(runtimeSpec, resolvedPlugins).build(false)

    val preprocessedTask =
      task
        .preProcessingSteps(context)
        .runPlugins(context, resolvedPlugins, compiler)

    context.execute("compile classes") {
      preprocessedTask.apply {
        listOf(
          runCatching {
            context.execute("kotlinc") {
              if (compileKotlin) {
                compileKotlin(
                  context,
                  compiler,
                  args =
                    baseArgs()
                      .given(outputs.jdeps)
                      .notEmpty {
                        plugin(resolvedPlugins.jdeps.asLegacyPlugin()) {
                          flag("output", outputs.jdeps)
                          flag("target_label", info.label)
                          inputs.directDependenciesList.forEach {
                            flag("direct_dependencies", it)
                          }
                          inputs.classpathList.forEach {
                            flag("full_classpath", it)
                          }
                          flag("strict_kotlin_deps", info.strictKotlinDeps)
                        }
                      }.given(outputs.jar)
                      .notEmpty {
                        append(codeGenArgs())
                      }.given(outputs.abijar)
                      .notEmpty {
                        plugin(resolvedPlugins.jvmAbiGen.asLegacyPlugin()) {
                          flag("outputDir", directories.abiClasses)
                          if (info.treatInternalAsPrivateInAbiJar) {
                            flag("treatInternalAsPrivate", "true")
                          }
                          if (info.removePrivateClassesInAbiJar) {
                            flag("removePrivateClasses", "true")
                          }
                          if (info.removeDebugInfo) {
                            flag("removeDebugInfo", "true")
                          }
                        }
                        given(outputs.jar).empty {
                          plugin(resolvedPlugins.skipCodeGen.asLegacyPlugin())
                        }
                      },
                  printOnFail = false,
                )
              } else {
                emptyList()
              }
            }
          },
        ).map {
          (it.getOrNull() ?: emptyList()) to it.exceptionOrNull()
        }.map {
          when (it.second) {
            is CompilationStatusException ->
              (it.second as CompilationStatusException).lines + it.first to it.second
            else -> it
          }
        }.fold(Pair<List<String>, Throwable?>(emptyList(), null)) { acc, result ->
          acc.first + result.first to combine(acc.second, result.second)
        }.apply {
          first.apply(context::printCompilerOutput)
          second?.let {
            throw it
          }
        }

        emitOutputs(context)
      }
    }
  }

  private fun executeBtapi(
    context: CompilationTaskContext,
    task: JvmCompilationTask,
    runtimeSpec: BtapiRuntimeSpec,
    plugins: InternalCompilerPlugins,
  ) {
    val btapiCompiler =
      btapiCompilers.computeIfAbsent(runtimeSpec) {
        BtapiCompiler(btapiToolchainsCache.get(it))
      }

    val preprocessedTask =
      task
        .preProcessingSteps(context)
        .runPluginsBtapi(context, plugins, btapiCompiler)

    context.execute("compile classes") {
      preprocessedTask.apply {
        context.execute("kotlinc") {
          if (compileKotlin && inputs.kotlinSourcesList.isNotEmpty()) {
            when (btapiCompiler.compile(this, plugins, context.outputStream())) {
              CompilationResult.COMPILATION_SUCCESS -> {
                // no-op
              }
              CompilationResult.COMPILATION_ERROR ->
                throw CompilationStatusException("Compilation failed", 1)
              CompilationResult.COMPILATION_OOM_ERROR ->
                throw CompilationStatusException("Compilation failed with OOM", 3)
              CompilationResult.COMPILER_INTERNAL_ERROR ->
                throw CompilationStatusException("Compiler internal error", 2)
            }
          }
        }

        context.execute("ensure jdeps") { ensureJdepsExists() }
        emitOutputs(context)
      }
    }
  }

  private fun JvmCompilationTask.emitOutputs(context: CompilationTaskContext) {
    if (outputs.jar.isNotEmpty()) {
      if (instrumentCoverage) {
        context.execute("create instrumented jar", ::createCoverageInstrumentedJar)
      } else {
        context.execute("create jar", ::createOutputJar)
      }
    }
    if (outputs.abijar.isNotEmpty()) {
      context.execute("create abi jar", ::createAbiJar)
    }
    if (outputs.generatedJavaSrcJar.isNotEmpty()) {
      context.execute("creating KAPT generated Java source jar", ::createGeneratedJavaSrcJar)
    }
    if (outputs.generatedJavaStubJar.isNotEmpty()) {
      context.execute("creating KAPT generated Kotlin stubs jar", ::createGeneratedStubJar)
    }
    if (outputs.generatedClassJar.isNotEmpty()) {
      context.execute("creating KAPT generated stub class jar", ::createGeneratedClassJar)
    }
    if (outputs.generatedKspSrcJar.isNotEmpty()) {
      context.execute("creating KSP generated src jar", ::createGeneratedKspKotlinSrcJar)
    }
    if (outputs.generatedKspClassesJar.isNotEmpty()) {
      context.execute("creating KSP generated classes jar", ::createdGeneratedKspClassesJar)
    }
  }

  private fun combine(
    one: Throwable?,
    two: Throwable?,
  ): Throwable? {
    return when {
      one != null && two != null -> {
        one.addSuppressed(two)
        one
      }
      one != null -> one
      else -> two
    }
  }

  private fun resolveLegacyCompilerBuilder(
    runtimeSpec: BtapiRuntimeSpec?,
    plugins: InternalCompilerPlugins,
  ): KotlinToolchain.KotlincInvokerBuilder {
    compilerBuilder?.let { return it }
    if (runtimeSpec != null) {
      return legacyCompilerBuilders.computeIfAbsent(
        LegacyRuntimeInputKey(
          runtimeSpec = runtimeSpec,
          jvmAbiGenJar = plugins.jvmAbiGen.jarPath,
          skipCodeGenJar = plugins.skipCodeGen.jarPath,
          kaptJar = plugins.kapt.jarPath,
          jdepsJar = plugins.jdeps.jarPath,
        ),
      ) { key ->
        createLegacyCompilerBuilderFromRuntime(key.runtimeSpec, plugins)
      }
    }
    return defaultLegacyRuntime.compilerBuilder
  }

  private fun resolveLegacyPlugins(plugins: InternalCompilerPlugins?): InternalCompilerPlugins =
    plugins ?: legacyPlugins ?: defaultLegacyRuntime.plugins

  private fun createLegacyCompilerBuilderFromRuntime(
    runtimeSpec: BtapiRuntimeSpec,
    plugins: InternalCompilerPlugins,
  ): KotlinToolchain.KotlincInvokerBuilder {
    val runtimeJars = runtimeSpec.classpath.map { it.toFile() }

    val kotlincJar =
      pickRuntimeJar(
        runtimeJars,
        "kotlin-compiler-embeddable",
        "kotlin-compiler-",
      )
    val buildToolsImplJar = pickRuntimeJar(runtimeJars, "kotlin-build-tools-impl")

    val toolchain =
      KotlinToolchain.createToolchain(
        kotlinc = kotlincJar,
        buildTools = buildToolsImplJar,
        compiler = compilerBridgeJar(),
        jvmAbiGenFile = File(plugins.jvmAbiGen.jarPath),
        skipCodeGenFile = File(plugins.skipCodeGen.jarPath),
        jdepsGenFile = File(plugins.jdeps.jarPath),
        kaptFile = File(plugins.kapt.jarPath),
        extraClasspath = runtimeJars,
      )
    return KotlinToolchain.KotlincInvokerBuilder(toolchain)
  }

  private fun pickRuntimeJar(
    jars: List<File>,
    vararg tokens: String,
  ): File =
    jars.firstOrNull { jar -> tokens.any { token -> jar.name.contains(token) } }
      ?: error(
        "Unable to locate runtime jar with any token ${tokens.toList()} in BTAPI runtime classpath: " +
          jars.joinToString { it.name },
      )

  private fun compilerBridgeJar(): File {
    val compilerClass = Class.forName("io.bazel.kotlin.compiler.BazelK2JVMCompiler")
    return File(compilerClass.protectionDomain.codeSource.location.toURI())
  }

  private fun InternalCompilerPlugin.asLegacyPlugin(): KotlinToolchain.CompilerPlugin =
    KotlinToolchain.CompilerPlugin(jarPath = jarPath, id = id)
}

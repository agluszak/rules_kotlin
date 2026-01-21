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

import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.emptyJdeps
import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.writeJdeps
import io.bazel.kotlin.builder.toolchain.BtapiToolchainFactory
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.toolchain.KotlinToolchain
import io.bazel.kotlin.model.JvmCompilationTask
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Due to an inconsistency in the handling of -Xfriends-path, jvm uses a comma (property list
 * separator)
 */
const val X_FRIENDS_PATH_SEPARATOR = ","

@Singleton
class KotlinJvmTaskExecutor
  @Inject
  internal constructor(
    private val compilerBuilder: KotlinToolchain.KotlincInvokerBuilder,
    private val plugins: InternalCompilerPlugins,
    private val toolchain: KotlinToolchain,
  ) {
    private fun combine(
      one: Throwable?,
      two: Throwable?,
    ): Throwable? {
      return when {
        one != null && two != null -> {
          one.addSuppressed(two)
          return one
        }
        one != null -> one
        else -> two
      }
    }

    fun execute(
      context: CompilationTaskContext,
      task: JvmCompilationTask,
    ) {
      val compiler = compilerBuilder.build()

      val preprocessedTask =
        task
          .preProcessingSteps(context)
          .runPlugins(context, plugins, compiler)

      context.execute("compile classes") {
        preprocessedTask.apply {
          listOf(
            runCatching {
              context.execute("kotlinc") {
                if (compileKotlin) {
                  val outputStream = ByteArrayOutputStream()
                  val ps = PrintStream(outputStream)

                  val pluginJars = mutableListOf<File>()
                  pluginJars.add(File(plugins.jdeps.jarPath))
                  pluginJars.add(File(plugins.jvmAbiGen.jarPath))
                  if (preprocessedTask.outputs.jar.isEmpty()) {
                    pluginJars.add(File(plugins.skipCodeGen.jarPath))
                  }
                  preprocessedTask.inputs.compilerPluginClasspathList.forEach { pluginJars.add(File(it)) }

                  val factory =
                    BtapiToolchainFactory(
                      toolchain.buildToolsImplJar,
                      toolchain.compilerJar,
                      pluginJars,
                    )
                  val btapiCompiler = BtapiCompiler(factory.createToolchains(), ps)
                  val result = btapiCompiler.compile(preprocessedTask, plugins)

                  val outputLines =
                    ByteArrayInputStream(outputStream.toByteArray())
                      .bufferedReader()
                      .readLines()

                  if (result != CompilationResult.COMPILATION_SUCCESS) {
                    throw CompilationStatusException("compile phase failed", result.ordinal, outputLines)
                  }
                  outputLines
                } else {
                  writeJdeps(outputs.jdeps, emptyJdeps(info.label))
                  emptyList()
                }
              }
            },
          ).map {
            (it.getOrNull() ?: emptyList()) to it.exceptionOrNull()
          }.map {
            when (it.second) {
              // TODO(issue/296): remove when the CompilationStatusException is unified.
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

          // Ensure jdeps exists (restore from IC cache if compilation was skipped)
          context.execute("ensure jdeps") { ensureJdepsExists() }

          if (outputs.jar.isNotEmpty()) {
            if (instrumentCoverage) {
              context.execute("create instrumented jar", ::createCoverageInstrumentedJar)
            } else {
              context.execute("create jar", ::createOutputJar)
            }
            // Generate classpath snapshot for incremental compilation (stored in IC directory, not Bazel output)
            if (context.info.incrementalCompilation) {
              context.execute("create classpath snapshot") {
                createOutputClasspathSnapshot(compilerBuilder.buildSnapshotInvoker())
              }
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
      }
    }
  }

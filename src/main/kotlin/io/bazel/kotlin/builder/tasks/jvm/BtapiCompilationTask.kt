/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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
 */
package io.bazel.kotlin.builder.tasks.jvm

import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.emptyJdeps
import io.bazel.kotlin.builder.tasks.jvm.JDepsGenerator.writeJdeps
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.model.JvmCompilationTask
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import java.nio.file.Paths
import kotlin.io.path.exists

@OptIn(ExperimentalBuildToolsApi::class)
internal fun JvmCompilationTask.runPluginsBtapi(
  context: CompilationTaskContext,
  plugins: InternalCompilerPlugins,
  btapiCompiler: BtapiCompiler,
): JvmCompilationTask {
  if (
    (
      inputs.processorsList.isEmpty() &&
        inputs.pluginsList.none {
          it.phasesList.contains(JvmCompilationTask.Inputs.PluginPhase.PLUGIN_PHASE_STUBS)
        }
    ) ||
    inputs.kotlinSourcesList.isEmpty()
  ) {
    return this
  }

  return context.execute("kapt (${inputs.processorsList.joinToString(", ")})") {
    when (
      btapiCompiler.compileKapt(
        task = this,
        plugins = plugins,
        aptMode = "stubsAndApt",
        verbose = context.whenTracing { true } == true,
        out = context.outputStream(),
      )
    ) {
      CompilationResult.COMPILATION_SUCCESS -> expandWithGeneratedSources()
      CompilationResult.COMPILATION_ERROR ->
        throw CompilationStatusException("KAPT failed", 1)
      CompilationResult.COMPILATION_OOM_ERROR ->
        throw CompilationStatusException("KAPT failed with OOM", 3)
      CompilationResult.COMPILER_INTERNAL_ERROR ->
        throw CompilationStatusException("KAPT compiler internal error", 2)
    }
  }
}

internal fun JvmCompilationTask.ensureJdepsExists() {
  if (outputs.jdeps.isEmpty()) {
    return
  }
  val jdepsPath = Paths.get(outputs.jdeps)
  if (!jdepsPath.exists()) {
    writeJdeps(outputs.jdeps, emptyJdeps(info.label))
  }
}

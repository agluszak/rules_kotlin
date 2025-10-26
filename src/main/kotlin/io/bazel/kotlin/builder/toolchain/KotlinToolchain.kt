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
package io.bazel.kotlin.builder.toolchain

import io.bazel.kotlin.builder.utils.BazelRunFiles
import io.bazel.kotlin.builder.utils.resolveVerified
import io.bazel.kotlin.builder.utils.verified
import io.bazel.kotlin.builder.utils.verifiedPath
import org.jetbrains.kotlin.preloading.ClassPreloadingUtils
import org.jetbrains.kotlin.preloading.Preloader
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths

class KotlinToolchain private constructor(
  private val baseJars: List<File>,
  val kotlinCompilerJar: File,
  val buildToolsImplJar: File,
  val kapt3Plugin: CompilerPlugin,
  val jvmAbiGen: CompilerPlugin,
  val skipCodeGen: CompilerPlugin,
  val jdepsGen: CompilerPlugin,
  val kspSymbolProcessingApi: CompilerPlugin,
  val kspSymbolProcessingCommandLine: CompilerPlugin,
) {
  companion object {
    private val JVM_ABI_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...jvm-abi-gen",
        ).toPath()
    }

    private val KAPT_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...kapt",
        ).toPath()
    }

    private val COMPILER by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...compiler",
        ).toPath()
    }

    private val SKIP_CODE_GEN_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...skip-code-gen",
        ).toPath()
    }

    private val JDEPS_GEN_PLUGIN by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin...jdeps-gen",
        ).toPath()
    }

    private val KOTLINC by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...kotlin-compiler",
        ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_API by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_google_ksp...symbol-processing-api",
        ).toPath()
    }

    private val KSP_SYMBOL_PROCESSING_CMDLINE by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_google_ksp...symbol-processing-cmdline",
        ).toPath()
    }

    private val KOTLIN_REFLECT by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@rules_kotlin..kotlin.compiler.kotlin-reflect",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_CORE_JVM by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-core-jvm",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_JSON by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-json",
        ).toPath()
    }

    private val KOTLINX_SERIALIZATION_JSON_JVM by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlinx...serialization-json-jvm",
        ).toPath()
    }

    private val BUILD_TOOLS_API_JAR by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...build-tools-api",
        ).toPath()
    }

    private val BUILD_TOOLS_IMPL_JAR by lazy {
      BazelRunFiles
        .resolveVerifiedFromProperty(
          "@com_github_jetbrains_kotlin...build-tools-impl",
        ).toPath()
    }

    private val JAVA_HOME by lazy {
      FileSystems
        .getDefault()
        .getPath(System.getProperty("java.home"))
        .let { path ->
          path.takeIf { !it.endsWith(Paths.get("jre")) } ?: path.parent
        }.verifiedPath()
    }

    private val isJdk9OrNewer = !System.getProperty("java.version").startsWith("1.")

    @JvmStatic
    fun createToolchain(): KotlinToolchain =
      createToolchain(
        JAVA_HOME,
        KOTLINC.verified().absoluteFile,
        COMPILER.verified().absoluteFile,
        BUILD_TOOLS_API_JAR.verified().absoluteFile,
        BUILD_TOOLS_IMPL_JAR.verified().absoluteFile,
        JVM_ABI_PLUGIN.verified().absoluteFile,
        SKIP_CODE_GEN_PLUGIN.verified().absoluteFile,
        JDEPS_GEN_PLUGIN.verified().absoluteFile,
        KAPT_PLUGIN.verified().absoluteFile,
        KSP_SYMBOL_PROCESSING_API.toFile(),
        KSP_SYMBOL_PROCESSING_CMDLINE.toFile(),
        KOTLINX_SERIALIZATION_CORE_JVM.toFile(),
        KOTLINX_SERIALIZATION_JSON.toFile(),
        KOTLINX_SERIALIZATION_JSON_JVM.toFile(),
      )

    @JvmStatic
    fun createToolchain(
      javaHome: Path,
      kotlinc: File,
      compiler: File,
      buildToolsApiJar: File,
      buildToolsImplJar: File,
      jvmAbiGenFile: File,
      skipCodeGenFile: File,
      jdepsGenFile: File,
      kaptFile: File,
      kspSymbolProcessingApi: File,
      kspSymbolProcessingCommandLine: File,
      kotlinxSerializationCoreJvm: File,
      kotlinxSerializationJson: File,
      kotlinxSerializationJsonJvm: File,
    ): KotlinToolchain =
      KotlinToolchain(
        listOf(
          kotlinc,
          compiler,
          // NOTE: buildToolsApiJar must be preloaded so SharedApiClassesClassLoader is available
          // kotlinc and buildToolsImplJar are NOT preloaded - BuildToolsAPICompiler loads them in isolated classloader
          buildToolsApiJar,
          // plugins *must* be preloaded. Not doing so causes class conflicts
          // (and a NoClassDef err) in the compiler extension interfaces.
          // This may cause issues in accepting user defined compiler plugins.
          jvmAbiGenFile,
          skipCodeGenFile,
          jdepsGenFile,
          kspSymbolProcessingApi,
          kspSymbolProcessingCommandLine,
          kotlinxSerializationCoreJvm,
          kotlinxSerializationJson,
          kotlinxSerializationJsonJvm,
        ),
        kotlinCompilerJar = kotlinc,
        buildToolsImplJar = buildToolsImplJar,
        jvmAbiGen =
          CompilerPlugin(
            jvmAbiGenFile.path,
            "org.jetbrains.kotlin.jvm.abi",
          ),
        skipCodeGen =
          CompilerPlugin(
            skipCodeGenFile.path,
            "io.bazel.kotlin.plugin.SkipCodeGen",
          ),
        jdepsGen =
          CompilerPlugin(
            jdepsGenFile.path,
            "io.bazel.kotlin.plugin.jdeps.JDepsGen",
          ),
        kapt3Plugin =
          CompilerPlugin(
            kaptFile.path,
            "org.jetbrains.kotlin.kapt3",
          ),
        kspSymbolProcessingApi =
          CompilerPlugin(
            kspSymbolProcessingApi.absolutePath,
            "com.google.devtools.ksp.symbol-processing",
          ),
        kspSymbolProcessingCommandLine =
          CompilerPlugin(
            kspSymbolProcessingCommandLine.absolutePath,
            "com.google.devtools.ksp.symbol-processing",
          ),
      )
  }

  private fun createClassLoader(
    javaHome: Path,
    baseJars: List<File>,
    classLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
  ): ClassLoader =
    runCatching {
      ClassPreloadingUtils.preloadClasses(
        mutableListOf<File>().also {
          it += baseJars
          if (!isJdk9OrNewer) {
            it += javaHome.resolveVerified("lib", "tools.jar")
          }
        },
        Preloader.DEFAULT_CLASS_NUMBER_ESTIMATE,
        classLoader,
        null,
      )
    }.onFailure {
      throw RuntimeException("$javaHome, $baseJars", it)
    }.getOrThrow()

  val classLoader by lazy {
    createClassLoader(
      JAVA_HOME,
      baseJars,
    )
  }

  /**
   * Get the Kotlin compiler version from the Build Tools API.
   *
   * This method creates a temporary BuildToolsAPICompiler instance to query the version.
   * The version is useful for:
   * - Debugging compilation issues
   * - Version compatibility checks in rules
   * - Logging and diagnostics
   *
   * @return Version string of the Kotlin compiler (e.g., "2.1.0" or "2.2.0-Beta1")
   */
  fun getCompilerVersion(): String {
    val compiler = io.bazel.kotlin.compiler.BuildToolsAPICompiler(kotlinCompilerJar, buildToolsImplJar)
    return compiler.getCompilerVersion()
  }

  fun toolchainWithReflect(kotlinReflect: File? = null): KotlinToolchain =
    KotlinToolchain(
      baseJars + listOf(kotlinReflect ?: KOTLIN_REFLECT.toFile()),
      kotlinCompilerJar,
      buildToolsImplJar,
      kapt3Plugin,
      jvmAbiGen,
      skipCodeGen,
      jdepsGen,
      kspSymbolProcessingApi,
      kspSymbolProcessingCommandLine,
    )

  data class CompilerPlugin(
    val jarPath: String,
    val id: String,
  )
}

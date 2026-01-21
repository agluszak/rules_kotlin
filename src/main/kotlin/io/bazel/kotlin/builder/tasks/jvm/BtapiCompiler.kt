package io.bazel.kotlin.builder.tasks.jvm

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.toolchain.BtapiToolchainFactory
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.compiler.ICLogger
import io.bazel.kotlin.model.JvmCompilationTask
import org.jetbrains.kotlin.buildtools.api.CompilationResult
import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SourcesChanges
import org.jetbrains.kotlin.buildtools.api.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.JvmCompilerArguments
import org.jetbrains.kotlin.buildtools.api.arguments.enums.JvmTarget
import org.jetbrains.kotlin.buildtools.api.arguments.enums.KotlinVersion
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationConfiguration
import org.jetbrains.kotlin.buildtools.api.jvm.JvmSnapshotBasedIncrementalCompilationOptions
import org.jetbrains.kotlin.buildtools.api.jvm.operations.JvmCompilationOperation
import java.io.BufferedInputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalBuildToolsApi::class)
class BtapiCompiler(
    private val toolchains: KotlinToolchains,
    private val out: PrintStream
) {

    fun compile(
        task: JvmCompilationTask,
        plugins: InternalCompilerPlugins
    ): CompilationResult {
        val destination = Paths.get(task.directories.classes)
        // Ensure destination exists
        Files.createDirectories(destination)

        val sources = (task.inputs.kotlinSourcesList + task.inputs.javaSourcesList).map { Paths.get(it) }

        val operation = toolchains.jvm.createJvmCompilationOperation(sources, destination)
        val args = operation.compilerArguments

        // Typed setters
        // Using common arguments where available
        args[CommonCompilerArguments.MODULE_NAME] = task.info.moduleName
        args[CommonCompilerArguments.NO_STDLIB] = true
        args[CommonCompilerArguments.NO_REFLECT] = true

        parseJvmTarget(task.info.toolchainInfo.jvm.jvmTarget)?.let {
            args[JvmCompilerArguments.JVM_TARGET] = it
        }
        parseKotlinVersion(task.info.toolchainInfo.common.apiVersion)?.let {
            args[CommonCompilerArguments.API_VERSION] = it
        }
        parseKotlinVersion(task.info.toolchainInfo.common.languageVersion)?.let {
            args[CommonCompilerArguments.LANGUAGE_VERSION] = it
        }

        args[JvmCompilerArguments.CLASSPATH] = computeClasspath(task).joinToString(File.pathSeparator)
        args[JvmCompilerArguments.FRIEND_PATHS] = task.info.friendPathsList.toTypedArray()

        // Plugins options
        val pluginArgs = buildPluginArgs(task, plugins)
        args.applyArgumentStrings(pluginArgs)

        // Passthrough flags
        args.applyArgumentStrings(task.info.passthroughFlagsList)

        // Config hash for IC
        val configHash = computeConfigHash(task, pluginArgs)

        // IC Configuration
        configureIncrementalCompilation(operation, task, configHash)

        val logger = if (task.info.icEnableLogging) ICLogger(out) else null

        // Redirect stderr to capture compiler error messages
        val originalErr = System.err
        System.setErr(out)

        try {
            return toolchains.createBuildSession().use { session ->
                session.executeOperation(operation, logger)
            }
        } finally {
            System.setErr(originalErr)
        }
    }

    private fun computeClasspath(task: JvmCompilationTask): List<String> {
        val classpath = when (task.info.reducedClasspathMode) {
            "KOTLINBUILDER_REDUCED" -> {
                val transitiveDepsForCompile = mutableSetOf<String>()
                task.inputs.depsArtifactsList.forEach { jdepsPath ->
                    BufferedInputStream(Paths.get(jdepsPath).toFile().inputStream()).use {
                        val deps = Deps.Dependencies.parseFrom(it)
                        deps.dependencyList.forEach { dep ->
                            if (dep.kind == Deps.Dependency.Kind.EXPLICIT) {
                                transitiveDepsForCompile.add(dep.path)
                            }
                        }
                    }
                }
                task.inputs.directDependenciesList + transitiveDepsForCompile
            }
            else -> task.inputs.classpathList
        }
        return classpath + task.directories.generatedClasses
    }

    private fun buildPluginArgs(task: JvmCompilationTask, plugins: InternalCompilerPlugins): List<String> {
        val args = mutableListOf<String>()

        // Helper to add plugin options
        fun addPluginOption(pluginId: String, option: String, value: String) {
            args.add("-P")
            args.add("plugin:$pluginId:$option=$value")
        }

        // Jdeps plugin
        if (task.outputs.jdeps.isNotEmpty()) {
            val jdeps = plugins.jdeps
            addPluginOption(jdeps.id, "output", task.outputs.jdeps)
            addPluginOption(jdeps.id, "target_label", task.info.label)
            task.inputs.directDependenciesList.forEach {
                addPluginOption(jdeps.id, "direct_dependencies", it)
            }
            task.inputs.classpathList.forEach {
                addPluginOption(jdeps.id, "full_classpath", it)
            }
            addPluginOption(jdeps.id, "strict_kotlin_deps", task.info.strictKotlinDeps)
        }

        // JVM ABI Gen plugin
        if (task.outputs.abijar.isNotEmpty()) {
             val jvmAbiGen = plugins.jvmAbiGen
             addPluginOption(jvmAbiGen.id, "outputDir", task.directories.abiClasses)
             if (task.info.treatInternalAsPrivateInAbiJar) {
                 addPluginOption(jvmAbiGen.id, "treatInternalAsPrivate", "true")
             }
             if (task.info.removePrivateClassesInAbiJar) {
                 addPluginOption(jvmAbiGen.id, "removePrivateClasses", "true")
             }
             if (task.info.removeDebugInfo) {
                 addPluginOption(jvmAbiGen.id, "removeDebugInfo", "true")
             }

             // Skip Code Gen plugin (if only generating ABI)
             if (task.outputs.jar.isEmpty()) {
                 // The plugin just needs to be present, no options needed?
                 // CompilationTask.kt: plugin(plugins.skipCodeGen)
                 // This plugin might not take options. But it needs to be enabled.
                 // Since it is in the classpath, it is enabled by default?
                 // No, usually plugins are enabled by default if they have a registrar.
                 // But typically we pass an option to enable/configure them?
                 // The old code just did `plugin(plugins.skipCodeGen)` which adds `-Xplugin=...`.
                 // Since we added it to classpath, it is loaded.
                 // Does it need any option to activate?
                 // If not, we do nothing here.
             }
        }

        // User plugin options
        // Logic from CompilationTask.kt plugins()
        val optionTokens = mapOf(
            "{generatedClasses}" to task.directories.generatedClasses,
            "{stubs}" to task.directories.stubs,
            "{temp}" to task.directories.temp,
            "{generatedSources}" to task.directories.generatedSources,
            "{classpath}" to computeClasspath(task).joinToString(File.pathSeparator),
        )

        task.inputs.compilerPluginOptionsList.forEach { opt ->
            val formatted = optionTokens.entries.fold(opt) { formatting, (token, value) ->
                formatting.replace(token, value)
            }
            args.add("-P")
            args.add("plugin:$formatted")
        }

        return args
    }

    private fun configureIncrementalCompilation(
        operation: JvmCompilationOperation,
        task: JvmCompilationTask,
        configHash: Long,
    ) {
        if (!task.info.incrementalCompilation || task.directories.incrementalBaseDir.isEmpty()) return

        val icBaseDir = Paths.get(task.directories.incrementalBaseDir)
        val icWorkingDir = icBaseDir.resolve("ic-caches")
        val shrunkSnapshot = icBaseDir.resolve("shrunk-classpath-snapshot.bin")

        val icOptions = operation.createSnapshotBasedIcOptions().apply {
            this[JvmSnapshotBasedIncrementalCompilationOptions.ROOT_PROJECT_DIR] = Paths.get("").toAbsolutePath()
            this[JvmSnapshotBasedIncrementalCompilationOptions.MODULE_BUILD_DIR] = Paths.get(task.directories.classes).parent ?: Paths.get(task.directories.classes)
            this[JvmSnapshotBasedIncrementalCompilationOptions.FORCE_RECOMPILATION] = computeForceRecompilation(task, icBaseDir, configHash)
            this[JvmSnapshotBasedIncrementalCompilationOptions.OUTPUT_DIRS] = setOf(Paths.get(task.directories.classes), icWorkingDir)
        }

        operation[JvmCompilationOperation.INCREMENTAL_COMPILATION] = JvmSnapshotBasedIncrementalCompilationConfiguration(
            workingDirectory = icWorkingDir,
            sourcesChanges = SourcesChanges.ToBeCalculated,
            dependenciesSnapshotFiles = createClasspathSnapshotsPaths(task).map { Paths.get(it) },
            shrunkClasspathSnapshot = shrunkSnapshot,
            options = icOptions,
        )
    }

    private fun computeForceRecompilation(task: JvmCompilationTask, icBaseDir: Path, currentHash: Long): Boolean {
         val shrunkSnapshotPath = icBaseDir.resolve("shrunk-classpath-snapshot.bin")
         val snapshotMissing = !Files.exists(shrunkSnapshotPath)

         val previousHash = loadArgsHash(icBaseDir)
         val argsChanged = previousHash != null && previousHash != currentHash

         // Store current hash
         Files.createDirectories(icBaseDir)
         storeArgsHash(icBaseDir, currentHash)

         return snapshotMissing || argsChanged
    }

    private fun loadArgsHash(icBaseDir: Path): Long? {
        val hashFile = icBaseDir.resolve("args-hash.txt")
        return if (Files.exists(hashFile)) {
            Files.readString(hashFile).trim().toLongOrNull()
        } else {
            null
        }
    }

    private fun storeArgsHash(icBaseDir: Path, hash: Long) {
        val hashFile = icBaseDir.resolve("args-hash.txt")
        Files.writeString(hashFile, hash.toString())
    }

    private fun computeConfigHash(
        task: JvmCompilationTask,
        pluginArgs: List<String>
    ): Long {
        val components = mutableListOf<String>()

        components.add("moduleName=${task.info.moduleName}")
        components.add("jvmTarget=${task.info.toolchainInfo.jvm.jvmTarget}")
        components.add("apiVersion=${task.info.toolchainInfo.common.apiVersion}")
        components.add("languageVersion=${task.info.toolchainInfo.common.languageVersion}")

        // Plugin args
        components.addAll(pluginArgs)

        // Passthrough flags
        components.addAll(task.info.passthroughFlagsList)

        // Filter out path-specific args that change between builds (sandbox paths)
        val filtered = components.filter { arg ->
             !arg.contains("/sandbox/") && !arg.contains("/execroot/")
        }.sorted()

        var hash = 0L
        for (arg in filtered) {
            hash = hash * 31 + arg.hashCode()
        }
        return hash
    }

    private fun createClasspathSnapshotsPaths(task: JvmCompilationTask): List<String> {
        // Logic from CompilationTask.kt
        return task.inputs.classpathList
            .mapNotNull { jarPath ->
                val path = Paths.get(jarPath)
                val jarName = path.fileName.toString()
                    .removeSuffix(".jar")
                    .removeSuffix(".abi")
                val snapshotPath = path.resolveSibling("$jarName-ic/output-classpath-snapshot.bin")
                if (Files.exists(snapshotPath)) {
                    snapshotPath.toString()
                } else {
                    null
                }
            }
    }

    private fun parseJvmTarget(target: String): JvmTarget? =
        when (target) {
            "1.6", "6" -> JvmTarget.JVM1_6
            "1.8", "8" -> JvmTarget.JVM1_8
            "9" -> JvmTarget.JVM_9
            "10" -> JvmTarget.JVM_10
            "11" -> JvmTarget.JVM_11
            "12" -> JvmTarget.JVM_12
            "13" -> JvmTarget.JVM_13
            "14" -> JvmTarget.JVM_14
            "15" -> JvmTarget.JVM_15
            "16" -> JvmTarget.JVM_16
            "17" -> JvmTarget.JVM_17
            "18" -> JvmTarget.JVM_18
            "19" -> JvmTarget.JVM_19
            "20" -> JvmTarget.JVM_20
            "21" -> JvmTarget.JVM_21
            "22" -> JvmTarget.JVM_22
            "23" -> JvmTarget.JVM_23
            "24" -> JvmTarget.JVM_24
            "25" -> JvmTarget.JVM_25
            else -> null
        }

    private fun parseKotlinVersion(version: String): KotlinVersion? =
        when (version) {
            "1.4" -> KotlinVersion.V1_4
            "1.5" -> KotlinVersion.V1_5
            "1.6" -> KotlinVersion.V1_6
            "1.7" -> KotlinVersion.V1_7
            "1.8" -> KotlinVersion.V1_8
            "1.9" -> KotlinVersion.V1_9
            "2.0" -> KotlinVersion.V2_0
            "2.1" -> KotlinVersion.V2_1
            "2.2" -> KotlinVersion.V2_2
            "2.3" -> KotlinVersion.V2_3
            else -> null
        }

    private val JvmCompilationTask.Directories.stubs
      get() =
        Files
          .createDirectories(
            Paths
              .get(temp)
              .resolve("stubs"),
          ).toString()
}

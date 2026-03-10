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
 *
 */
package io.bazel.kotlin.builder.tasks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.bazel.kotlin.builder.Deps
import io.bazel.kotlin.builder.tasks.jvm.Ksp2Task
import io.bazel.worker.Status.SUCCESS
import io.bazel.worker.WorkerContext
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

@RunWith(JUnit4::class)
class Ksp2TaskMemoryLeakReproducerTest {
  private val task = Ksp2Task()

  @Test
  // Manual failing reproducer for rules_kotlin#1471 and google/ksp#2817.
  fun standaloneKsp2ShouldNotLeakDispatcherWorkersAcrossInvocations() {
    val baselineWorkers = defaultDispatcherWorkers()
    val observations = mutableListOf<String>()
    val iterations = 4

    WorkerContext.run(named = "ksp2-memory-leak-reproducer") {
      repeat(iterations) { iteration ->
        val invocation = createInvocation(iteration)
        try {
          val result =
            doTask("ksp2-$iteration") { taskContext ->
              task.invoke(taskContext, invocation.args)
            }

          assertThat(result.status).isEqualTo(SUCCESS)
          assertGeneratedClass(invocation.generatedClassesJar, invocation.generatedClassEntry)

          forceGc()
          val workers = waitForDefaultDispatcherWorkers(minimumSize = baselineWorkers.size + iteration + 1)
          observations += "iteration ${iteration + 1}: ${workers.sorted()}"
        } finally {
          invocation.root.toFile().deleteRecursively()
        }
      }
    }

    forceGc()
    val finalWorkers = defaultDispatcherWorkers()
    assertWithMessage(
      "Expected standalone KSP2 not to retain DefaultDispatcher workers across isolated classloaders, " +
        "but baseline=%s final=%s observations=%s",
      baselineWorkers.sorted(),
      finalWorkers.sorted(),
      observations,
    ).that(finalWorkers - baselineWorkers).isEmpty()
  }

  private fun createInvocation(iteration: Int): Invocation {
    val root = Files.createTempDirectory("ksp2-memory-leak-$iteration")
    val sourceJar = root.resolve("src").resolve("inputs.srcjar").also { Files.createDirectories(it.parent) }
    val outDir = root.resolve("out").also(Files::createDirectories)
    val generatedSourcesJar = outDir.resolve("generated-sources.jar")
    val generatedClassesJar = outDir.resolve("generated-classes.jar")
    val className = "LeakMe$iteration"
    writeSourceJar(sourceJar, className)
    return Invocation(
      root = root,
      generatedClassesJar = generatedClassesJar,
      generatedClassEntry = "repro/$className\$GeneratedDefinition\$.class",
      args =
        buildArgs(
          moduleName = "ksp2_memory_leak_$iteration",
          sourceJar = sourceJar,
          generatedSourcesJar = generatedSourcesJar,
          generatedClassesJar = generatedClassesJar,
        ),
    )
  }

  private fun buildArgs(
    moduleName: String,
    sourceJar: Path,
    generatedSourcesJar: Path,
    generatedClassesJar: Path,
  ): List<String> =
    buildList {
      add("--module_name")
      add(moduleName)
      add("--source_jars")
      add(sourceJar.toString())
      add("--generated_sources_output")
      add(generatedSourcesJar.toString())
      add("--generated_classes_output")
      add(generatedClassesJar.toString())
      add("--jvm_target")
      add("11")
      add("--language_version")
      add("2.0")
      add("--api_version")
      add("2.0")
      add("--jdk_home")
      add(System.getProperty("java.home"))
      libraryClasspath().forEach {
        add("--libraries")
        add(it)
      }
      processorClasspath().forEach {
        add("--processor_classpath")
        add(it)
      }
    }

  private fun writeSourceJar(
    sourceJar: Path,
    className: String,
  ) {
    val source =
      """
      package repro

      import src.test.data.jvm.ksp.bytecodegenerator.annotation.GenerateBytecode

      @GenerateBytecode
      class $className
      """.trimIndent()
    JarOutputStream(Files.newOutputStream(sourceJar)).use { jar ->
      jar.putNextEntry(JarEntry("LeakMe.kt"))
      jar.write(source.toByteArray(UTF_8))
      jar.closeEntry()
    }
  }

  private fun assertGeneratedClass(
    generatedClassesJar: Path,
    entryName: String,
  ) {
    JarFile(generatedClassesJar.toFile()).use { jar ->
      assertThat(jar.getEntry(entryName)).isNotNull()
    }
  }

  private fun libraryClasspath(): List<String> =
    listOf(
      dep("//src/test/data/jvm/ksp/bytecodegenerator/annotation:annotation.jar"),
      dep("//kotlin/compiler:kotlin-stdlib"),
    )

  private fun processorClasspath(): List<String> =
    listOf(
      dep("//src/main/kotlin/io/bazel/kotlin/ksp2:ksp2"),
      dep("//kotlin/compiler:symbol-processing-api"),
      dep("//kotlin/compiler:symbol-processing-aa"),
      dep("//kotlin/compiler:symbol-processing-common-deps"),
      dep("//kotlin/compiler:ksp-intellij-kotlinx-coroutines-core-jvm"),
      dep("//src/test/data/jvm/ksp/bytecodegenerator/processor/src/main/com/example:processor.jar"),
      dep("//src/test/data/jvm/ksp/bytecodegenerator/annotation:annotation.jar"),
      dep("@kotlin_rules_maven//:org_ow2_asm_asm"),
    )

  private fun dep(label: String): String = Deps.Dep.fromLabel(label).singleCompileJar()

  private fun defaultDispatcherWorkers(): Set<String> =
    Thread
      .getAllStackTraces()
      .filter { (thread, stackTrace) ->
        thread.isAlive &&
          thread.name.startsWith("DefaultDispatcher-worker-") &&
          stackTrace.any { it.className.startsWith("kotlinx.coroutines.") }
      }.keys
      .mapTo(linkedSetOf()) { "${it.name}#${it.id}" }

  private fun waitForDefaultDispatcherWorkers(minimumSize: Int): Set<String> {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    var workers = defaultDispatcherWorkers()
    while (System.nanoTime() < deadline) {
      if (workers.size >= minimumSize) {
        return workers
      }
      Thread.sleep(100)
      workers = defaultDispatcherWorkers()
    }
    return workers
  }

  private fun forceGc() {
    repeat(3) {
      System.gc()
      System.runFinalization()
      Thread.sleep(200)
    }
  }

  private data class Invocation(
    val root: Path,
    val generatedClassesJar: Path,
    val generatedClassEntry: String,
    val args: List<String>,
  )
}

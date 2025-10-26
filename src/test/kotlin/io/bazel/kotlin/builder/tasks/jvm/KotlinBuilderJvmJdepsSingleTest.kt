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
package io.bazel.kotlin.builder.tasks.jvm;

import com.google.common.truth.Truth.assertThat
import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.Deps.Dep
import io.bazel.kotlin.builder.KotlinJvmTestBuilder
import org.junit.Test
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Consumer

/**
 * Isolated test for debugging jdeps path mismatch issue.
 * Contains only the "assignment from function call" test from KotlinBuilderJvmJdepsTest.
 */
class KotlinBuilderJvmJdepsSingleTest {

  val ctx = KotlinJvmTestBuilder()

  val TEST_FIXTURES_DEP = Dep.fromLabel(":JdepsParserTestFixtures")
  val TEST_FIXTURES2_DEP = Dep.fromLabel(":JdepsParserTestFixtures2")
  val KOTLIN_STDLIB_DEP = Dep.fromLabel("//kotlin/compiler:kotlin-stdlib")

  @Test
  fun `assignment from function call`() {
    val depWithReturnTypesSuperType = runCompileTask { c: KotlinJvmTestBuilder.TaskBuilder ->
      c.addSource(
        "SomeSuperType.kt",
        """
            package something

            open class SomeSuperType
            """,
      )
      c.setLabel("depWithReturnType")
    }
    val depWithReturnType = runCompileTask { c: KotlinJvmTestBuilder.TaskBuilder ->
      c.addSource(
        "SomeType.kt",
        """
            package something

            class SomeType : SomeSuperType() {
              val stringValue = "Hello World"
            }
            """,
      )
      c.setLabel("depWithReturnType")
      c.addDirectDependencies(depWithReturnTypesSuperType)
    }

    val depWithFunction = runCompileTask { c: KotlinJvmTestBuilder.TaskBuilder ->
      c.addSource(
        "ContainsFunction.kt",
        """
            package something

            fun returnSomeType() = SomeType()
            """,
      )
      c.addDirectDependencies(depWithReturnType)
      c.setLabel("depWithFunction")
    }

    val dependingTarget = runJdepsCompileTask { c: KotlinJvmTestBuilder.TaskBuilder ->
      c.setLabel("//:dependingTarget")
      c.addSource(
        "ReferencesClassWithSuperClass.kt",
        """
            package something

            fun foo() {
              val assignment = returnSomeType()
              print(assignment.stringValue)
            }
          """,
      )
      c.addDirectDependencies(depWithFunction)
      c.addTransitiveDependencies(depWithReturnType, depWithReturnTypesSuperType)
    }

    val jdeps = depsProto(dependingTarget)
    val expected = Deps.Dependencies.newBuilder()
      .setRuleLabel("//:dependingTarget")
      .setSuccess(true)
      .addExplicitDep(depWithFunction.singleCompileJar())
      .addExplicitDep(depWithReturnType.singleCompileJar())
      .addExplicitDep(KOTLIN_STDLIB_DEP.singleCompileJar())
      .addImplicitDep(depWithReturnTypesSuperType.singleCompileJar())
      .buildSorted()

    println("=== JDEPS COMPARISON ===")
    println("Expected:")
    println(expected)
    println("\nActual:")
    println(jdeps)
    println("========================")

    assertThat(jdeps).isEqualTo(expected)
  }

  private fun depsProto(jdeps: Dep) =
    Deps.Dependencies.parseFrom(BufferedInputStream(Files.newInputStream(Paths.get(jdeps.jdeps()!!))))

  private fun runCompileTask(block: (c: KotlinJvmTestBuilder.TaskBuilder) -> Unit): Dep {
    return ctx.runCompileTask(
      Consumer { c: KotlinJvmTestBuilder.TaskBuilder ->
        c.useK2()
        block(c.outputJar().compileKotlin())
      },
    )
  }

  private fun runJdepsCompileTask(block: (c: KotlinJvmTestBuilder.TaskBuilder) -> Unit): Dep {
    return runCompileTask { c -> block(c.outputJdeps()) }
  }

  private fun Deps.Dependencies.Builder.addExplicitDep(depPath: String): Deps.Dependencies.Builder {
    addDependency(Deps.Dependency.newBuilder().setPath(depPath).setKind(Deps.Dependency.Kind.EXPLICIT))
    return this
  }

  private fun Deps.Dependencies.Builder.addImplicitDep(depPath: String): Deps.Dependencies.Builder {
    addDependency(Deps.Dependency.newBuilder().setPath(depPath).setKind(Deps.Dependency.Kind.IMPLICIT))
    return this
  }

  private fun Deps.Dependencies.Builder.addUnusedDep(depPath: String): Deps.Dependencies.Builder {
    addDependency(Deps.Dependency.newBuilder().setPath(depPath).setKind(Deps.Dependency.Kind.UNUSED))
    return this
  }

  private fun Deps.Dependencies.Builder.buildSorted(): Deps.Dependencies {
    val sortedDeps = dependencyList.sortedBy { it.path }
    sortedDeps.forEachIndexed { index, dep ->
      setDependency(index, dep)
    }
    return build()
  }
}

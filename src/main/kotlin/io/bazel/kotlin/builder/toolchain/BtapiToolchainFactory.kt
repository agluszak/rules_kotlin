package io.bazel.kotlin.builder.toolchain

import org.jetbrains.kotlin.buildtools.api.ExperimentalBuildToolsApi
import org.jetbrains.kotlin.buildtools.api.KotlinToolchains
import org.jetbrains.kotlin.buildtools.api.SharedApiClassesClassLoader
import java.io.File
import java.net.URLClassLoader

@OptIn(ExperimentalBuildToolsApi::class)
class BtapiToolchainFactory(
  private val buildToolsImplJar: File,
  private val compilerJar: File,
  private val pluginJars: List<File>,
) {
  fun createToolchains(): KotlinToolchains {
    // Plugins are part of the classloader, NOT passed via -Xplugin
    val classpath = listOf(buildToolsImplJar, compilerJar) + pluginJars
    val urls = classpath.map { it.toURI().toURL() }.toTypedArray()

    // SharedApiClassesClassLoader ensures API classes are shared
    val compilerClassloader = URLClassLoader(urls, SharedApiClassesClassLoader())
    return KotlinToolchains.loadImplementation(compilerClassloader)
  }
}

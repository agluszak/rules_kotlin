/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
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
package io.bazel.kotlin.compiler;

import io.bazel.kotlin.builder.Deps;
import io.bazel.kotlin.builder.toolchain.KotlinToolchain;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Tests for BuildToolsAPICompiler version reporting functionality.
 */
@RunWith(JUnit4.class)
public class BuildToolsAPICompilerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    // Kotlin version pattern: major.minor.patch[-qualifier]
    // Examples: "2.1.0", "2.2.0-Beta1", "2.0.20-RC2"
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9]+)?$"
    );

    /**
     * Creates a KotlinToolchain using explicit file paths from runfiles.
     * This avoids needing system properties to be set.
     */
    private KotlinToolchain createToolchain() {
        Path javaHome = FileSystems.getDefault().getPath(System.getProperty("java.home"));
        if (javaHome.endsWith(FileSystems.getDefault().getPath("jre"))) {
            javaHome = javaHome.getParent();
        }
        return KotlinToolchain.createToolchain(
            javaHome,
            new File(Deps.Dep.fromLabel("//kotlin/compiler:kotlin-compiler").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//src/main/kotlin/io/bazel/kotlin/compiler:compiler.jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("@kotlin_build_tools_api//jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("@kotlin_build_tools_impl//jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//kotlin/compiler:jvm-abi-gen").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//src/main/kotlin:skip-code-gen").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//src/main/kotlin:jdeps-gen").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//kotlin/compiler:kotlin-annotation-processing").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//kotlin/compiler:symbol-processing-api").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//kotlin/compiler:symbol-processing-cmdline").singleCompileJar()),
            new File(Deps.Dep.fromLabel("@kotlinx_serialization_core_jvm//jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("@kotlinx_serialization_json//jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("@kotlinx_serialization_json_jvm//jar").singleCompileJar()),
            new File(Deps.Dep.fromLabel("//kotlin/compiler:kotlin-daemon-client").singleCompileJar())
        );
    }

    @Test
    public void testGetCompilerVersion_returnsValidVersion() {
        KotlinToolchain toolchain = createToolchain();
        String version = toolchain.getCompilerVersion();

        assertWithMessage("Compiler version should not be null or empty")
            .that(version)
            .isNotEmpty();

        assertWithMessage("Compiler version should match Kotlin version format (e.g., '2.1.0' or '2.2.0-Beta1')")
            .that(VERSION_PATTERN.matcher(version).matches())
            .isTrue();
    }

    @Test
    public void testGetCompilerVersion_returnsConsistentVersion() {
        KotlinToolchain toolchain = createToolchain();

        // Call multiple times to verify consistency
        String version1 = toolchain.getCompilerVersion();
        String version2 = toolchain.getCompilerVersion();

        assertWithMessage("Compiler version should be consistent across calls")
            .that(version1)
            .isEqualTo(version2);
    }

    @Test
    public void testGetCompilerVersion_reportsExpectedVersionRange() {
        KotlinToolchain toolchain = createToolchain();
        String version = toolchain.getCompilerVersion();

        // Extract major version
        String[] parts = version.split("\\.");
        int majorVersion = Integer.parseInt(parts[0]);
        int minorVersion = Integer.parseInt(parts[1]);

        // We expect Kotlin 2.0 or later (rules_kotlin uses modern Kotlin)
        assertWithMessage("Compiler should be Kotlin 2.0 or later, got: " + version)
            .that(majorVersion >= 2)
            .isTrue();

        // If major version is 2, minor should be reasonable (0-20 as of 2025)
        if (majorVersion == 2) {
            assertWithMessage("Minor version should be reasonable, got: " + version)
                .that(minorVersion)
                .isAtLeast(0);
            assertWithMessage("Minor version seems too high, got: " + version)
                .that(minorVersion)
                .isAtMost(50); // Allow some headroom for future versions
        }
    }

    @Test
    public void testBuildToolsAPICompiler_directVersionAccess() {
        KotlinToolchain toolchain = createToolchain();
        BuildToolsAPICompiler compiler = new BuildToolsAPICompiler(
            toolchain.getKotlinCompilerJar(),
            toolchain.getBuildToolsImplJar()
        );

        String version = compiler.getCompilerVersion();

        assertWithMessage("BuildToolsAPICompiler should return valid version")
            .that(version)
            .isNotEmpty();

        assertWithMessage("Version should match expected format")
            .that(VERSION_PATTERN.matcher(version).matches())
            .isTrue();
    }

    @Test
    public void testCompilerVersion_cachingWorks() {
        KotlinToolchain toolchain = createToolchain();
        BuildToolsAPICompiler compiler = new BuildToolsAPICompiler(
            toolchain.getKotlinCompilerJar(),
            toolchain.getBuildToolsImplJar()
        );

        // Measure time for first call (lazy initialization)
        long start1 = System.nanoTime();
        String version1 = compiler.getCompilerVersion();
        long duration1 = System.nanoTime() - start1;

        // Measure time for second call (should use cached service)
        long start2 = System.nanoTime();
        String version2 = compiler.getCompilerVersion();
        long duration2 = System.nanoTime() - start2;

        assertWithMessage("Versions should be identical")
            .that(version1)
            .isEqualTo(version2);

        // Second call should be significantly faster (at least 2x)
        // This verifies that CompilationService is cached
        assertWithMessage("Second call should be faster due to caching. First: " + duration1 + "ns, Second: " + duration2 + "ns")
            .that(duration2 < duration1)
            .isTrue();
    }

    @Test
    public void testIncrementalCompilation_createsCache() throws Exception {
        KotlinToolchain toolchain = createToolchain();
        // For IC tests, we need to include kotlin-daemon-client in the classpath
        List<File> icClasspath = new java.util.ArrayList<>(toolchain.getKotlinxSerializationJars());
        icClasspath.add(toolchain.getKotlinDaemonClientJar());

        BuildToolsAPICompiler compiler = new BuildToolsAPICompiler(
            toolchain.getKotlinCompilerJar(),
            toolchain.getBuildToolsImplJar(),
            icClasspath
        );

        // Create temporary directories
        File sourceFile = temporaryFolder.newFile("Test.kt");
        File outputJar = temporaryFolder.newFile("output.jar");
        File icCacheDir = temporaryFolder.newFolder("ic_cache");

        // Write simple Kotlin source (without stdlib dependencies)
        Files.write(sourceFile.toPath(), Arrays.asList(
            "class SimpleClass {",
            "    val value: Int = 42",
            "}"
        ));

        // Prepare compiler arguments
        String[] args = new String[] {
            "-d", outputJar.getAbsolutePath(),
            "-module-name", "test_module",
            "-jvm-target", "11",
            sourceFile.getAbsolutePath()
        };

        // Compile with IC enabled
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out);

        int exitCode = compiler.compile(
            args,
            printStream,
            icCacheDir,
            Collections.emptyList()
        );

        // Verify compilation succeeded
        assertWithMessage("Compilation should succeed, output: " + out.toString())
            .that(exitCode)
            .isEqualTo(0);

        // Verify output jar was created
        assertWithMessage("Output jar should be created")
            .that(outputJar.exists())
            .isTrue();

        // Verify IC cache directory was used (should have classpath snapshot)
        File snapshotFile = new File(icCacheDir, "shrunk-classpath-snapshot.bin");
        assertWithMessage("IC cache snapshot should be created at: " + snapshotFile.getAbsolutePath())
            .that(snapshotFile.exists())
            .isTrue();

        // Verify IC cache directory has contents
        File[] cacheContents = icCacheDir.listFiles();
        assertWithMessage("IC cache should not be empty")
            .that(cacheContents)
            .isNotNull();
        assertWithMessage("IC cache should have files")
            .that(cacheContents.length)
            .isGreaterThan(0);
    }
}

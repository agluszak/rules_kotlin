# Copyright 2025 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Test that verifies Kotlin 2.2 compatibility with the introspected distribution.
# This test ensures that:
# 1. Kotlin compiler targets build successfully with Kotlin 2.2+
# 2. Optional jars (like trove4j, removed in 2.2) are handled gracefully
# 3. Basic Kotlin compilation works with the new distribution

load("//kotlin:jvm.bzl", "kt_jvm_library", "kt_jvm_test")

package(default_visibility = ["//visibility:private"])

# Test that basic Kotlin compilation works with Kotlin 2.2
kt_jvm_library(
    name = "basic_lib",
    srcs = ["BasicLib.kt"],
)

kt_jvm_test(
    name = "basic_test",
    srcs = ["BasicTest.kt"],
    test_class = "com.example.BasicTest",
    deps = [":basic_lib"],
)

# Test that trove4j target can be referenced even when jar is missing (Kotlin 2.2+)
# This filegroup will succeed whether trove4j contains a jar or is empty
filegroup(
    name = "trove4j_reference_test",
    srcs = [
        "//kotlin/compiler:trove4j",
    ],
)

# Test that kotlin-annotation-processing-compiler can be referenced
filegroup(
    name = "kapt_compiler_reference_test",
    srcs = [
        "//kotlin/compiler:kotlin-annotation-processing-compiler",
    ],
)

# Test that all KOTLIN_STDLIBS targets exist and can be built
filegroup(
    name = "kotlin_stdlibs_test",
    srcs = [
        "//kotlin/compiler:annotations",
        "//kotlin/compiler:kotlin-stdlib",
        "//kotlin/compiler:kotlin-stdlib-jdk7",
        "//kotlin/compiler:kotlin-stdlib-jdk8",
        "//kotlin/compiler:kotlinx-coroutines-core-jvm",
        "//kotlin/compiler:trove4j",
    ],
)

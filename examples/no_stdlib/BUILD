# Copyright 2018 The Bazel Authors. All rights reserved.
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

load("//kotlin:jvm.bzl", "kt_jvm_library")
load("//kotlin/internal:toolchains.bzl", "define_kt_toolchain")

# Example: Custom toolchain that doesn't link stdlib automatically
# This is useful for IntelliJ plugin development where the platform provides its own stdlib
define_kt_toolchain(
    name = "no_stdlib_toolchain",
    link_stdlib = False,
)

# Example 1: Library using the default toolchain (with stdlib auto-linked)
kt_jvm_library(
    name = "with_stdlib",
    srcs = ["WithStdlib.kt"],
)

# Example 2: Library with explicit stdlib dependency
# When using no_stdlib_toolchain, you can still add stdlib explicitly if needed
kt_jvm_library(
    name = "explicit_stdlib",
    srcs = ["ExplicitStdlib.kt"],
    deps = ["//kotlin/compiler:kotlin-stdlib"],
)

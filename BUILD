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

load("//kotlin:lint.bzl", "ktlint_config")

exports_files([
    "scripts/noop.sh",
])

filegroup(
    name = "local_repository_files",
    srcs = glob(
        ["*"],
        exclude = ["bazel-*"],
    ) + [
        # kotlin/ hierarchy
        "//kotlin:all_files",
        "//kotlin/compiler:all_files",
        "//kotlin/internal:all_files",
        "//kotlin/internal/jvm:all_files",
        "//kotlin/internal/lint:all_files",
        "//kotlin/internal/utils:all_files",
        "//kotlin/settings:all_files",
        # src/main/kotlin/ hierarchy
        "//src/main/kotlin:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/cmd:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/tasks:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/toolchain:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/utils:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/utils/jars:all_files",
        "//src/main/kotlin/io/bazel/kotlin/compiler:all_files",
        "//src/main/kotlin/io/bazel/kotlin/generate:all_files",
        "//src/main/kotlin/io/bazel/kotlin/ksp2:all_files",
        "//src/main/kotlin/io/bazel/kotlin/plugin:all_files",
        "//src/main/kotlin/io/bazel/kotlin/plugin/jdeps:all_files",
        "//src/main/kotlin/io/bazel/worker:all_files",
        # src/main/starlark/ hierarchy
        "//src/main/starlark:all_files",
        "//src/main/starlark/core:all_files",
        "//src/main/starlark/core/compile:all_files",
        "//src/main/starlark/core/compile/cli:all_files",
        "//src/main/starlark/core/options:all_files",
        "//src/main/starlark/core/plugin:all_files",
        "//src/main/starlark/core/repositories:all_files",
        "//src/main/starlark/core/repositories/kotlin:all_files",
        # Other
        "//src/main/protobuf:all_files",
        "//third_party:all_files",
    ],
    visibility = ["//:__subpackages__"],
)

filegroup(
    name = "editorconfig",
    srcs = [".editorconfig"],
)

ktlint_config(
    name = "ktlint_editorconfig",
    android_rules_enabled = False,
    editorconfig = "//:editorconfig",
    experimental_rules_enabled = False,
    visibility = ["//visibility:public"],
)

# The entire test suite excluding local tests.
test_suite(
    name = "all_tests",
    tests = [
        "//src/test/kotlin/io/bazel/kotlin:assertion_tests",
        "//src/test/kotlin/io/bazel/kotlin/builder:builder_tests",
        "//src/test/kotlin/io/bazel/worker:worker_tests",
        "//src/test/starlark:convert_tests",
        "//src/test/starlark:resource_strip_prefix_tests",
    ],
)

#  Local tests. Tests that shouldn't be run on the CI server.
test_suite(
    name = "all_local_tests",
    tests = [
        ":all_tests",
        "//src/test/kotlin/io/bazel/kotlin:local_assertion_tests",
        "//src/test/kotlin/io/bazel/worker:local_worker_tests",
        "//src/test/starlark:convert_tests",
        "//src/test/starlark:resource_strip_prefix_tests",
    ],
)


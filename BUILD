load("@buildifier_prebuilt//:rules.bzl", "buildifier")
load("//kotlin:lint.bzl", "ktlint_config")

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
load("//src/main/starlark/release:packager.bzl", "release_archive")

exports_files([
    "scripts/noop.sh",
    "MODULE.bazel",
])

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
    ],
)

# Release target.
release_archive(
    name = "rules_kotlin_release",
    srcs = [
        "MODULE.bazel",
    ],
    src_map = {
        "BUILD.release.bazel": "BUILD.bazel",
    },
    deps = [
        "//kotlin:pkg",
        "//src/main/kotlin:pkg",
        "//src/main/starlark:pkg",
        "//third_party:pkg",
    ],
)

# This target collects all of the parent workspace files needed by the child workspaces.
filegroup(
    name = "release_repositories",
    # Include every package that is required by the child workspaces.
    srcs = [
        ":rules_kotlin_release",
    ],
    visibility = ["//:__subpackages__"],
)

# This target collects all of the parent workspace files needed by the child workspaces.
# Integration tests use local_path_override(path = "../..") to reference the parent workspace,
# so they need access to all source BUILD files and .bzl files.
filegroup(
    name = "runtime_files",
    srcs = [
        "BUILD",
        "MODULE.bazel",
        "kotlin_rules_maven_install.json",
        # kotlin/
        "//kotlin:all_files",
        "//kotlin/compiler:all_files",
        "//kotlin/internal:all_files",
        "//kotlin/internal/jvm:all_files",
        "//kotlin/internal/lint:all_files",
        "//kotlin/internal/utils:all_files",
        "//kotlin/settings:all_files",
        # src/main/starlark/
        "//src/main/starlark:all_files",
        "//src/main/starlark/core:all_files",
        "//src/main/starlark/core/compile:all_files",
        "//src/main/starlark/core/compile/cli:all_files",
        "//src/main/starlark/core/options:all_files",
        "//src/main/starlark/core/plugin:all_files",
        "//src/main/starlark/core/repositories:all_files",
        "//src/main/starlark/core/repositories/kotlin:all_files",
        "//src/main/starlark/release_archive:all_files",
        "//src/main/starlark/release:all_files",
        # src/main/kotlin/
        "//src/main/kotlin:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/cmd:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/tasks:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/toolchain:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/utils:all_files",
        "//src/main/kotlin/io/bazel/kotlin/builder/utils/jars:all_files",
        "//src/main/kotlin/io/bazel/kotlin/compiler:all_files",
        "//src/main/kotlin/io/bazel/kotlin/generate:all_files",
        "//src/main/kotlin/io/bazel/kotlin/plugin:all_files",
        "//src/main/kotlin/io/bazel/kotlin/plugin/jdeps:all_files",
        "//src/main/kotlin/io/bazel/kotlin/test:all_files",
        "//src/main/kotlin/io/bazel/worker:all_files",
        # src/main/protobuf/
        "//src/main/protobuf:all_files",
        # third_party/
        "//third_party:all_files",
    ],
    visibility = ["//examples:__pkg__"],
)

buildifier(
    name = "buildifier.check",
    exclude_patterns = [
        "./.git/*",
        "./.ijwb/*",
    ],
    lint_mode = "warn",
    lint_warnings = [
        "-confusing-name",
        "-constant-glob",
        "-duplicated-name",
        "-function-docstring",
        "-function-docstring-args",
        "-function-docstring-header",
        "-module-docstring",
        "-name-conventions",
        "-no-effect",
        "-constant-glob",
        "-provider-params",
        "-print",
        "-rule-impl-return",
        "-bzl-visibility",
        "-unnamed-macro",
        "-uninitialized",
        "-unreachable",
    ],
    mode = "diff",
)

buildifier(
    name = "buildifier.fix",
    exclude_patterns = [
        "./.git/*",
    ],
    lint_mode = "fix",
)

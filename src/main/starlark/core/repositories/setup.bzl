# Copyright 2020 The Bazel Authors. All rights reserved.
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

load("@bazel_features//:deps.bzl", "bazel_features_deps")
load("@bazel_lib//lib:repositories.bzl", "bazel_lib_dependencies")
load("@bazel_skylib//:workspace.bzl", "bazel_skylib_workspace")
load("@com_google_protobuf//:protobuf_deps.bzl", "protobuf_deps")
load("@rules_jvm_external//:defs.bzl", "maven_install")
load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies")
load("@rules_proto//proto:setup.bzl", "rules_proto_setup")
load("@rules_proto//proto:toolchains.bzl", "rules_proto_toolchains")
load("//src/main/starlark/core/repositories:versions.bzl", "versions")

def kt_configure():
    """Setup dependencies."""
    rules_proto_dependencies()
    rules_proto_toolchains()
    rules_proto_setup()

    protobuf_deps()

    bazel_skylib_workspace()
    bazel_features_deps()
    bazel_lib_dependencies()

    # keep in sync with MODULE.bazel and `proto_version` variable there.
    proto_version = "4.33.4"

    maven_install(
        name = "kotlin_rules_maven",
        fetch_sources = True,
        artifacts = [
            "com.google.auto.service:auto-service-annotations:1.1.1",
            "com.google.auto.service:auto-service:1.1.1",
            "com.google.auto.value:auto-value-annotations:1.11.0",
            "com.google.auto.value:auto-value:1.11.0",
            "com.google.code.findbugs:jsr305:3.0.2",
            "com.google.code.gson:gson:2.10.1",
            "com.google.dagger:dagger-compiler:2.57.2",
            "com.google.dagger:dagger-producers:2.57.2",
            "com.google.dagger:dagger:2.57.2",
            "com.google.errorprone:error_prone_annotations:2.23.0",
            "com.google.guava:guava:33.0.0-jre",
            "com.google.protobuf:protobuf-java-util:{}".format(proto_version),
            "com.google.protobuf:protobuf-java:{}".format(proto_version),
            "com.google.truth:truth:0.45",
            "com.squareup.moshi:moshi-kotlin-codegen:1.15.2",
            "com.squareup.moshi:moshi-kotlin:1.15.2",
            "com.squareup.moshi:moshi:1.15.2",
            "dev.zacsweers.autoservice:auto-service-ksp:jar:1.2.0",
            "javax.annotation:javax.annotation-api:1.3.2",
            "javax.inject:javax.inject:1",
            "junit:junit:4.13-beta-3",
            "org.apache.commons:commons-compress:1.26.2",
            "org.codehaus.plexus:plexus-utils:3.0.24",
            # JetBrains annotations
            "org.jetbrains:annotations:13.0",
            # Kotlin stdlib and runtime dependencies
            "org.jetbrains.kotlin:kotlin-stdlib:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-reflect:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-test:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-script-runtime:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-daemon-client:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            # Kotlin compiler dependencies
            "org.jetbrains.kotlin:kotlin-compiler:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-annotation-processing:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:jvm-abi-gen:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            # Kotlin compiler plugins
            "org.jetbrains.kotlin:kotlin-allopen-compiler-plugin:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-sam-with-receiver-compiler-plugin:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-parcelize-compiler:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-parcelize-runtime:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            # Kotlin embeddable variants
            "org.jetbrains.kotlin:kotlin-compiler-embeddable:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-annotation-processing-embeddable:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            # Kotlin build tools (already present)
            "org.jetbrains.kotlin:kotlin-build-tools-api:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-build-tools-impl:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-metadata-jvm:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            "org.jetbrains.kotlin:kotlin-compiler-arguments-description:{}".format(versions.KOTLIN_CURRENT_COMPILER_VERSION),
            # kotlinx libraries
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1",
            "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1",
            "org.pantsbuild:jarjar:1.7.2",
        ],
        repositories = [
            "https://maven-central.storage.googleapis.com/repos/central/data/",
            "https://maven.google.com",
            "https://repo1.maven.org/maven2",
        ],
    )

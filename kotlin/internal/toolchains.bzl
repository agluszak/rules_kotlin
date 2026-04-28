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
load("@bazel_skylib//rules:common_settings.bzl", "BuildSettingInfo")
load("@rules_java//java:defs.bzl", "JavaInfo", "java_common")
load(
    "//kotlin/internal:defs.bzl",
    _TOOLCHAIN_TYPE = "TOOLCHAIN_TYPE",
)
load(
    "//kotlin/internal:opts.bzl",
    "JavacOptions",
    "KotlincOptions",
    "kt_javac_options",
    "kt_kotlinc_options",
)

"""Kotlin Toolchains

This file contains macros for defining and registering specific toolchains.
"""

def _kotlin_toolchain_impl(ctx):
    # Create neverlink JavaInfo providers using actual compile_jars (header jars) from stdlib targets.
    compile_time_providers = []
    for target in ctx.attr.jvm_stdlibs:
        if JavaInfo in target:
            for java_output in target[JavaInfo].java_outputs:
                compile_time_providers.append(JavaInfo(
                    output_jar = java_output.class_jar,
                    compile_jar = java_output.compile_jar if java_output.compile_jar else java_output.class_jar,
                    neverlink = True,
                ))

    # For runtime, use actual JavaInfo providers (they contain proper runtime jars)
    runtime_providers = [
        target[JavaInfo]
        for target in ctx.attr.jvm_runtime
        if JavaInfo in target
    ]

    if ctx.attr.experimental_build_tools_api:
        build_tools_info = ctx.attr.build_tools_impl[JavaInfo]
        build_tools_runtime_classpath = depset(
            direct = build_tools_info.runtime_output_jars,
            transitive = [build_tools_info.transitive_runtime_jars],
        )
    else:
        build_tools_runtime_classpath = depset()

    toolchain = dict(
        language_version = ctx.attr.language_version,
        api_version = ctx.attr.api_version,
        debug = ctx.attr.debug,
        jvm_target = ctx.attr.jvm_target,
        kotlinbuilder = ctx.attr.kotlinbuilder,
        builder_args = [],
        jdeps_merger = ctx.attr.jdeps_merger,
        ksp2 = ctx.attr.ksp2,
        ksp2_invoker = ctx.attr.ksp2_invoker,
        snapshot_worker = ctx.attr.snapshot_worker,
        btapi_runtime_classpath = build_tools_runtime_classpath,
        # BTAPI components from WIP branch
        btapi_build_tools_impl = ctx.file.btapi_build_tools_impl,
        btapi_kotlin_compiler_embeddable = ctx.file.btapi_kotlin_compiler_embeddable,
        btapi_kotlin_daemon_client = ctx.file.btapi_kotlin_daemon_client,
        btapi_kotlin_stdlib = ctx.file.btapi_kotlin_stdlib,
        btapi_kotlin_reflect = ctx.file.btapi_kotlin_reflect,
        btapi_kotlin_coroutines = ctx.file.btapi_kotlin_coroutines,
        btapi_annotations = ctx.file.btapi_annotations,
        # Plugins using BTAPI or internal mechanisms
        jvm_abi_gen = ctx.file.jvm_abi_gen if ctx.attr.experimental_build_tools_api else ctx.file.internal_jvm_abi_gen,
        skip_code_gen = ctx.file.skip_code_gen if ctx.attr.experimental_build_tools_api else ctx.file.internal_skip_code_gen,
        jdeps_gen = ctx.file.jdeps_gen if ctx.attr.experimental_build_tools_api else ctx.file.internal_jdeps_gen,
        kapt = ctx.file.kapt if ctx.attr.experimental_build_tools_api else ctx.file.internal_kapt,
        jvm_stdlibs = java_common.merge(compile_time_providers + runtime_providers),
        jvm_emit_jdeps = ctx.attr._jvm_emit_jdeps[BuildSettingInfo].value,
        execution_requirements = {
            "supports-multiplex-sandboxing": "1" if ctx.attr.experimental_multiplex_sandboxing else "0",
            "supports-multiplex-workers": "1" if ctx.attr.experimental_multiplex_workers else "0",
            "supports-path-mapping": "1" if ctx.attr.supports_path_mapping else "0",
            "supports-workers": "1",
        },
        experimental_use_abi_jars = ctx.attr.experimental_use_abi_jars,
        experimental_treat_internal_as_private_in_abi_jars = ctx.attr.experimental_treat_internal_as_private_in_abi_jars,
        experimental_remove_private_classes_in_abi_jars = ctx.attr.experimental_remove_private_classes_in_abi_jars,
        experimental_remove_debug_info_in_abi_jars = ctx.attr.experimental_remove_debug_info_in_abi_jars,
        experimental_strict_kotlin_deps = ctx.attr.experimental_strict_kotlin_deps,
        experimental_report_unused_deps = ctx.attr.experimental_report_unused_deps,
        experimental_reduce_classpath_mode = ctx.attr.experimental_reduce_classpath_mode,
        experimental_incremental_compilation = ctx.attr.experimental_incremental_compilation[BuildSettingInfo].value,
        experimental_ic_enable_logging = ctx.attr.experimental_ic_enable_logging[BuildSettingInfo].value,
        javac_options = ctx.attr.javac_options[JavacOptions] if ctx.attr.javac_options else None,
        kotlinc_options = ctx.attr.kotlinc_options[KotlincOptions] if ctx.attr.kotlinc_options else None,
        empty_jar = ctx.file._empty_jar,
        empty_jdeps = ctx.file._empty_jdeps,
        jacocorunner = ctx.attr.jacocorunner,
        experimental_prune_transitive_deps = ctx.attr._experimental_prune_transitive_deps[BuildSettingInfo].value,
        experimental_strict_associate_dependencies = ctx.attr._experimental_strict_associate_dependencies[BuildSettingInfo].value,
    )

    return [
        platform_common.ToolchainInfo(**toolchain),
    ]

_kt_toolchain = rule(
    doc = """The kotlin toolchain.""",
    attrs = {
        "api_version": attr.string(
            default = "2.1",
            values = ["2.0", "2.1", "2.2", "2.3"],
        ),
        "btapi_annotations": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:annotations"),
        ),
        "btapi_build_tools_impl": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_build_tools_impl"),
        ),
        "btapi_kotlin_compiler_embeddable": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_compiler_embeddable"),
        ),
        "btapi_kotlin_coroutines": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:kotlinx-coroutines-core-jvm"),
        ),
        "btapi_kotlin_daemon_client": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_daemon_client"),
        ),
        "btapi_kotlin_reflect": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:kotlin-reflect"),
        ),
        "btapi_kotlin_stdlib": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:kotlin-stdlib"),
        ),
        "build_tools_impl": attr.label(
            providers = [JavaInfo],
            cfg = "exec",
            default = Label("//kotlin/compiler:kotlin-build-tools-impl"),
        ),
        "experimental_build_tools_api": attr.bool(default = False),
        "debug": attr.string_list(allow_empty = True),
        "experimental_ic_enable_logging": attr.label(
            default = Label("//kotlin/settings:experimental_ic_enable_logging"),
        ),
        "experimental_incremental_compilation": attr.label(
            default = Label("//kotlin/settings:experimental_incremental_compilation"),
        ),
        "experimental_multiplex_sandboxing": attr.bool(default = False),
        "experimental_multiplex_workers": attr.bool(default = True),
        "experimental_reduce_classpath_mode": attr.string(
            default = "NONE",
            values = ["NONE", "KOTLINBUILDER_REDUCED"],
        ),
        "experimental_remove_debug_info_in_abi_jars": attr.bool(default = False),
        "experimental_remove_private_classes_in_abi_jars": attr.bool(default = False),
        "experimental_report_unused_deps": attr.string(
            default = "off",
            values = ["off", "warn", "error"],
        ),
        "experimental_strict_kotlin_deps": attr.string(
            default = "off",
            values = ["off", "warn", "error"],
        ),
        "experimental_treat_internal_as_private_in_abi_jars": attr.bool(default = False),
        "experimental_use_abi_jars": attr.bool(default = False),
        "internal_jdeps_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//src/main/kotlin:jdeps-gen"),
        ),
        "internal_jvm_abi_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:jvm-abi-gen"),
        ),
        "internal_kapt": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_annotation_processing_embeddable"),
        ),
        "internal_skip_code_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//src/main/kotlin:skip-code-gen"),
        ),
        "jacocorunner": attr.label(
            default = Label("@remote_java_tools//:jacoco_coverage_runner"),
        ),
        "javac_options": attr.label(providers = [JavacOptions]),
        "jdeps_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//src/main/kotlin:jdeps-gen"),
        ),
        "jdeps_merger": attr.label(
            default = Label("//src/main/kotlin:jdeps_merger"),
            executable = True,
            allow_files = True,
            cfg = "exec",
        ),
        "jvm_abi_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:jvm-abi-gen"),
        ),
        "jvm_runtime": attr.label_list(
            providers = [JavaInfo],
            cfg = "target",
        ),
        "jvm_stdlibs": attr.label_list(
            providers = [JavaInfo],
            cfg = "target",
        ),
        "jvm_target": attr.string(
            default = "1.8",
            values = ["1.6", "1.8", "9", "10", "11", "12", "13", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25"],
        ),
        "kapt": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//kotlin/compiler:kotlin-annotation-processing-embeddable"),
        ),
        "kotlinbuilder": attr.label(
            default = Label("//src/main/kotlin:build"),
            executable = True,
            allow_files = True,
            cfg = "exec",
        ),
        "kotlinc_options": attr.label(providers = [KotlincOptions]),
        "ksp2": attr.label(
            default = Label("//src/main/kotlin:ksp2"),
            executable = True,
            allow_files = True,
            cfg = "exec",
        ),
        "ksp2_invoker": attr.label(
            default = Label("//src/main/kotlin:ksp2_invoker"),
            allow_files = True,
            cfg = "exec",
        ),
        "language_version": attr.string(
            default = "2.1",
            values = ["2.0", "2.1", "2.2", "2.3"],
        ),
        "snapshot_worker": attr.label(
            default = Label("//src/main/kotlin:snapshot"),
            executable = True,
            allow_files = True,
            cfg = "exec",
        ),
        "skip_code_gen": attr.label(
            allow_single_file = True,
            cfg = "exec",
            default = Label("//src/main/kotlin:skip-code-gen"),
        ),
        "supports_path_mapping": attr.bool(default = False),
        "_empty_jar": attr.label(
            allow_single_file = True,
            cfg = "target",
            default = Label("//third_party:empty.jar"),
        ),
        "_empty_jdeps": attr.label(
            allow_single_file = True,
            cfg = "target",
            default = Label("//third_party:empty.jdeps"),
        ),
        "_experimental_prune_transitive_deps": attr.label(
            default = Label("//kotlin/settings:experimental_prune_transitive_deps"),
        ),
        "_experimental_strict_associate_dependencies": attr.label(
            default = Label("//kotlin/settings:experimental_strict_associate_dependencies"),
        ),
        "_jvm_emit_jdeps": attr.label(default = "//kotlin/settings:jvm_emit_jdeps"),
    },
    implementation = _kotlin_toolchain_impl,
    provides = [platform_common.ToolchainInfo],
)

# Evaluating the select in the context of bzl file to get its repository
_DEBUG_SELECT = select({
    str(Label("//kotlin/internal:builder_debug_trace")): ["trace"],
    "//conditions:default": [],
}) + select({
    str(Label("//kotlin/internal:builder_debug_timings")): ["timings"],
    "//conditions:default": [],
})

_EXPERIMENTAL_USE_ABI_JARS = str(Label("//kotlin/internal:experimental_use_abi_jars"))
_NOEXPERIMENTAL_USE_ABI_JARS = str(Label("//kotlin/internal:noexperimental_use_abi_jars"))

def define_kt_toolchain(
        name,
        language_version = None,
        api_version = None,
        jvm_target = None,
        experimental_use_abi_jars = False,
        experimental_treat_internal_as_private_in_abi_jars = False,
        experimental_remove_private_classes_in_abi_jars = False,
        experimental_remove_debug_info_in_abi_jars = False,
        experimental_strict_kotlin_deps = None,
        experimental_report_unused_deps = None,
        experimental_reduce_classpath_mode = None,
        experimental_multiplex_workers = None,
        experimental_multiplex_sandboxing = None,
        supports_path_mapping = None,
        experimental_incremental_compilation = None,
        experimental_ic_enable_logging = None,
        experimental_build_tools_api = None,
        javac_options = Label("//kotlin/internal:default_javac_options"),
        kotlinc_options = Label("//kotlin/internal:default_kotlinc_options"),
        jvm_stdlibs = None,
        jvm_runtime = None,
        jacocorunner = None,
        btapi_build_tools_impl = None,
        btapi_kotlin_compiler_embeddable = None,
        btapi_kotlin_daemon_client = None,
        btapi_kotlin_stdlib = None,
        btapi_kotlin_reflect = None,
        btapi_kotlin_coroutines = None,
        btapi_annotations = None,
        internal_jvm_abi_gen = None,
        internal_skip_code_gen = None,
        internal_jdeps_gen = None,
        internal_kapt = None,
        build_tools_impl = None,
        jvm_abi_gen = None,
        skip_code_gen = None,
        jdeps_gen = None,
        kapt = None,
        exec_compatible_with = None,
        target_compatible_with = None,
        target_settings = None):
    """Define the Kotlin toolchain."""
    impl_name = name + "_impl"

    _kt_toolchain(
        name = impl_name,
        language_version = language_version,
        api_version = api_version,
        jvm_target = jvm_target,
        debug = _DEBUG_SELECT,
        experimental_use_abi_jars = select({
            _EXPERIMENTAL_USE_ABI_JARS: True,
            _NOEXPERIMENTAL_USE_ABI_JARS: False,
            "//conditions:default": experimental_use_abi_jars,
        }),
        experimental_treat_internal_as_private_in_abi_jars = experimental_treat_internal_as_private_in_abi_jars,
        experimental_remove_private_classes_in_abi_jars = experimental_remove_private_classes_in_abi_jars,
        experimental_remove_debug_info_in_abi_jars = experimental_remove_debug_info_in_abi_jars,
        experimental_multiplex_workers = experimental_multiplex_workers,
        experimental_multiplex_sandboxing = experimental_multiplex_sandboxing,
        supports_path_mapping = supports_path_mapping,
        experimental_strict_kotlin_deps = experimental_strict_kotlin_deps,
        experimental_report_unused_deps = experimental_report_unused_deps,
        experimental_reduce_classpath_mode = experimental_reduce_classpath_mode,
        experimental_incremental_compilation = experimental_incremental_compilation,
        experimental_ic_enable_logging = experimental_ic_enable_logging,
        experimental_build_tools_api = experimental_build_tools_api,
        javac_options = javac_options,
        kotlinc_options = kotlinc_options,
        visibility = ["//visibility:public"],
        jacocorunner = jacocorunner,
        btapi_build_tools_impl = btapi_build_tools_impl if btapi_build_tools_impl != None else Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_build_tools_impl"),
        btapi_kotlin_compiler_embeddable = btapi_kotlin_compiler_embeddable if btapi_kotlin_compiler_embeddable != None else Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_compiler_embeddable"),
        btapi_kotlin_daemon_client = btapi_kotlin_daemon_client if btapi_kotlin_daemon_client != None else Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_daemon_client"),
        btapi_kotlin_stdlib = btapi_kotlin_stdlib if btapi_kotlin_stdlib != None else Label("//kotlin/compiler:kotlin-stdlib"),
        btapi_kotlin_reflect = btapi_kotlin_reflect if btapi_kotlin_reflect != None else Label("//kotlin/compiler:kotlin-reflect"),
        btapi_kotlin_coroutines = btapi_kotlin_coroutines if btapi_kotlin_coroutines != None else Label("//kotlin/compiler:kotlinx-coroutines-core-jvm"),
        btapi_annotations = btapi_annotations if btapi_annotations != None else Label("//kotlin/compiler:annotations"),
        internal_jvm_abi_gen = internal_jvm_abi_gen if internal_jvm_abi_gen != None else Label("//kotlin/compiler:jvm-abi-gen"),
        internal_skip_code_gen = internal_skip_code_gen if internal_skip_code_gen != None else Label("//src/main/kotlin:skip-code-gen"),
        internal_jdeps_gen = internal_jdeps_gen if internal_jdeps_gen != None else Label("//src/main/kotlin:jdeps-gen"),
        internal_kapt = internal_kapt if internal_kapt != None else Label("@rules_kotlin_maven//:org_jetbrains_kotlin_kotlin_annotation_processing_embeddable"),
        build_tools_impl = build_tools_impl,
        jvm_abi_gen = jvm_abi_gen,
        skip_code_gen = skip_code_gen,
        jdeps_gen = jdeps_gen,
        kapt = kapt,
        jvm_stdlibs = jvm_stdlibs if jvm_stdlibs != None else [
            Label("//kotlin/compiler:annotations"),
            Label("//kotlin/compiler:kotlin-stdlib"),
        ],
        jvm_runtime = jvm_runtime if jvm_runtime != None else [
            Label("//kotlin/compiler:kotlin-stdlib"),
        ],
    )
    native.toolchain(
        name = name,
        toolchain_type = _TOOLCHAIN_TYPE,
        toolchain = impl_name,
        visibility = ["//visibility:public"],
        exec_compatible_with = exec_compatible_with or [],
        target_compatible_with = target_compatible_with or [],
        target_settings = target_settings or [],
    )

def _kt_toolchain_alias_impl(ctx):
    toolchain_info = ctx.toolchains[_TOOLCHAIN_TYPE]
    return [toolchain_info]

_kt_toolchain_alias = rule(
    implementation = _kt_toolchain_alias_impl,
    toolchains = [_TOOLCHAIN_TYPE],
)

def kt_configure_toolchains():
    """Defines the toolchain_type and default toolchain for kotlin compilation."""
    if native.package_name() != "kotlin/internal":
        fail("kt_configure_toolchains must be called in kotlin/internal not %s" % native.package_name())

    kt_kotlinc_options(
        name = "default_kotlinc_options",
        include_stdlibs = "none",
        visibility = ["//visibility:public"],
    )

    kt_javac_options(
        name = "default_javac_options",
        visibility = ["//visibility:public"],
        no_proc = True,
        x_ep_disable_all_checks = True,
    )

    native.config_setting(
        name = "experimental_use_abi_jars",
        values = {"define": "experimental_use_abi_jars=1"},
        visibility = ["//visibility:public"],
    )
    native.config_setting(
        name = "noexperimental_use_abi_jars",
        values = {"define": "experimental_use_abi_jars=0"},
        visibility = ["//visibility:public"],
    )

    native.config_setting(
        name = "builder_debug_timings",
        values = {"define": "kt_timings=1"},
        visibility = ["//visibility:public"],
    )

    native.config_setting(
        name = "experimental_multiplex_workers",
        values = {"define": "kt_multiplex=1"},
        visibility = ["//visibility:public"],
    )

    native.config_setting(
        name = "builder_debug_trace",
        values = {"define": "kt_trace=1"},
        visibility = ["//visibility:public"],
    )

    native.toolchain_type(
        name = "kt_toolchain_type",
        visibility = ["//visibility:public"],
    )

    define_kt_toolchain(
        name = "default_toolchain",
    )

    _kt_toolchain_alias(
        name = "current_toolchain",
        visibility = ["//visibility:public"],
    )

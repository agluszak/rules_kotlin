load("//kotlin:jvm.bzl", "kt_jvm_import")
load(":kapt.bzl", "kapt_compiler_plugin")
load(":ksp.bzl", "kt_configure_ksp")

package(default_visibility = ["//visibility:public"])

# Configures the KSP plugins
kt_configure_ksp()

kt_jvm_import(
    name = "kotlin-stdlib",
    jar = "@kotlin_stdlib//file",
)

kt_jvm_import(
    name = "kotlin-reflect",
    jar = "@kotlin_reflect//file",
)

kt_jvm_import(
    name = "kotlin-test",
    jar = "@kotlin_test//file",
)

kt_jvm_import(
    name = "annotations",
    jar = "@jetbrains_annotations//file",
)

kt_jvm_import(
    name = "jvm-abi-gen",
    jar = "@kotlin_jvm_abi_gen//file",
)

kt_jvm_import(
    name = "kotlin-compiler",
    jar = "@kotlin_compiler//file",
)

kt_jvm_import(
    name = "kotlin-annotation-processing",
    jar = "@kotlin_annotation_processing//file",
)

kt_jvm_import(
    name = "allopen-compiler-plugin",
    jar = "@kotlin_allopen_compiler_plugin//file",
)

kt_jvm_import(
    name = "noarg-compiler-plugin",
    jar = "@kotlin_noarg_compiler_plugin//file",
)

kt_jvm_import(
    name = "sam-with-receiver-compiler-plugin",
    jar = "@kotlin_sam_with_receiver_compiler_plugin//file",
)

kt_jvm_import(
    name = "kotlin-serialization-compiler-plugin",
    jar = "@kotlin_serialization_compiler_plugin//file",
)

kt_jvm_import(
    name = "kotlinx-coroutines-core-jvm",
    jar = "@kotlinx_coroutines_core_jvm//file",
)

filegroup(
    name = "home",
    srcs = [
        ":annotations",
        ":kotlin-compiler",
        ":kotlin-reflect",
        ":kotlin-stdlib",
    ],
)

kapt_compiler_plugin(
    name = "kapt",
    id = "org.jetbrains.kotlin.kapt3",
)

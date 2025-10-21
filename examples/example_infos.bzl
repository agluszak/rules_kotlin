"""Module exposing information about the example integration tests."""

load("@bazel_skylib//lib:dicts.bzl", "dicts")
load(
    "@rules_bazel_integration_test//bazel_integration_test:defs.bzl",
    "bazel_integration_tests",
    "integration_test_utils",
)

def _new(name, oss, versions):
    return struct(
        name = name,
        oss = oss,
        versions = versions,
    )

def _test_name_prefix(name):
    return name + "_test"

def _test_name(example_name, version):
    return integration_test_utils.bazel_integration_test_name(
        _test_name_prefix(example_name),
        version,
    )

def _bazel_integration_test(ei, bazel_binaries):
    target_compatible_with = select(dicts.add(
        {
            "@platforms//os:{}".format(os): []
            for os in ei.oss
        },
        {"//conditions:default": ["@platforms//:incompatible"]},
    ))
    timeout = "moderate"
    # Include root MODULE.bazel and all source files for local_path_override to work
    workspace_files = integration_test_utils.glob_workspace_files(ei.name) + [
        "//:MODULE.bazel",
        "//:runtime_files",
    ]
    workspace_path = ei.name
    test_runner = ":test_runner"
    bazel_integration_tests(
        name = _test_name_prefix(ei.name),
        bazel_binaries = bazel_binaries,
        bazel_versions = ei.versions,
        tags = integration_test_utils.DEFAULT_INTEGRATION_TEST_TAGS + [
            "no-sandbox",
        ],
        timeout = timeout,
        target_compatible_with = target_compatible_with,
        test_runner = test_runner,
        workspace_files = workspace_files,
        workspace_path = workspace_path,
    )

# Simple examples - test on multiple platforms and Bazel versions
_simple_examples = [
    "trivial",
    "associates",
    "multiplex",
]

# Android examples - test on Linux only (requires Android SDK)
_android_examples = [
    "android",
    "plugin",
    "deps",
    "anvil",
    "jetpack_compose",
]

# JVM examples with complex dependencies
_jvm_examples = [
    "dagger",
    "ksp",
]

def _make_all_examples(bazel_versions):
    return [
        _new(
            name = name,
            oss = ["linux", "macos"],
            versions = bazel_versions,
        )
        for name in _simple_examples
    ] + [
        _new(
            name = name,
            oss = ["linux"],
            versions = [bazel_versions[0]],  # Current version only
        )
        for name in _android_examples
    ] + [
        _new(
            name = name,
            oss = ["linux", "macos"],
            versions = [bazel_versions[0]],  # Current version only
        )
        for name in _jvm_examples
    ]

example_infos = struct(
    make_all = _make_all_examples,
    bazel_integration_test = _bazel_integration_test,
    test_name_prefix = _test_name_prefix,
    test_name = _test_name,
)

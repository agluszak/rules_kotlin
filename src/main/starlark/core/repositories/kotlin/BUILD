load("@bazel_skylib//:bzl_library.bzl", "bzl_library")

bzl_library(
    name = "kotlin",
    srcs = glob(["*.bzl"]),
    visibility = [
        "//visibility:public",
    ],
)

filegroup(
    name = "all_files",
    srcs = glob(["**"]),
    visibility = ["//:__subpackages__"],
)

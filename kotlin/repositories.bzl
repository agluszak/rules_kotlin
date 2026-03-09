load(
    "//src/main/starlark/core/repositories:versions.bzl",
    _versions = "versions",
)

versions = _versions

def kotlin_repositories(**kwargs):
    if kwargs:
        fail(
            "kotlin_repositories no longer accepts version overrides. " +
            "Kotlin and KSP artifacts are configured via kt_configure(). " +
            "Unsupported kwargs: {}".format(", ".join(sorted(kwargs.keys()))),
        )

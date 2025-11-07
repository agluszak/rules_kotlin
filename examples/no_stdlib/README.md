# Controlling Kotlin stdlib Linking

This example shows how to disable automatic Kotlin stdlib linking using the `link_stdlib` toolchain attribute.

## Use Case

IntelliJ plugin development: The IntelliJ Platform provides its own Kotlin stdlib, so you don't want rules_kotlin to add another one.

## Usage

Define a custom toolchain with `link_stdlib = False`:

```python
define_kt_toolchain(
    name = "no_stdlib_toolchain",
    link_stdlib = False,
)
```

Register it in your WORKSPACE or MODULE.bazel:

```python
register_toolchains("//your/package:no_stdlib_toolchain")
```

Targets can still explicitly add stdlib when needed:

```python
kt_jvm_library(
    name = "my_lib",
    srcs = ["MyLib.kt"],
    deps = ["@rules_kotlin//kotlin/compiler:kotlin-stdlib"],
)
```

Default: `link_stdlib = True` (backward compatible)

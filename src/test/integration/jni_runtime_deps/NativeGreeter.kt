package jni_runtime_deps

import java.io.File

class NativeGreeter {
    external fun greet(): String

    companion object {
        init {
            // Load the native library from runfiles
            val runfilesDir = System.getenv("RUNFILES_DIR")
                ?: System.getenv("JAVA_RUNFILES")
                ?: error("No runfiles directory found")

            val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"
            val libPath = File(runfilesDir, "$workspace/src/test/integration/jni_runtime_deps/libnative_greeter.so")

            if (!libPath.exists()) {
                error("Native library not found at: ${libPath.absolutePath}")
            }

            System.load(libPath.absolutePath)
        }
    }
}

fun main() {
    val greeter = NativeGreeter()
    val result = greeter.greet()

    if (result != "Hello from JNI!") {
        error("Expected 'Hello from JNI!', got: '$result'")
    }

    println("SUCCESS: JNI cc_binary runtime_dep is working!")
}

package non_java_runtime_deps

import java.io.File

fun main() {
    // Find the helper script in runfiles
    val runfilesDir = System.getenv("RUNFILES_DIR")
        ?: System.getenv("JAVA_RUNFILES")
        ?: error("No runfiles directory found")

    val workspace = System.getenv("TEST_WORKSPACE") ?: "_main"
    val helperPath = File(runfilesDir, "$workspace/src/test/integration/non_java_runtime_deps/helper.sh")

    if (!helperPath.exists()) {
        error("Helper script not found at: ${helperPath.absolutePath}")
    }

    // Execute the helper script
    val process = ProcessBuilder(helperPath.absolutePath)
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        error("Helper script failed with exit code: $exitCode")
    }

    if (output != "HELPER_OK") {
        error("Expected 'HELPER_OK', got: '$output'")
    }

    println("SUCCESS: Non-Java runtime_dep is accessible and working!")
}

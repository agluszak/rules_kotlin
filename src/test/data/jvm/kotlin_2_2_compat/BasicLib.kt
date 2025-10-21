/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example

/**
 * A simple class to test that Kotlin 2.2 compilation works with the introspected distribution.
 * This verifies that the compiler can successfully compile Kotlin code without requiring
 * trove4j or other jars that were removed in Kotlin 2.2.
 */
class BasicLib {
    fun greet(name: String): String {
        return "Hello, $name!"
    }

    // Use some Kotlin 2.0+ features to ensure we're actually using the new compiler
    fun useModernKotlin(): String {
        // Data objects (Kotlin 1.9+)
        data object Config {
            const val VERSION = "2.2"
        }

        return "Kotlin version: ${Config.VERSION}"
    }
}

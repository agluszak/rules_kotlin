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

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Test that verifies Kotlin 2.2 compilation works correctly.
 * This test ensures that the introspected distribution allows compilation
 * even when optional jars (like trove4j) are missing.
 */
class BasicTest {
    @Test
    fun testBasicCompilation() {
        val lib = BasicLib()
        val result = lib.greet("Kotlin 2.2")
        assertEquals("Hello, Kotlin 2.2!", result)
    }

    @Test
    fun testModernKotlinFeatures() {
        val lib = BasicLib()
        val result = lib.useModernKotlin()
        assertEquals("Kotlin version: 2.2", result)
    }
}

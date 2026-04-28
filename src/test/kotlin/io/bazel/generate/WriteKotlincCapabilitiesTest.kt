package io.bazel.generate

import com.google.common.truth.Truth.assertThat
import io.bazel.kotlin.generate.WriteKotlincCapabilities
import org.junit.Test
import java.nio.file.Files

class WriteKotlincCapabilitiesTest {
  @Test
  fun smokeTest() {
    val tmp = Files.createTempDirectory("WriteKotlincCapabilitiesTest")
    WriteKotlincCapabilities.main("--out", tmp.toString())
    val got = tmp.resolve(WriteKotlincCapabilities.capabilitiesName)
    assertThat(Files.exists(got)).isTrue()
    assertThat(Files.readString(got)).contains("-Werror")
  }
}

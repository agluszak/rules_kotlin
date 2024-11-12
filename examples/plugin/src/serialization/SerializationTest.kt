package plugin.serialization

import org.junit.*
import org.junit.Assert.assertNotNull
import kotlinx.serialization.*
import kotlinx.serialization.json.*


class SerializationTest {
  @Test
  fun dataShouldHaveASerializerMethod() {

    assertNotNull(Json.encodeToString(Data("dupka", 13, AnotherData("asd")))  )
  }
}

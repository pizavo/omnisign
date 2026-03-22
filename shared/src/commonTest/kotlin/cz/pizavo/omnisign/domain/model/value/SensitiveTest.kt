package cz.pizavo.omnisign.domain.model.value

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Verifies [Sensitive] masking behaviour and the [sensitive] extension.
 */
class SensitiveTest : FunSpec({

    test("toString returns *** regardless of the wrapped value") {
        Sensitive("my-secret").toString() shouldBe "***"
    }

    test("string interpolation masks the value") {
        "${Sensitive("password123")}" shouldBe "***"
    }

    test("value property exposes the raw underlying value") {
        Sensitive("raw").value shouldBe "raw"
    }

    test("two Sensitive instances with the same value are equal") {
        Sensitive("a") shouldBe Sensitive("a")
    }

    test("two Sensitive instances with different values are not equal") {
        Sensitive("a") shouldNotBe Sensitive("b")
    }

    test("String.sensitive wraps the string in Sensitive") {
        "secret".sensitive() shouldBe Sensitive("secret")
    }

    test("String.sensitive masks value in toString") {
        "secret".sensitive().toString() shouldBe "***"
    }

    test("non-String type parameter is masked in toString") {
        Sensitive(42).toString() shouldBe "***"
    }

    test("serialization throws SerializationException") {
        shouldThrow<SerializationException> {
            Json.encodeToString(SensitiveSerializer(serializer<String>()), Sensitive("secret"))
        }
    }

    test("deserialization throws SerializationException") {
        shouldThrow<SerializationException> {
            Json.decodeFromString(SensitiveSerializer(serializer<String>()), "\"secret\"")
        }
    }
})

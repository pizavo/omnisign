package cz.pizavo.omnisign.data.serializer

import cz.pizavo.omnisign.domain.model.config.service.TimestampServerConfig
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import tools.jackson.databind.DatabindException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule

/**
 * Verifies that [SensitiveJacksonSerializer] refuses to write a [Sensitive] without putting its value
 * in the error, and that a `null` one is written as `null`.
 *
 * The mapper here has no mixin, so [TimestampServerConfig.runtimePassword] is an ordinary property to
 * it, standing in for a secret that a future property forgets to hide.
 */
class SensitiveJacksonSerializerTest : FunSpec({

	val mapper = JsonMapper.builder()
		.addModule(KotlinModule.Builder().build())
		.addModule(SimpleModule().addSerializer(Sensitive::class.java, SensitiveJacksonSerializer()))
		.build()

	test("writing a Sensitive throws without the value in the message") {
		val ex = shouldThrow<DatabindException> { mapper.writeValueAsString("hunter2".sensitive()) }
		ex.message.shouldNotBeNull() shouldContain "Sensitive<T> must not be serialized"
		ex.message.shouldNotBeNull() shouldNotContain "hunter2"
	}

	test("writing an object that holds a Sensitive names the property but not the value") {
		val config = TimestampServerConfig(url = "https://tsa.example", runtimePassword = "hunter2".sensitive())
		val ex = shouldThrow<DatabindException> { mapper.writeValueAsString(config) }
		ex.message.shouldNotBeNull() shouldContain "[\"runtimePassword\"]"
		ex.message.shouldNotBeNull() shouldNotContain "hunter2"
	}

	test("a null Sensitive is written as null") {
		mapper.writeValueAsString(TimestampServerConfig(url = "https://tsa.example")) shouldContain
			"\"runtimePassword\":null"
	}
})

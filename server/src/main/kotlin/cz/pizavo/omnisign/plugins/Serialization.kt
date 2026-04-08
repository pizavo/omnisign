package cz.pizavo.omnisign.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Pre-configured [Json] instance shared by content negotiation and manual serialization.
 */
val serverJson = Json {
	prettyPrint = true
	isLenient = false
	ignoreUnknownKeys = true
	encodeDefaults = true
}

/**
 * Install Ktor [ContentNegotiation] plugin with kotlinx-serialization JSON.
 */
fun Application.configureSerialization() {
	install(ContentNegotiation) {
		json(serverJson)
	}
}


package cz.pizavo.omnisign.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json

/**
 * Pre-configured [Json] instance shared by content negotiation and manual serialization.
 *
 * Compact (single-line) on purpose: API consumers are the web client and other
 * machine callers; whitespace would just bloat the wire and break any value that
 * has to travel inside an HTTP header (CR/LF aren't allowed there per RFC 7230
 * §3.2). Operators inspecting responses by hand can pipe through `jq` or rely on
 * the browser DevTools JSON viewer for pretty-printed display.
 */
val serverJson = Json {
	prettyPrint = false
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


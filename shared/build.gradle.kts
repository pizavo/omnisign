import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.zip.ZipFile

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.kotest)
	alias(libs.plugins.decoroutinator)
	alias(libs.plugins.dokka)
}

afterEvaluate {
	configurations.findByName("commonTestApi")?.dependencies?.removeIf {
		it.group == "io.kotest" && it.name == "kotest-assertions-core"
	}
}

configurations.findByName("commonTestApi")?.dependencies?.removeIf {
	it.group == "io.kotest" && it.name == "kotest-assertions-core"
}

kotlin {
	jvm {
		compilerOptions {
			jvmTarget = JvmTarget.JVM_25
			freeCompilerArgs.add("-Xadd-modules=java.smartcardio")
		}
		testRuns.configureEach {
			executionTask.configure {
				useJUnitPlatform()
				jvmArgs(
					"-XX:+EnableDynamicAgentLoading",
					"-Xshare:off",
					"--enable-native-access=ALL-UNNAMED",
					"--add-modules=java.smartcardio",
					"--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED",
				)
			}
		}
	}
	
	@OptIn(ExperimentalWasmDsl::class)
	wasmJs {
		browser()
	}
	
	sourceSets {
		commonMain.dependencies {
			implementation(project.dependencies.platform(libs.koin.bom))
			implementation(libs.koin.core)
			implementation(libs.koin.annotations)
			
			implementation(libs.kotlinx.serialization.json)
			implementation(libs.kotlinx.serialization.cbor)

			api(libs.arrow.core)
			api(libs.kotlin.logging)
			implementation(libs.arrow.fx.coroutines)
			implementation(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(libs.kotest.engine)
			implementation(libs.kotest.core)
		}
		jvmTest.dependencies {
			implementation(libs.koin.test)
			implementation(libs.mockk)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.kotest.arrow)
			implementation(libs.kotest.jvm.runner)
			implementation(libs.kotest.decoroutinator)
			implementation(libs.decoroutinator.jvm)
			implementation(libs.logback)
		}
		wasmJsTest.dependencies {
			implementation(libs.ktor.client.mock)
			implementation(libs.kotest.arrow)
		}
		wasmJsMain.dependencies {
			implementation(libs.ktor.client.core)
			implementation(libs.ktor.client.content.negotiation)
			implementation(libs.ktor.serialization.json)
			implementation(libs.ktor.client.cio)
		}
		jvmMain.dependencies {
			implementation(project.dependencies.platform(libs.dss.bom))
			
			implementation(libs.dss.document)
			implementation(libs.dss.model)
			implementation(libs.dss.cms.obj)
			
			implementation(libs.dss.pades)
			implementation(libs.dss.pades.pdfbox)
			
			implementation(libs.dss.enumerations)
			implementation(libs.dss.alert)
			implementation(libs.dss.utils.apache.commons)
			implementation(libs.dss.i18n)
			
			implementation(libs.dss.crl.parser)
			implementation(libs.dss.crl.parser.stream)
			implementation(libs.dss.token)
			implementation("${libs.dss.pdfa.get().module}") {
				exclude(group = "net.sf.saxon", module = "Saxon-HE")
			}
			
			implementation(libs.dss.service) // handles TSP, OCSP, CRL requests, etc.
			implementation(libs.dss.tsl.validation) // TLValidationJob, LOTLSource, TLSource
			implementation(libs.dss.specs.trusted.list) // JAXB model for building TL XML
			implementation(libs.dss.timestamp.remote)
			implementation(libs.dss.validation)
			
			implementation(libs.dss.policy.jaxb)
			implementation(libs.dss.policy.crypto.xml)
			implementation(libs.dss.policy.crypto.json)
			
			implementation(libs.jaxb.runtime)
			implementation("com.github.javakeyring:java-keyring:${libs.versions.keyring.get()}") {
				exclude(group = "com.github.hypfvieh")
				exclude(group = "de.swiesend")
			}
			implementation(libs.purejava.secret.service)
			
			implementation(libs.jackson.databind)
			implementation(libs.jackson.kotlin)
			implementation(libs.jackson.yaml)
			implementation(libs.jackson.xml)

			implementation(libs.jna)
			implementation(libs.jna.platform)
		}
	}
}

/**
 * Downloads the current Official Journal (OJ) keystore from the DSS demonstrations
 * repository into `src/jvmMain/resources/lotl-keystore.p12`.
 *
 * The keystore contains the EU Commission signing certificates required to verify
 * the EU LOTL signature. Run this task whenever the EC rotates its signing keys
 * to keep the bundled keystore in sync:
 *
 * ```
 * ./gradlew :shared:updateLotlKeystore
 * ```
 */
val updateLotlKeystore by tasks.registering {
	group = "verification"
	description = "Downloads the latest OJ keystore from the DSS demonstrations repository."
	val keystoreTarget = layout.projectDirectory.file("src/jvmMain/resources/lotl-keystore.p12")
	outputs.file(keystoreTarget)
	doLast {
		val url = URI.create(
			"https://raw.githubusercontent.com/esig/dss-demonstrations/master" +
			"/dss-demo-webapp/src/main/resources/keystore.p12"
		).toURL()
		keystoreTarget.asFile.parentFile.mkdirs()
		url.openStream().use { input ->
			keystoreTarget.asFile.outputStream().use { output -> input.copyTo(output) }
		}
		logger.lifecycle("OJ keystore updated → ${keystoreTarget.asFile}")
	}
}

/**
 * Rebuilds one of the DSS validation-message translations,
 * `src/jvmMain/resources/dss-messages_<locale>.properties`, against the English base bundle of the
 * DSS version on the classpath.
 *
 * A DSS upgrade adds, rewords and drops message keys, which `DssMessagesCzechCoverageTest` and its
 * Slovak twin then report. This task applies the translations written in answer:
 *
 * ```
 * .\gradlew :shared:regenerateDssMessages -Plocale=cs -Padditions=new-cs.properties
 * ```
 *
 * The additions file, resolved against the root project, holds `KEY=translation` lines for new and
 * reworded messages in any order; without it the bundle is only laid out again. Each message is taken
 * from the additions, or else from the current bundle, and written in the order and under the section
 * comments of DSS's English original, keeping a blank line wherever the current bundle has one too.
 * Keys DSS no longer defines are dropped, and the DSS version the header comment names is moved to the
 * one on the classpath. The output is UTF-8 with LF line endings, which `Properties.load(Reader)` and
 * DSS's `ResourceBundle` lookup read alike.
 *
 * Nothing is written while anything is wrong: a key left untranslated, an addition DSS does not
 * define, a translation whose `{n}` placeholders differ from the English original, or a lone
 * apostrophe, which DSS's `MessageFormat` would swallow together with the text after it. The failure
 * lists the untranslated keys with their English text, ready to be translated into the additions file.
 */
tasks.register("regenerateDssMessages") {
	group = "verification"
	description = "Rebuilds a DSS message translation against the DSS base bundle, merging new translations."

	val runtimeArtifacts = configurations.named("jvmRuntimeClasspath")
		.flatMap { it.incoming.artifacts.resolvedArtifacts }
	val locale = providers.gradleProperty("locale")
	val rootDirectory = rootDir
	val additionsFile = providers.gradleProperty("additions").map { rootDirectory.resolve(it) }
	val resourcesDir = layout.projectDirectory.dir("src/jvmMain/resources")

	doLast {
		val tag = locale.orNull ?: throw GradleException("Name the bundle to rebuild: -Plocale=cs or -Plocale=sk.")
		val target = resourcesDir.file("dss-messages_$tag.properties").asFile
		if (!target.isFile) throw GradleException("No DSS message bundle for '$tag': $target")

		val i18n = runtimeArtifacts.get().firstOrNull {
			(it.id.componentIdentifier as? ModuleComponentIdentifier)?.module == "dss-i18n"
		} ?: throw GradleException("dss-i18n is not on the JVM runtime classpath.")
		val dssVersion = (i18n.id.componentIdentifier as ModuleComponentIdentifier).version
		val baseLines = ZipFile(i18n.file).use { zip ->
			val entry = zip.getEntry("dss-messages.properties")
				?: throw GradleException("${i18n.file.name} carries no dss-messages.properties.")
			zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).readLines()
		}
		val existingLines = target.readLines(Charsets.UTF_8)
		val additionLines = additionsFile.orNull?.let { file ->
			if (!file.isFile) throw GradleException("Additions file not found: $file")
			file.readLines(Charsets.UTF_8)
		}.orEmpty()

		fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("#") || it.startsWith("!") }

		fun keyAndValue(line: String): Pair<String, String> {
			val text = line.trimStart()
			var i = 0
			while (i < text.length && text[i] !in "=: \t\u000c") i++
			val key = text.substring(0, i)
			while (i < text.length && text[i] in " \t\u000c") i++
			if (i < text.length && text[i] in "=:") i++
			while (i < text.length && text[i] in " \t\u000c") i++
			return key to text.substring(i)
		}

		fun messages(lines: List<String>, source: String): Map<String, String> {
			val messages = linkedMapOf<String, String>()
			lines.filter { it.isNotBlank() && !isComment(it) }.forEach { line ->
				if (line.endsWith("\\")) throw GradleException("Line continuation not supported in $source: $line")
				val (key, value) = keyAndValue(line)
				if (key.isEmpty()) throw GradleException("Unparseable line in $source: $line")
				if (messages.put(key, value) != null) throw GradleException("Duplicate key $key in $source")
			}
			return messages
		}

		fun placeholders(pattern: String): Set<String> =
			Regex("""\{\d+}""").findAll(pattern).map { it.value }.toSortedSet()

		val base = messages(baseLines, "the DSS $dssVersion base bundle")
		val existing = messages(existingLines, target.name)
		val additions = messages(additionLines, additionsFile.orNull?.name ?: "the additions")

		val problems = additions.keys.filter { it !in base }.map { "Addition not in the base bundle: $it" }.toMutableList()
		val untranslated = mutableListOf<String>()

		val headerEnd = existingLines.indexOfFirst { !isComment(it) }.let { if (it < 0) existingLines.size else it }
		val oldVersion = existingLines.take(headerEnd).firstNotNullOfOrNull { line ->
			Regex("""blob/([^/\s]+)/dss-i18n/""").find(line)?.groupValues?.get(1)
		}
		val header = existingLines.take(headerEnd).map { line ->
			if (oldVersion != null) line.replace(oldVersion, dssVersion) else line
		}

		val existingPosition = mutableMapOf<String, Int>()
		val existingBlanks = mutableSetOf<Int>()
		for (i in headerEnd until existingLines.size) {
			val line = existingLines[i]
			when {
				line.isBlank() -> if (i > headerEnd) existingBlanks += i
				!isComment(line) -> existingPosition[keyAndValue(line).first] = i
			}
		}
		val baseKeys = baseLines.filter { it.isNotBlank() && !isComment(it) }.map { keyAndValue(it).first }

		val out = StringBuilder()
		header.forEach { out.append(it).append('\n') }
		out.append('\n')
		var keyIndex = 0
		var previousExisting: String? = null
		var fromAdditions = 0
		baseLines.forEach { line ->
			when {
				line.isBlank() -> {
					val previous = previousExisting
					val next = baseKeys.drop(keyIndex).firstOrNull { it in existingPosition }
					val keep = previous == null || next == null ||
						existingPosition.getValue(previous) + 1 in existingBlanks ||
						existingPosition.getValue(next) - 1 in existingBlanks
					if (keep) out.append('\n')
				}

				isComment(line) -> out.append(line.trim()).append('\n')

				else -> {
					val key = keyAndValue(line).first
					keyIndex++
					if (key in existingPosition) previousExisting = key
					val translation = additions[key]?.also { fromAdditions++ } ?: existing[key]
					if (translation == null) {
						untranslated += "$key=${base.getValue(key)}"
						return@forEach
					}
					val english = base.getValue(key)
					if (placeholders(english) != placeholders(translation)) {
						problems += "Placeholder mismatch in $key: English ${placeholders(english)}, " +
							"translation ${placeholders(translation)}"
					}
					if ('\'' in translation.replace("''", "")) {
						problems += "Lone apostrophe in $key, which MessageFormat would treat as a quote: $translation"
					}
					if (translation != translation.trim()) problems += "Leading or trailing whitespace in $key"
					out.append(key).append('=').append(translation).append('\n')
				}
			}
		}

		if (problems.isNotEmpty() || untranslated.isNotEmpty()) {
			val report = StringBuilder("${target.name} was left unchanged.")
			problems.forEach { report.append("\n  - ").append(it) }
			if (untranslated.isNotEmpty()) {
				report.append("\n${untranslated.size} key(s) need a translation; their English text, to translate into the additions file:")
				untranslated.forEach { report.append("\n    ").append(it) }
			}
			throw GradleException(report.toString())
		}

		target.writeText(out.toString(), Charsets.UTF_8)
		val dropped = existing.keys.filter { it !in base }.sorted()
		val droppedNote = if (dropped.isEmpty()) "" else ", ${dropped.size} dropped (${dropped.joinToString()})"
		logger.lifecycle(
			"${target.name} rebuilt against DSS $dssVersion: ${base.size} keys, " +
				"$fromAdditions from the additions$droppedNote.",
		)
	}
}

/**
 * Dokka configuration for the shared module API documentation.
 * The wasmJs source set is suppressed because Dokka cannot fully process Wasm targets.
 */
dokka {
	dokkaPublications.html {
		outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
	}
	pluginsConfiguration.html {
		footerMessage.set("OmniSign — shared module API reference")
	}
	dokkaSourceSets.configureEach {
		if (name.contains("wasmJs", ignoreCase = true)) {
			suppress.set(true)
		}
	}
}

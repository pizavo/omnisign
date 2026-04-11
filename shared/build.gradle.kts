import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

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
		compilerOptions.jvmTarget = JvmTarget.JVM_25
		testRuns.configureEach {
			executionTask.configure {
				useJUnitPlatform()
				jvmArgs(
					"-XX:+EnableDynamicAgentLoading",
					"-Xshare:off",
					"--enable-native-access=ALL-UNNAMED",
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
			
			api(libs.arrow.core)
			api(libs.kotlin.logging)
			implementation(libs.arrow.fx.coroutines)
			implementation(libs.kotlinx.datetime)
		}
		commonTest.dependencies {
			implementation(libs.koin.test)
			implementation(libs.kotest.engine)
			implementation(libs.kotest.core)
		}
		jvmTest.dependencies {
			implementation(libs.mockk)
			implementation(libs.kotlinx.coroutines.test)
			implementation(libs.kotest.arrow)
			implementation(libs.kotest.jvm.runner)
			implementation(libs.kotest.decoroutinator)
			implementation(libs.decoroutinator.jvm)
			implementation(libs.logback)
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
			implementation(libs.dss.pdfa)
			
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

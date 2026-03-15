import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.kotlinSerialization)
	alias(libs.plugins.ksp)
	alias(libs.plugins.kotest)
	alias(libs.plugins.decoroutinator)
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
			implementation(libs.java.keyring)
			
			implementation(libs.jackson.databind)
			implementation(libs.jackson.kotlin)
			implementation(libs.jackson.yaml)
			implementation(libs.jackson.xml)

			implementation(libs.jna)
			implementation(libs.jna.platform)
		}
	}
}


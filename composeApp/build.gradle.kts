import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * Normalizes a semver-like string to the three-component `MAJOR.MINOR.BUILD` format required by
 * Windows native installers (MSI/EXE). Pre-release suffixes (e.g. `-SNAPSHOT`) are stripped, and
 * missing components are padded with `0`.
 *
 * Examples: `"1"` → `"1.0.0"`, `"1.5"` → `"1.5.0"`, `"1.5.0-SNAPSHOT"` → `"1.5.0"`.
 */
fun String.toNativeDistributionVersion(): String {
    val parts = substringBefore("-").split(".").mapNotNull { it.toIntOrNull() }
    return listOf(
        parts.getOrElse(0) { 0 },
        parts.getOrElse(1) { 0 },
        parts.getOrElse(2) { 0 },
    ).joinToString(".")
}

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.ksp)
	alias(libs.plugins.kotest)
	alias(libs.plugins.lumo)
}

version = project.findProperty("releaseVersion")?.toString() ?: "1.0.0"

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
		testRuns.configureEach {
			executionTask.configure { useJUnitPlatform() }
		}
	}
	
	if (project.findProperty("qodanaAnalysis") == null) {
		@OptIn(ExperimentalWasmDsl::class)
		wasmJs {
			browser()
			binaries.executable()
		}
	}
	
	sourceSets {
		commonMain.dependencies {
			implementation(libs.androidx.lifecycle.viewmodelCompose)
			implementation(libs.androidx.lifecycle.runtimeCompose)
			implementation(libs.compose.runtime)
			implementation(libs.compose.foundation)
			implementation(libs.compose.material)
			implementation(libs.compose.ui)
			implementation(libs.compose.ui.tooling)
			implementation(libs.compose.components.resources)
			
			implementation(projects.shared)
			
			implementation(project.dependencies.platform(libs.koin.bom))
			implementation(libs.koin.compose)
			implementation(libs.koin.compose.viewmodel)
		}
		commonTest.dependencies {
			implementation(libs.kotest.engine)
			implementation(libs.kotest.core)
		}
		jvmTest.dependencies {
			implementation(libs.kotest.jvm.runner)
		}
		jvmMain.dependencies {
			implementation(compose.desktop.currentOs)
			implementation(libs.kotlinx.coroutines.swing)
		}
	}
}


compose.desktop {
	application {
		mainClass = "cz.pizavo.omnisign.MainKt"

		jvmArgs("--enable-native-access=ALL-UNNAMED")

		nativeDistributions {
			targetFormats(
				TargetFormat.Msi,
				TargetFormat.Exe,
				
				TargetFormat.Dmg,
				TargetFormat.Pkg,
				
				TargetFormat.Deb,
				TargetFormat.Rpm,
				
				TargetFormat.AppImage
			)
			packageName = "cz.pizavo.omnisign"
			packageVersion = project.version.toString().toNativeDistributionVersion()
		}
	}
}

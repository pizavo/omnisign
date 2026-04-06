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
	alias(libs.plugins.decoroutinator)
	alias(libs.plugins.dokka)
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
		kotlin.jvmToolchain {
			languageVersion.set(JavaLanguageVersion.of(25))
			vendor.set(JvmVendorSpec.JETBRAINS)
		}
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
			
			implementation(libs.filekit.core)
			implementation(libs.filekit.dialogs.compose)
		}
		commonTest.dependencies {
			implementation(libs.kotest.engine)
			implementation(libs.kotest.core)
		}
		jvmTest.dependencies {
			implementation(libs.kotest.jvm.runner)
			implementation(libs.kotest.arrow)
			implementation(libs.kotest.decoroutinator)
			implementation(libs.mockk)
			implementation(libs.kotlinx.coroutines.test)
		}
		jvmMain.dependencies {
			implementation(compose.desktop.currentOs)
			implementation(libs.kotlinx.coroutines.swing)
			implementation(libs.pdfbox)
			implementation(libs.decoroutinator.jvm)
			implementation(libs.jbr.api)
			implementation(libs.logback)
		}
	}
}

/**
 * Lazily resolved JBR launcher from the Gradle toolchain registry.
 *
 * Used to ensure that both compilation and the Compose Desktop `run` task
 * execute on JetBrains Runtime, which provides the Custom Title Bar API.
 */
val jbrLauncher = javaToolchains.launcherFor {
	languageVersion.set(JavaLanguageVersion.of(25))
	vendor.set(JvmVendorSpec.JETBRAINS)
}

/**
 * Eagerly resolved path to the JBR installation, or `null` when JBR is not
 * available on this machine. When `null`, the Compose Desktop `run` and
 * packaging tasks will fail at execution time with a descriptive message.
 */
val jbrHomePath: String? = try {
	jbrLauncher.map { it.metadata.installationPath.asFile.absolutePath }.get()
} catch (_: Exception) {
	null
}

compose.resources {
	generateResClass = always
}

compose.desktop {
	application {
		mainClass = "cz.pizavo.omnisign.MainKt"

		jvmArgs(
			"--enable-native-access=ALL-UNNAMED",
			"-Dsun.java2d.d3d=false"
		)

		jbrHomePath?.let { javaHome = it }

		buildTypes.release.proguard {
			isEnabled.set(false)
		}

		nativeDistributions {
			modules(
				"java.instrument",
				"java.management",
				"java.naming",
				"java.net.http",
				"java.sql",
				"java.xml",
				"java.xml.crypto",
				"jdk.unsupported",
			)
			
			if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
				modules("jdk.crypto.mscapi")
			}
			
			targetFormats(
				TargetFormat.Msi,
				TargetFormat.Exe,
				
				TargetFormat.Dmg,
				TargetFormat.Pkg,
				
				TargetFormat.Deb,
				TargetFormat.Rpm,
				
				TargetFormat.AppImage
			)
			packageName = "OmniSign"
			packageVersion = project.version.toString().toNativeDistributionVersion()
			description = "Digital signature verification, signing and re-timestamping"
			vendor = "Pizavo"

			windows {
				shortcut = true
				menu = true
				menuGroup = "OmniSign"
				dirChooser = true
				perUserInstall = true
				upgradeUuid = "e479b089-886d-4bb1-94dd-b73837d17c2c"
			}

			linux {
				shortcut = true
				menuGroup = "OmniSign"
				appCategory = "Utility"
				debMaintainer = "pizavo@gmail.com"
			}

			macOS {
				dockName = "OmniSign"
			}
		}
	}
}

gradle.taskGraph.whenReady {
	if (jbrHomePath != null) return@whenReady

	val needsJbr = allTasks.any {
		it.project.path == ":composeApp" && (
			it.name == "run" ||
			it.name == "suggestRuntimeModules" ||
			it.name.startsWith("package") ||
			it.name.contains("Distributable")
		)
	}

	if (needsJbr) {
		throw GradleException(
			buildString {
				appendLine()
				appendLine("JetBrains Runtime (JBR) 25 is required to build/run the desktop application")
				appendLine("but was not found by the Gradle toolchain resolver.")
				appendLine()
				appendLine("Install it via one of:")
				appendLine("  • IntelliJ IDEA → Settings → Build → Build Tools → Gradle → Gradle JDK")
				appendLine("  • Download from https://github.com/JetBrains/JetBrainsRuntime/releases")
				appendLine("    and place it under ~/.jdks/ so Gradle auto-detects it.")
			}
		)
	}
}

/**
 * Dokka configuration for the composeApp module API documentation.
 * The wasmJs source set is suppressed because Dokka cannot fully process Wasm targets.
 */
dokka {
	dokkaPublications.html {
		outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
	}
	pluginsConfiguration.html {
		footerMessage.set("OmniSign — composeApp module API reference")
	}
	dokkaSourceSets.configureEach {
		if (name.contains("wasmJs", ignoreCase = true) || name.contains("web", ignoreCase = true)) {
			suppress.set(true)
		}
	}
}


import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

/**
 * Resolves the platform-native crash-dump directory at Gradle evaluation time.
 *
 * Mirrors the runtime `resolveLogDir()` logic in `main.kt` so that `-XX:ErrorFile`
 * points to a `crashes/` subdirectory of the application log folder. Used only by the
 * `:run` task — packaged builds intentionally omit `-XX:ErrorFile` because the path
 * resolved here is the build host's `user.home`, which would never match the path on
 * an end-user's machine. Without the flag, HotSpot falls back to writing crash dumps
 * next to the launcher, which is correct on the target.
 */
fun resolveCrashDir(): String {
	val userHome = System.getProperty("user.home")
	val os = System.getProperty("os.name").lowercase()
	val logDir = when {
		os.contains("win") ->
			File(System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local", "omnisign/logs")
		os.contains("mac") ->
			File(userHome, "Library/Logs/omnisign")
		else ->
			File(System.getenv("XDG_STATE_HOME") ?: "$userHome/.local/state", "omnisign")
	}
	return File(logDir, "crashes").absolutePath
}

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

/**
 * JVM arguments shared by every way the desktop app is launched: the Compose
 * `application` `run` task, the Compose Hot Reload `hotRunJvm` / `hotRunJvmAsync`
 * tasks (what the IDE "Run with Compose Hot Reload" gutter invokes), and the packaged
 * distribution.  Kept as a single source so the Hot Reload run can never silently
 * drift from `run` and drop `--add-opens` / `--enable-native-access` — that drift is
 * exactly what left the JDK PC/SC stale-context recovery unable to work under hot
 * reload.
 */
val desktopJvmArgs: List<String> = buildList {
	add("--enable-native-access=ALL-UNNAMED")
	add("--add-modules=java.smartcardio")
	add("--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED")
	add("-Dsun.java2d.d3d=false")
	add("-Dsun.awt.wmclass=OmniSign")
	if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
		add("--add-opens")
		add("java.desktop/sun.awt.X11=ALL-UNNAMED")
	}
}

compose.desktop {
	application {
		mainClass = "cz.pizavo.omnisign.MainKt"

		jvmArgs(*desktopJvmArgs.toTypedArray())

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
				"java.smartcardio",
				"java.sql",
				"java.xml",
				"java.xml.crypto",
				"jdk.unsupported",
			)
			
			if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
				modules("jdk.crypto.mscapi")
			}

			if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
				modules("jdk.security.auth")
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
			copyright = "Copyright (C) 2026 Pizavo"
			licenseFile.set(rootProject.file("LICENSE.md"))

			appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

			windows {
				iconFile.set(rootProject.file("assets/icons/omnisign-logo.ico"))
				shortcut = true
				menu = true
				menuGroup = "OmniSign"
				dirChooser = true
				perUserInstall = true
				upgradeUuid = "e479b089-886d-4bb1-94dd-b73837d17c2c"
			}

			linux {
				iconFile.set(rootProject.file("assets/icons/omnisign-logo-512.png"))
				shortcut = true
				menuGroup = "Utility"
				packageName = "omnisign"
				appCategory = "utils"
				debMaintainer = "pizavo@gmail.com"
				rpmLicenseType = "AGPL-3.0-or-later"
			}

			macOS {
				iconFile.set(rootProject.file("assets/icons/omnisign-logo.icns"))
				dockName = "OmniSign"
				bundleID = "cz.pizavo.omnisign.desktop"
				appCategory = "public.app-category.utilities"
			}
		}
	}
}

/**
 * Injects additional jpackage metadata arguments into every Compose Desktop packaging task.
 * The Compose Gradle plugin does not expose DSL properties for about-url, Windows help/update
 * URLs, Linux package dependencies, or jpackage's `--resource-dir`, so the underlying
 * [AbstractJPackageTask.freeArgs] list is used to pass them to jpackage. Tasks that produce
 * app-images (AppImage and Distributable) are excluded because jpackage rejects installer-only
 * options such as `--about-url` when `--type app-image` is used.
 *
 * On Linux, two extra flags are appended:
 *  - `--linux-package-deps "xdg-utils"` declares the runtime requirement so the post-install
 *    scripts (`xdg-desktop-menu install`, `xdg-mime install`) succeed on minimal RPM-based
 *    systems. The DEB target has it via the Compose plugin's auto-injected dependency list;
 *    the RPM does not.
 *  - `--resource-dir packaging/linux` points jpackage at a custom `OmniSign.desktop` template
 *    that adds `StartupWMClass=OmniSign`, `Exec=APPLICATION_LAUNCHER %U`, and
 *    `StartupNotify=true` — keys jpackage cannot emit on its own (tracked upstream as
 *    JetBrains YouTrack CMP-8559, closed "As designed"). The Compose plugin already passes
 *    its own `--resource-dir` pointing at `build/compose/tmp/resources/`, but jpackage
 *    resolves duplicate `--resource-dir` arguments last-wins via
 *    `DeployParams.addBundleArgument` (LinkedHashMap.put), and the plugin's `clearDirs`
 *    call only touches its own path, so the override file is preserved.
 */
afterEvaluate {
	tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
		if (name.contains("AppImage", ignoreCase = true) || name.contains("Distributable", ignoreCase = true)) return@configureEach

		freeArgs.addAll(
			"--about-url", "https://pizavo.github.io/omnisign/desktop/",
			"--file-associations", project.file("file-associations/pdf.properties").absolutePath,
		)
		if (name.contains("Msi", ignoreCase = true) || name.contains("Exe", ignoreCase = true)) {
			freeArgs.addAll(
				"--win-help-url", "https://pizavo.github.io/omnisign/desktop/",
				"--win-update-url", "https://github.com/pizavo/omnisign/releases",
			)
		}
		if (name.contains("Deb", ignoreCase = true) || name.contains("Rpm", ignoreCase = true)) {
			freeArgs.addAll("--linux-package-deps", "xdg-utils")
			freeArgs.addAll("--resource-dir", project.file("packaging/linux").absolutePath)
		}
	}
}

/**
 * Adds `-XX:ErrorFile` to the `:run` JavaExec task only. The path is resolved from the
 * Gradle-running user's `user.home`, which on a developer's machine matches the runtime
 * user. Packaged jpackage builds intentionally omit this option (see [resolveCrashDir]).
 */
afterEvaluate {
	tasks.matching { it.name == "run" }.configureEach {
		(this as? JavaExec)?.jvmArgs("-XX:ErrorFile=${resolveCrashDir()}/hs_err_pid%p.log")
	}
}

/**
 * Compose Hot Reload's `hotRunJvm` task — `org.jetbrains.compose.reload.gradle.ComposeHotRun`,
 * a `JavaExec` — is what the IDE "Run with Compose Hot Reload" gutter and the
 * checked-in `.run/composeApp [Hot Reload]` configuration invoke.  It forks the
 * application JVM itself and does **not** inherit `compose.desktop.application`
 * `jvmArgs`, so without this it launches the app without `--add-opens` /
 * `--enable-native-access`, silently disabling JDK PC/SC stale-context recovery and
 * native access under hot reload.  Give it the same [desktopJvmArgs] as every other
 * launch path.
 *
 * Only the synchronous `hotRunJvm` is configured: its `[Async]` sibling
 * `hotRunJvmAsync` is a different type (`ComposeHotAsyncRun`, not a `JavaExec`) that
 * the IDE gutter does not use.  The non-null `as JavaExec` cast is deliberate — if a
 * future Compose release changes `hotRunJvm`'s type this fails the build loudly
 * rather than silently regressing the flags again.
 */
afterEvaluate {
	tasks.matching { it.name == "hotRunJvm" }.configureEach {
		(this as JavaExec).jvmArgs(desktopJvmArgs)
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
 * On Linux, installs two user-local `.desktop` entries before every `:composeApp:run`.
 *
 * GNOME Shell (Wayland) matches XWayland windows to apps via two mechanisms:
 *  1. **`StartupWMClass` index** — only rebuilt at session login on Wayland.
 *  2. **Name lookup**: `appSys.lookup_app("omnisign.desktop")` — lowercase of
 *     WM_CLASS (`"OmniSign"` → `"omnisign"`), backed by GLib's `GAppInfoMonitor`
 *     (inotify) and effective **without a session restart**.
 *
 * Both files are written so icon matching works whether the user has ever
 * logged out since the project was cloned. No `sudo` is required; user-level
 * entries take precedence over any system-level RPM file.
 *
 * The task is **incremental**: it is skipped entirely on subsequent runs once the
 * files are up to date, adding zero overhead to day-to-day `:run` invocations.
 */
if (org.gradle.internal.os.OperatingSystem.current().isLinux) {
	val userAppsDir = file("${System.getProperty("user.home")}/.local/share/applications")
	val iconPath = rootProject.file("assets/icons/omnisign-logo-512.png").absolutePath

	val primaryContent =
		"[Desktop Entry]\n" +
		"Name=OmniSign\n" +
		"Comment=Digital signature verification, signing and re-timestamping\n" +
		"Exec=/opt/omnisign/bin/OmniSign\n" +
		"Icon=$iconPath\n" +
		"Terminal=false\n" +
		"Type=Application\n" +
		"Categories=Utility\n" +
		"MimeType=application/pdf\n" +
		"StartupWMClass=OmniSign\n" +
		"StartupNotify=true\n"

	val hiddenContent =
		"[Desktop Entry]\n" +
		"Name=OmniSign\n" +
		"Comment=OmniSign window-tracker entry — do not remove\n" +
		"Exec=true\n" +
		"Icon=$iconPath\n" +
		"Terminal=false\n" +
		"Type=Application\n" +
		"NoDisplay=true\n" +
		"StartupWMClass=OmniSign\n" +
		"StartupNotify=true\n"

	val primaryFile = userAppsDir.resolve("omnisign-OmniSign.desktop")
	val hiddenFile  = userAppsDir.resolve("omnisign.desktop")

	val installLinuxDevDesktopEntry by tasks.registering {
		group = "compose desktop"
		description =
			"Installs two user-local .desktop entries so GNOME Shell matches the running " +
			"window (WM_CLASS=OmniSign) to the correct icon when launching via :run."

		inputs.property("primaryContent", primaryContent)
		inputs.property("hiddenContent",  hiddenContent)
		outputs.files(primaryFile, hiddenFile)

		doLast {
			userAppsDir.mkdirs()
			primaryFile.writeText(primaryContent)
			hiddenFile.writeText(hiddenContent)

			try {
				ProcessBuilder("update-desktop-database", userAppsDir.absolutePath)
					.redirectErrorStream(true).start().waitFor()
			} catch (_: Exception) { /* may not be installed; inotify handles it */ }

			Thread.sleep(600)

			logger.lifecycle("Installed development .desktop entries in $userAppsDir")
		}
	}

	afterEvaluate {
		tasks.matching { it.name == "run" }.configureEach {
			dependsOn(installLinuxDevDesktopEntry)
		}
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

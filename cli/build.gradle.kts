plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.shadow)
	alias(libs.plugins.dokka)
	application
}

group = "cz.pizavo.omnisign"
version = project.findProperty("releaseVersion")?.toString() ?: "1.9.0"

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

val generateVersionProperties by tasks.registering {
	group = "build"
	description = "Generates a version.properties resource file containing the project version."
	val versionValue = project.version.toString()
	val outputDir = layout.buildDirectory.dir("generated/resources")
	outputs.dir(outputDir)
	doLast {
		val file = outputDir.get().asFile.resolve("version.properties")
		file.parentFile.mkdirs()
		file.writeText("version=$versionValue\n")
	}
}

sourceSets.main {
	resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}

tasks.named("processResources") {
	dependsOn(generateVersionProperties)
}

dependencies {
	implementation(projects.shared)
	testImplementation(libs.kotest.engine)
	testImplementation(libs.kotest.jvm.runner)
	testImplementation(libs.mockk)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.kotest.core)
	testImplementation(libs.kotest.arrow)
	testImplementation(libs.kotest.koin)
	testImplementation(libs.kotest.decoroutinator)
	
	implementation(libs.clikt)
	implementation(libs.clikt.core)
	implementation(libs.clikt.markdown)
	implementation(libs.mordant)
	
	implementation(project.dependencies.platform(libs.koin.bom))
	implementation(libs.koin.core)
	implementation(libs.koin.test)
	
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.datetime)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.logback)
	
	implementation(libs.decoroutinator.jvm)
}

tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs(
		"-XX:+EnableDynamicAgentLoading",
		"-Xshare:off",
		"--enable-native-access=ALL-UNNAMED",
	)
}

application {
	mainClass.set("cz.pizavo.omnisign.CliKt")
	applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
	applicationName = "omnisign"
}

tasks.named<JavaExec>("run") {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	if (System.getProperty("os.name", "").lowercase().contains("win")) {
		jvmArgs("--add-modules=jdk.crypto.mscapi")
	}
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
	archiveBaseName.set("omnisign")
	archiveClassifier.set("")
	archiveVersion.set(project.version.toString())
	manifest {
		attributes["Main-Class"] = "cz.pizavo.omnisign.CliKt"
		attributes["Enable-Native-Access"] = "ALL-UNNAMED"
	}
	mergeServiceFiles()
}

tasks.named<CreateStartScripts>("startScripts") {
	applicationName = "omnisign"
}

tasks.named<CreateStartScripts>("startShadowScripts") {
	applicationName = "omnisign"
	outputs.upToDateWhen { false }
	doLast {
		windowsScript.writeText(
			windowsScript.readText().replace(
				Regex("""(set DEFAULT_JVM_OPTS=)"""),
				"""$1"--add-modules=jdk.crypto.mscapi" """
			)
		)
	}
}

tasks.named("assembleShadowDist") {
	dependsOn("shadowJar")
}

tasks.register<Copy>("install") {
	group = "distribution"
	description = "Installs the omnisign shadow distribution into the build/install/omnisign directory."
	dependsOn("installShadowDist")
}


/**
 * Task that packages the CLI using jpackage to create a native installer.
 * Uses injected [ExecOperations] to remain configuration-cache compatible.
 */
abstract class JPackageTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
	/** Path to the jpackage binary. */
	@get:Input
	abstract val jpackageBin: Property<String>
	
	/** The shadow JAR to package. */
	@get:InputFile
	abstract val shadowJar: RegularFileProperty

	/** Directory that contains only the shadow JAR, passed to jpackage as {@code --input}. */
	@get:InputDirectory
	abstract val inputDir: DirectoryProperty
	
	/** jpackage --type value. */
	@get:Input
	abstract val packageType: Property<String>
	
	/** Directory jpackage writes its output into. */
	@get:OutputDirectory
	abstract val destDir: DirectoryProperty
	
	/** Icon file passed via --icon. */
	@get:InputFile
	abstract val iconFile: RegularFileProperty
	
	/** Arguments appended after the common ones (platform flags, etc.). */
	@get:Input
	abstract val extraArgs: ListProperty<String>
	
	/** Common jpackage arguments (name, version, vendor, …). */
	@get:Input
	abstract val commonArgs: ListProperty<String>
	
	/**
	 * Optional platform-specific resource directory (e.g., src/main/jpackage/win).
	 * Declared as an input, so Gradle invalidates the task whenever any file inside
	 * it changes — for example, when main.wxs or overrides.wxi are edited.
	 */
	@get:InputDirectory
	@get:Optional
	abstract val resourceDir: DirectoryProperty
	
	@TaskAction
	fun pack() {
		val jar = shadowJar.get().asFile
		val dest = destDir.get().asFile
		if (dest.exists()) {
			val isWindows = System.getProperty("os.name").lowercase().contains("win")
			execOps.exec {
				isIgnoreExitValue = true
				if (isWindows) commandLine("cmd", "/c", "rmdir", "/s", "/q", dest.absolutePath)
				else commandLine("rm", "-rf", dest.absolutePath)
			}
		}
		dest.mkdirs()
		execOps.exec {
			executable(jpackageBin.get())
			args(
				commonArgs.get() + listOf(
					"--input", inputDir.get().asFile.absolutePath,
					"--main-jar", jar.name,
					"--type", packageType.get(),
					"--dest", dest.absolutePath,
					"--icon", iconFile.get().asFile.absolutePath,
				) + extraArgs.get()
			)
		}
	}
}

val jpackageBinPath: String = "${System.getProperty("java.home")}/bin/jpackage"
val shadowJarTask = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
val shadowJarFile: Provider<RegularFile> = shadowJarTask.flatMap { it.archiveFile }
val jpackageResourcesDir: String = layout.projectDirectory.dir("src/main/jpackage").asFile.absolutePath
val iconsDir: File = rootProject.file("assets/icons")

/**
 * Copies only the shadow JAR into a dedicated staging directory used as the jpackage {@code --input}.
 * This prevents the thin CLI JAR from also appearing on the packaged application's classpath,
 * which would otherwise cause duplicate resource warnings (e.g. {@code logback.xml}).
 */
val prepareJpackageInput by tasks.registering(Copy::class) {
	group = "distribution"
	description = "Stages the shadow JAR into build/jpackage/input for use as the jpackage --input directory."
	dependsOn(shadowJarTask)
	from(shadowJarFile)
	into(layout.buildDirectory.dir("jpackage/input"))
}

val jpackageInputDir: Provider<Directory> = layout.buildDirectory.dir("jpackage/input")

/** Common jpackage arguments shared by every package type. */
val commonJpackageArgsList: List<String> = listOf(
	"--name", "omnisign",
	"--app-version", project.version.toString().toNativeDistributionVersion(),
	"--vendor", "OmniSign",
	"--description", "Multiplatform digital signature verification, signing and re-timestamping tool",
	"--copyright", "Copyright (C) 2026 Pizavo",
	"--about-url", "https://pizavo.github.io/omnisign/cli/",
	"--main-class", "cz.pizavo.omnisign.CliKt",
	"--add-modules", "java.logging,java.naming,java.desktop,java.management,java.sql,java.xml.crypto,jdk.unsupported",
	"--java-options", "--enable-native-access=ALL-UNNAMED",
	"--license-file", rootProject.file("LICENSE.md").absolutePath,
)


/** Registers a [JPackageTask] with the given [name] and platform-specific [extraArgsList]. */
fun registerJPackageTask(
	name: String,
	description: String,
	type: String,
	destSubdir: String,
	iconFile: File,
	extraArgsList: List<String>,
	resourceDirPath: String? = null,
) {
	tasks.register<JPackageTask>(name) {
		this.group = "distribution"
		this.description = description
		dependsOn("prepareJpackageInput")
		jpackageBin.set(jpackageBinPath)
		shadowJar.set(shadowJarFile)
		inputDir.set(jpackageInputDir)
		packageType.set(type)
		destDir.set(layout.buildDirectory.dir("jpackage/$destSubdir"))
		commonArgs.set(commonJpackageArgsList)
		this.iconFile.set(iconFile)
		extraArgs.set(extraArgsList)
		if (resourceDirPath != null) resourceDir.set(file(resourceDirPath))
	}
}

/**
 * Appends a [Task.doLast] action to a [JPackageTask] that renames every output file
 * with [extension] whose name starts with `omnisign` to use the given [replacement] prefix.
 */
fun renamePackageOutput(taskName: String, extension: String, replacement: String = "omnisign-cli") {
	tasks.named<JPackageTask>(taskName) {
		doLast {
			destDir.get().asFile
				.listFiles { f -> f.name.startsWith("omnisign") && f.name.endsWith(".$extension") }
				?.forEach { f -> f.renameTo(File(f.parent, f.name.replaceFirst("omnisign", replacement))) }
		}
	}
}

registerJPackageTask(
	name = "jpackageWinExe",
	description = "Packages the CLI as a self-contained Windows app-image (.exe launcher inside a directory).",
	type = "app-image",
	destSubdir = "win-image",
	iconFile = File(iconsDir, "omnisign-logo-cli.ico"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/win",
		"--win-console",
		"--add-modules", "jdk.crypto.mscapi",
		"--java-options", "--add-modules=jdk.crypto.mscapi",
	),
	resourceDirPath = "$jpackageResourcesDir/win",
)

registerJPackageTask(
	name = "jpackageWinMsi",
	description = "Packages the CLI as a Windows MSI installer with start-menu entry and dir chooser.",
	type = "msi",
	destSubdir = "win-msi",
	iconFile = File(iconsDir, "omnisign-logo-cli.ico"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/win",
		"--win-per-user-install",
		"--win-dir-chooser",
		"--win-menu",
		"--win-menu-group", "omnisign",
		"--win-shortcut",
		"--win-console",
		"--win-help-url", "https://pizavo.github.io/omnisign/cli/",
		"--win-update-url", "https://github.com/pizavo/omnisign/releases",
		"--add-modules", "jdk.crypto.mscapi",
		"--java-options", "--add-modules=jdk.crypto.mscapi",
	),
	resourceDirPath = "$jpackageResourcesDir/win",
)
renamePackageOutput("jpackageWinMsi", "msi")

registerJPackageTask(
	name = "jpackageDeb",
	description = "Packages the CLI as a Debian/Ubuntu .deb package with /usr/local/bin symlink.",
	type = "deb",
	destSubdir = "linux-deb",
	iconFile = File(iconsDir, "omnisign-logo-cli-512.png"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/linux-deb",
		"--linux-app-category", "utils",
		"--linux-deb-maintainer", "pizavo@gmail.com",
	),
	resourceDirPath = "$jpackageResourcesDir/linux-deb",
)
renamePackageOutput("jpackageDeb", "deb")

registerJPackageTask(
	name = "jpackageRpm",
	description = "Packages the CLI as a Red Hat/Fedora .rpm package with /usr/local/bin symlink.",
	type = "rpm",
	destSubdir = "linux-rpm",
	iconFile = File(iconsDir, "omnisign-logo-cli-512.png"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/linux-rpm",
		"--linux-app-category", "utils",
		"--linux-rpm-license-type", "AGPLv3+",
	),
	resourceDirPath = "$jpackageResourcesDir/linux-rpm",
)
renamePackageOutput("jpackageRpm", "rpm")

registerJPackageTask(
	name = "jpackageLinuxImage",
	description =
		"Packages the CLI as a portable Linux app-image directory (for Arch and other non-DEB/RPM distributions).",
	type = "app-image",
	destSubdir = "linux-image",
	iconFile = File(iconsDir, "omnisign-logo-cli-512.png"),
	extraArgsList = emptyList(),
)

registerJPackageTask(
	name = "jpackageDmg",
	description = "Packages the CLI as a macOS .dmg disk image.",
	type = "dmg",
	destSubdir = "mac",
	iconFile = File(iconsDir, "omnisign-logo-cli.icns"),
	extraArgsList = listOf(
		"--mac-package-identifier", "cz.pizavo.omnisign.cli",
		"--mac-package-name", "omnisign",
		"--mac-app-category", "public.app-category.utilities",
	),
)
renamePackageOutput("jpackageDmg", "dmg")

registerJPackageTask(
	name = "jpackagePkg",
	description = "Packages the CLI as a macOS .pkg installer with /usr/local/bin symlink and uninstaller.",
	type = "pkg",
	destSubdir = "mac-pkg",
	iconFile = File(iconsDir, "omnisign-logo-cli.icns"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/mac",
		"--mac-package-identifier", "cz.pizavo.omnisign.cli",
		"--mac-package-name", "omnisign",
		"--mac-app-category", "public.app-category.utilities",
	),
	resourceDirPath = "$jpackageResourcesDir/mac",
)
renamePackageOutput("jpackagePkg", "pkg")

/**
 * Lifecycle task that runs the appropriate jpackage task(s) for the current OS.
 * On Windows: produces both the app-image (.exe) and the .msi installer.
 * On Linux:   produces .deb, .rpm, and a portable app-image tarball.
 * On macOS:   produces a .dmg and a .pkg installer.
 */
tasks.register("jpackage") {
	group = "distribution"
	description = "Produces the native installer(s) for the current operating system."
	val os = System.getProperty("os.name").lowercase()
	when {
		os.contains("win") -> dependsOn("jpackageWinExe", "jpackageWinMsi")
		os.contains("linux") -> dependsOn("jpackageDeb", "jpackageRpm", "jpackageLinuxImage")
		os.contains("mac") -> dependsOn("jpackageDmg", "jpackagePkg")
	}
}

/**
 * Dokka configuration for the CLI module API documentation.
 */
dokka {
	dokkaPublications.html {
		outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
	}
	pluginsConfiguration.html {
		footerMessage.set("OmniSign — CLI module API reference")
	}
}

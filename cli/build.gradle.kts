plugins {
	alias(libs.plugins.kotlinJvm)
	alias(libs.plugins.kotlinSerialization)
	alias(libs.plugins.shadow)
	application
}

group = "cz.pizavo.omnisign"
version = project.findProperty("releaseVersion")?.toString() ?: "1.6.0"

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
	testImplementation(libs.decoroutinator.jvm)
	
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
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
	archiveBaseName.set("omnisign")
	archiveClassifier.set("")
	archiveVersion.set(project.version.toString())
	manifest {
		attributes["Main-Class"] = "cz.pizavo.omnisign.CliKt"
	}
	mergeServiceFiles()
}

tasks.named<CreateStartScripts>("startScripts") {
	applicationName = "omnisign"
}

tasks.named<CreateStartScripts>("startShadowScripts") {
	applicationName = "omnisign"
	outputs.upToDateWhen { false }
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
 * Task that converts an SVG to a Windows multi-resolution .ico via ImageMagick.
 * Uses injected [ExecOperations] to remain configuration-cache compatible.
 */
abstract class SvgToIcoTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
	/** The source SVG file. */
	@get:InputFile
	abstract val sourceSvg: RegularFileProperty
	
	/** The output .ico file. */
	@get:OutputFile
	abstract val outputIco: RegularFileProperty
	
	@TaskAction
	fun convert() {
		outputIco.get().asFile.parentFile.mkdirs()
		execOps.exec {
			commandLine(
				"magick", sourceSvg.get().asFile.absolutePath,
				"-define", "icon:auto-resize=256,128,64,48,32,16",
				outputIco.get().asFile.absolutePath,
			)
		}
	}
}

/**
 * Task that converts an SVG to a PNG via rsvg-convert (librsvg).
 * Uses injected [ExecOperations] to remain configuration-cache compatible.
 */
abstract class SvgToPngTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
	/** The source SVG file. */
	@get:InputFile
	abstract val sourceSvg: RegularFileProperty
	
	/** The output PNG file. */
	@get:OutputFile
	abstract val outputPng: RegularFileProperty
	
	/** Target size in pixels (width and height). */
	@get:Input
	abstract val size: Property<Int>
	
	@TaskAction
	fun convert() {
		outputPng.get().asFile.parentFile.mkdirs()
		val s = size.get()
		execOps.exec {
			commandLine(
				"rsvg-convert",
				"-w", s.toString(), "-h", s.toString(),
				sourceSvg.get().asFile.absolutePath,
				"-o", outputPng.get().asFile.absolutePath,
			)
		}
	}
}

/**
 * Task that converts an SVG to a macOS .icns bundle via rsvg-convert and iconutil.
 * Uses injected [ExecOperations] to remain configuration-cache compatible.
 */
abstract class SvgToIcnsTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
	/** The source SVG file. */
	@get:InputFile
	abstract val sourceSvg: RegularFileProperty
	
	/** Directory into which omnisign.icns (and the intermediate .iconset) are written. */
	@get:OutputDirectory
	abstract val outputDir: DirectoryProperty
	
	@TaskAction
	fun convert() {
		val icDir = outputDir.get().asFile.also { it.mkdirs() }
		val iconsetDir = File(icDir, "omnisign.iconset").also { it.mkdirs() }
		val svg = sourceSvg.get().asFile.absolutePath
		listOf(16, 32, 64, 128, 256, 512).forEach { size ->
			execOps.exec {
				commandLine(
					"rsvg-convert",
					"-w", size.toString(), "-h", size.toString(),
					svg, "-o", File(iconsetDir, "icon_${size}x${size}.png").absolutePath,
				)
			}
			execOps.exec {
				commandLine(
					"rsvg-convert",
					"-w", (size * 2).toString(), "-h", (size * 2).toString(),
					svg, "-o", File(iconsetDir, "icon_${size}x${size}@2x.png").absolutePath,
				)
			}
		}
		execOps.exec {
			commandLine(
				"iconutil", "-c", "icns",
				iconsetDir.absolutePath,
				"-o", File(icDir, "omnisign.icns").absolutePath,
			)
		}
	}
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
	 * Declared as an input so Gradle invalidates the task whenever any file inside
	 * it changes — for example when main.wxs or overrides.wxi are edited.
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
					"--input", jar.parentFile.absolutePath,
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
val generatedIconsDir: Provider<Directory> = layout.buildDirectory.dir("jpackage/icons")
val sourceSvgFile: RegularFile = layout.projectDirectory.file("src/main/jpackage/omnisign.svg")

/** Common jpackage arguments shared by every package type. */
val commonJpackageArgsList: List<String> = listOf(
	"--name", "omnisign",
	"--app-version", project.version.toString().toNativeDistributionVersion(),
	"--vendor", "OmniSign",
	"--description", "Multiplatform digital signature verification, signing and re-timestamping tool",
	"--main-class", "cz.pizavo.omnisign.CliKt",
	"--java-options", "--enable-native-access=ALL-UNNAMED",
)

/**
 * Converts the source SVG to a Windows multi-resolution .ico using ImageMagick.
 */
tasks.register<SvgToIcoTask>("convertIconIco") {
	group = "distribution"
	description = "Converts omnisign.svg → build/jpackage/icons/omnisign.ico via ImageMagick."
	sourceSvg.set(sourceSvgFile)
	outputIco.set(generatedIconsDir.map { it.file("omnisign.ico") })
}

/**
 * Converts the source SVG to a 512×512 PNG for Linux packages using rsvg-convert.
 */
tasks.register<SvgToPngTask>("convertIconPng") {
	group = "distribution"
	description = "Converts omnisign.svg → build/jpackage/icons/omnisign.png via rsvg-convert."
	sourceSvg.set(sourceSvgFile)
	outputPng.set(generatedIconsDir.map { it.file("omnisign.png") })
	size.set(512)
}

/**
 * Converts the source SVG to a macOS .icns bundle using rsvg-convert and iconutil.
 * Only meaningful when building on macOS (iconutil is a macOS-only tool).
 */
tasks.register<SvgToIcnsTask>("convertIconIcns") {
	group = "distribution"
	description = "Converts omnisign.svg → build/jpackage/icons/omnisign.icns via rsvg-convert + iconutil (macOS only)."
	sourceSvg.set(sourceSvgFile)
	outputDir.set(generatedIconsDir)
}

/**
 * Lifecycle task that converts the source SVG to all required icon formats.
 * On macOS the .icns is additionally produced by the convertIconIcns task.
 */
tasks.register("convertIcons") {
	group = "distribution"
	description = "Converts src/main/jpackage/omnisign.svg into all platform icon formats."
	val os = System.getProperty("os.name").lowercase()
	if (os.contains("mac")) {
		dependsOn("convertIconIco", "convertIconPng", "convertIconIcns")
	} else {
		dependsOn("convertIconIco", "convertIconPng")
	}
}

/** Registers a [JPackageTask] with the given [name] and platform-specific [extraArgsList]. */
fun registerJPackageTask(
	name: String,
	description: String,
	type: String,
	destSubdir: String,
	iconFile: Provider<RegularFile>,
	iconDependency: String,
	extraArgsList: List<String>,
	resourceDirPath: String? = null,
) {
	tasks.register<JPackageTask>(name) {
		this.group = "distribution"
		this.description = description
		dependsOn("shadowJar", iconDependency)
		jpackageBin.set(jpackageBinPath)
		shadowJar.set(shadowJarFile)
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
 * with [extension] whose name starts with `omnisign` to use the `omnisign-cli` prefix.
 */
fun renamePackageOutput(taskName: String, extension: String) {
	tasks.named<JPackageTask>(taskName) {
		doLast {
			destDir.get().asFile
				.listFiles { f -> f.name.startsWith("omnisign") && f.name.endsWith(".$extension") }
				?.forEach { f -> f.renameTo(File(f.parent, f.name.replaceFirst("omnisign", "omnisign-cli"))) }
		}
	}
}

registerJPackageTask(
	name = "jpackageWinExe",
	description = "Packages the CLI as a self-contained Windows app-image (.exe launcher inside a directory).",
	type = "app-image",
	destSubdir = "win-image",
	iconFile = generatedIconsDir.map { it.file("omnisign.ico") },
	iconDependency = "convertIconIco",
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/win",
		"--win-console",
	),
	resourceDirPath = "$jpackageResourcesDir/win",
)

registerJPackageTask(
	name = "jpackageWinMsi",
	description = "Packages the CLI as a Windows MSI installer with start-menu entry and dir chooser.",
	type = "msi",
	destSubdir = "win-msi",
	iconFile = generatedIconsDir.map { it.file("omnisign.ico") },
	iconDependency = "convertIconIco",
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/win",
		"--win-dir-chooser",
		"--win-menu",
		"--win-menu-group", "omnisign",
		"--win-shortcut",
		"--win-console",
	),
	resourceDirPath = "$jpackageResourcesDir/win",
)
renamePackageOutput("jpackageWinMsi", "msi")

registerJPackageTask(
	name = "jpackageDeb",
	description = "Packages the CLI as a Debian/Ubuntu .deb package.",
	type = "deb",
	destSubdir = "linux-deb",
	iconFile = generatedIconsDir.map { it.file("omnisign.png") },
	iconDependency = "convertIconPng",
	extraArgsList = listOf(
		"--linux-menu-group", "omnisign",
		"--linux-shortcut",
		"--linux-app-category", "utils",
	),
)
renamePackageOutput("jpackageDeb", "deb")

registerJPackageTask(
	name = "jpackageRpm",
	description = "Packages the CLI as a Red Hat/Fedora .rpm package.",
	type = "rpm",
	destSubdir = "linux-rpm",
	iconFile = generatedIconsDir.map { it.file("omnisign.png") },
	iconDependency = "convertIconPng",
	extraArgsList = listOf(
		"--linux-menu-group", "omnisign",
		"--linux-shortcut",
		"--linux-app-category", "utils",
	),
)
renamePackageOutput("jpackageRpm", "rpm")

registerJPackageTask(
	name = "jpackageLinuxImage",
	description =
		"Packages the CLI as a portable Linux app-image directory (for Arch and other non-DEB/RPM distributions).",
	type = "app-image",
	destSubdir = "linux-image",
	iconFile = generatedIconsDir.map { it.file("omnisign.png") },
	iconDependency = "convertIconPng",
	extraArgsList = emptyList(),
)

registerJPackageTask(
	name = "jpackageDmg",
	description = "Packages the CLI as a macOS .dmg disk image.",
	type = "dmg",
	destSubdir = "mac",
	iconFile = generatedIconsDir.map { it.file("omnisign.icns") },
	iconDependency = "convertIconIcns",
	extraArgsList = listOf(
		"--mac-package-identifier", "cz.pizavo.omnisign",
		"--mac-package-name", "omnisign",
	),
)
renamePackageOutput("jpackageDmg", "dmg")

/**
 * Lifecycle task that runs the appropriate jpackage task(s) for the current OS.
 * On Windows: produces both the app-image (.exe) and the .msi installer.
 * On Linux:   produces .deb, .rpm, and a portable app-image tarball.
 * On macOS:   produces a .dmg.
 */
tasks.register("jpackage") {
	group = "distribution"
	description = "Produces the native installer(s) for the current operating system."
	val os = System.getProperty("os.name").lowercase()
	when {
		os.contains("win") -> dependsOn("jpackageWinExe", "jpackageWinMsi")
		os.contains("linux") -> dependsOn("jpackageDeb", "jpackageRpm", "jpackageLinuxImage")
		os.contains("mac") -> dependsOn("jpackageDmg")
	}
}

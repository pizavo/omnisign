import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo

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
 * Compiles, tests and runs the CLI on a JDK 25 toolchain, whichever JDK runs Gradle itself.
 *
 * Without it the Kotlin plugin takes the bytecode target from the Gradle JVM, so a build started on
 * a newer JDK would emit class files that a Java 25 runtime refuses to load.
 */
kotlin {
	jvmToolchain(25)
}

/**
 * Resolves the platform-native crash-dump directory at Gradle evaluation time.
 *
 * Mirrors the desktop `resolveLogDir()` logic so that `-XX:ErrorFile` points to a
 * `crashes/` subdirectory of the application log folder.  The CLI does not write
 * application logs to files, but crash dumps share the same parent structure for
 * discoverability.  For `gradle run` the path is always correct; for jpackage
 * distributions, the JVM falls back to `/tmp` if the baked path is not writable.
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

val generateVersionProperties = tasks.register("generateVersionProperties") {
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
	testImplementation(libs.koin.test)
	testImplementation(libs.kotest.decoroutinator)

	// CliSigningPipelineE2ETest drives the real signing/archiving pipeline through the CLI commands,
	// so its test classpath needs DSS + PDFBox (and DSS's transitive BouncyCastle). The production CLI
	// code stays DSS-agnostic; these are test-only.
	testImplementation(project.dependencies.platform(libs.dss.bom))
	testImplementation(libs.dss.model)
	testImplementation(libs.dss.pades)
	testImplementation(libs.dss.pades.pdfbox)
	testImplementation(libs.dss.service)
	testImplementation(libs.dss.validation)
	testImplementation(libs.dss.token)
	testImplementation(libs.pdfbox)

	implementation(libs.clikt)
	implementation(libs.clikt.core)
	implementation(libs.clikt.markdown)
	implementation(libs.mordant)
	
	implementation(project.dependencies.platform(libs.koin.bom))
	implementation(libs.koin.core)

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
		"--add-modules=java.smartcardio",
		"--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED",
	)
}

application {
	mainClass.set("cz.pizavo.omnisign.CliKt")
	applicationDefaultJvmArgs = listOf(
		"--enable-native-access=ALL-UNNAMED",
		"--add-modules=java.smartcardio",
		"--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED",
		"-XX:ErrorFile=${resolveCrashDir()}/hs_err_pid%p.log",
	)
	applicationName = "omnisign"
}

tasks.named<JavaExec>("run") {
	jvmArgs(
		"--enable-native-access=ALL-UNNAMED",
		"--add-modules=java.smartcardio",
		"--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED",
		"-XX:ErrorFile=${resolveCrashDir()}/hs_err_pid%p.log",
	)
	if (System.getProperty("os.name", "").lowercase().contains("win")) {
		jvmArgs("--add-modules=jdk.crypto.mscapi")
	}
	// Pass the parent terminal's stdin to the application so interactive prompts
	// (PIN entry via Mordant.promptSecret) can read user input.  Without this
	// gradle's JavaExec leaves stdin disconnected and the prompt sees EOF immediately.
	standardInput = System.`in`
}

/**
 * Legal files bundled into every CLI package under `META-INF/legal/`.
 *
 * Merging ~180 dependency jars into one archive collapses their `META-INF/LICENSE`
 * and `META-INF/NOTICE` entries onto a single path, so only whichever jar is
 * copied first survives and every other library's notice is silently dropped.
 * Those ambiguous paths are therefore excluded outright and replaced by the
 * aggregate that `:generateThirdPartyNotices` produces: `THIRD-PARTY.md` carries
 * every component's licence, copyright and verbatim upstream `NOTICE` text, and
 * `licenses/` carries the full text of each licence named there.
 */
val bundledLegalFiles: List<File> = listOf(
	rootProject.file("LICENSE.md"),
	rootProject.file("NOTICE.md"),
	rootProject.file("THIRD-PARTY.md"),
)

/** Directory holding the full text of every licence referenced by [bundledLegalFiles]. */
val bundledLicenseTexts: File = rootProject.file("licenses")

/**
 * Licence text the native installers ask the user to accept: OmniSign's own AGPL terms
 * prefixed with a summary of the third-party components the package installs. Produced by
 * the root `:generateInstallerLicense` task, which every jpackage task depends on.
 */
val installerLicenseFile: File = rootProject.layout.buildDirectory
	.file("legal/installer-license-cli.md").get().asFile

/**
 * Builds the fat JAR that is both published on its own and handed to jpackage.
 *
 * The manifest carries the two JVM flags `java -jar` cannot be given any other way, so running the
 * published JAR directly behaves like the native launchers, which pass the same flags as
 * `--java-options`: `Enable-Native-Access` for the native libraries, and `Add-Opens` for the
 * JDK-internal `sun.security.smartcardio` package that PC/SC stale-context recovery reflects into.
 */
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
	archiveBaseName.set("omnisign")
	archiveClassifier.set("")
	archiveVersion.set(project.version.toString())
	manifest {
		attributes["Main-Class"] = "cz.pizavo.omnisign.CliKt"
		attributes["Enable-Native-Access"] = "ALL-UNNAMED"
		attributes["Add-Opens"] = "java.smartcardio/sun.security.smartcardio"
	}
	mergeServiceFiles()
	filesMatching("META-INF/services/**") {
		duplicatesStrategy = DuplicatesStrategy.INCLUDE
	}
	filesMatching("META-INF/*.kotlin_module") {
		duplicatesStrategy = DuplicatesStrategy.INCLUDE
	}

	exclude(
		"META-INF/LICENSE",
		"META-INF/LICENSE.txt",
		"META-INF/LICENSE.md",
		"META-INF/NOTICE",
		"META-INF/NOTICE.txt",
		"META-INF/NOTICE.md",
		"META-INF/DEPENDENCIES",
		"META-INF/LGPL2.1",
		"META-INF/AL2.0",
		"META-INF/thirdparty-LICENSE",
		"META-INF/FastDoubleParser-LICENSE",
		"META-INF/FastDoubleParser-NOTICE",
	)

	from(bundledLegalFiles) { into("META-INF/legal") }
	from(bundledLicenseTexts) { into("META-INF/legal/licenses") }
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
	
	/**
	 * Icon file passed via `--icon`; the Linux packages set none.
	 *
	 * On Linux, jpackage turns a custom icon into a desktop entry, and the entry into an `xdg-utils`
	 * dependency and install scripts that register it with `xdg-desktop-menu`. That command fails on
	 * hosts with no applications directory, such as servers and containers, and a command-line tool
	 * has no use for a desktop entry anyway.
	 */
	@get:InputFile
	@get:Optional
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

	/**
	 * App-image the package's shared-library requirements are read from; see
	 * [sharedLibraryRequirements].
	 *
	 * Only the RPM sets it, to the image `jpackageLinuxImage` builds. jpackage assembles the RPM from
	 * the same jar, modules and launcher options, so that image holds exactly the native code the
	 * package installs.
	 */
	@get:InputDirectory
	@get:Optional
	abstract val libraryScanImage: DirectoryProperty

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
		val libraryRequirements = libraryScanImage.orNull?.asFile?.let(::sharedLibraryRequirements).orEmpty()
		val requirementArgs = if (libraryRequirements.isEmpty()) emptyList() else {
			logger.lifecycle("Requiring ${libraryRequirements.size} shared libraries: ${libraryRequirements.joinToString()}")
			listOf("--linux-package-deps", libraryRequirements.joinToString(", "))
		}
		val iconArgs = iconFile.orNull?.let { listOf("--icon", it.asFile.absolutePath) }.orEmpty()
		execOps.exec {
			executable(jpackageBin.get())
			args(
				commonArgs.get() + listOf(
					"--input", inputDir.get().asFile.absolutePath,
					"--main-jar", jar.name,
					"--type", packageType.get(),
					"--dest", dest.absolutePath,
				) + iconArgs + extraArgs.get() + requirementArgs
			)
		}
		if (packageType.get() == "deb") {
			dest.listFiles { f -> f.extension == "deb" }?.forEach(::amendDeb)
		}
	}

	/**
	 * Adds to a [deb] jpackage produced what jpackage has no option for, rebuilding it in place.
	 *
	 * - **`/usr/bin/omnisign`**, a symlink to the launcher, which puts the command on every user's
	 *   `PATH`. Shipped inside the archive rather than created by a maintainer script, it is dpkg's to
	 *   track: `dpkg -S` names its owner, it goes away with the package, and a clash with another
	 *   package's file stops the installation instead of overwriting that file. Up to 1.21.1 the
	 *   `postinst` created `/usr/local/bin/omnisign` instead, which Debian Policy §9.1.2 forbids,
	 *   because `/usr/local` belongs to the administrator. Those versions' `prerm` deletes that link
	 *   on upgrade, so nothing is left behind.
	 * - **Alternatives in `Depends` across Debian's 64-bit `time_t` transition.** jpackage maps every
	 *   library the bundled runtime links against to the package that owns it on the build host. The
	 *   release runners are past the transition, where several of those packages carry a `t64`
	 *   suffix (`libasound2t64`) that Ubuntu 22.04 and Debian 12 never had, so `apt` refused the
	 *   package there. Each such entry becomes an alternative with its pre-transition name
	 *   (`libasound2t64 | libasound2`), which both generations satisfy.
	 *
	 * The archive is unpacked and rebuilt with `dpkg-deb`, which jpackage itself needs to build a DEB,
	 * so it is always on the host; file contents and modes are carried over unchanged. The desktop
	 * app's `LinuxInstallerTask` applies the same `Depends` rewrite to its own DEB.
	 */
	private fun amendDeb(deb: File) {
		val root = temporaryDir.resolve("deb-root").apply { deleteRecursively() }
		execOps.exec { commandLine("dpkg-deb", "--raw-extract", deb.absolutePath, root.absolutePath) }
		val launcher = Path("/opt/omnisign-cli/bin/omnisign")
		check(root.resolve(launcher.toString().removePrefix("/")).isFile) { "$deb has no launcher at $launcher" }
		root.toPath().resolve("usr/bin").createDirectories().resolve("omnisign").createSymbolicLinkPointingTo(launcher)
		val control = root.resolve("DEBIAN/control")
		control.writeText(
			control.readLines().joinToString(separator = "\n", postfix = "\n") { line ->
				if (!line.startsWith("Depends:")) return@joinToString line
				line.removePrefix("Depends:").split(",").map(String::trim).filter(String::isNotEmpty)
					.joinToString(separator = ", ", prefix = "Depends: ") {
						if (it.endsWith("t64")) "$it | ${it.removeSuffix("t64")}" else it
					}
			}
		)
		execOps.exec {
			commandLine("dpkg-deb", "--root-owner-group", "--build", root.absolutePath, deb.absolutePath)
		}
	}

	/**
	 * Lists the shared libraries the native code under [image] links against without bundling them,
	 * as the RPM capabilities that provide them, such as `libasound.so.2()(64bit)`.
	 *
	 * jpackage looks up which package provides each library only for the host's own package format.
	 * The release runners are Debian-based, so it skips the lookup for the RPM, logging nothing but a
	 * misleading `app-image dir not generated by jpackage`, and the package required only
	 * `xdg-utils`. An RPM host would not fix that either, as it would yield package names, which
	 * differ between distributions (`alsa-lib` on Fedora, `libasound2` on openSUSE). A soname
	 * capability does not: every RPM distribution provides one for each library it packages, and it
	 * is what `rpmbuild` itself records when it scans a package.
	 *
	 * The names come from the `DT_NEEDED` entries of every ELF object in the image, minus the
	 * libraries the image ships itself, matched by file name and by `DT_SONAME`. Libraries opened
	 * with `dlopen`, such as the PC/SC library behind `java.smartcardio`, have no such entry, so
	 * neither this scan nor jpackage's own lookup can see them.
	 *
	 * The desktop app's `LinuxInstallerTask` declares its own RPM's requirements the same way.
	 */
	private fun sharedLibraryRequirements(image: File): List<String> {
		val objects = image.walkTopDown().filter(File::isFile)
			.mapNotNull { file -> readDynamicLinkage(file)?.let { file.name to it } }
			.toList()
		check(objects.isNotEmpty()) { "$image contains no 64-bit ELF objects" }
		val bundled = objects.flatMap { (name, linkage) -> listOfNotNull(name, linkage.soname) }.toSet()
		return objects.flatMap { (_, linkage) -> linkage.needed }
			.filterNot(bundled::contains)
			.distinct()
			.sorted()
			.map { "$it()(64bit)" }
	}

	/**
	 * Reads the dynamic-linking entries of [file], or returns `null` unless it is a little-endian
	 * 64-bit ELF object, the only kind an x86-64 or AArch64 runtime can load.
	 *
	 * The dynamic section is located through the program headers, the way the dynamic loader finds
	 * it, and the address of its string table is translated to a file offset through the `PT_LOAD`
	 * segment that maps it.
	 */
	private fun readDynamicLinkage(file: File): DynamicLinkage? = FileChannel.open(file.toPath()).use { channel ->
		val elf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()).order(ByteOrder.LITTLE_ENDIAN)
		if (elf.limit() < 64 || elf.getInt(0) != 0x464C457F || elf.get(4).toInt() != 2 || elf.get(5).toInt() != 1) {
			return@use null
		}
		val headers = List(elf.getShort(0x38).toInt() and 0xFFFF) {
			elf.getLong(0x20).toInt() + it * (elf.getShort(0x36).toInt() and 0xFFFF)
		}
		val dynamic = headers.firstOrNull { elf.getInt(it) == 2 } ?: return@use DynamicLinkage(emptyList(), null)
		val entries = generateSequence(elf.getLong(dynamic + 8).toInt()) { it + 16 }
			.map { elf.getLong(it) to elf.getLong(it + 8) }
			.takeWhile { (tag, _) -> tag != 0L }
			.toList()
		val stringTable = entries.firstOrNull { it.first == 5L }?.second
			?: return@use DynamicLinkage(emptyList(), null)
		val segment = headers.first { header ->
			elf.getInt(header) == 1 && stringTable - elf.getLong(header + 16) in 0L until elf.getLong(header + 32)
		}
		val base = stringTable - elf.getLong(segment + 16) + elf.getLong(segment + 8)
		fun string(offset: Long) = generateSequence((base + offset).toInt()) { it + 1 }
			.map { elf.get(it) }
			.takeWhile { it != 0.toByte() }
			.map { it.toInt().toChar() }
			.joinToString("")
		DynamicLinkage(
			needed = entries.filter { it.first == 1L }.map { string(it.second) },
			soname = entries.firstOrNull { it.first == 14L }?.let { string(it.second) },
		)
	}

	/**
	 * The dynamic-linking entries of one ELF object: the [needed] libraries (`DT_NEEDED`) and its
	 * own [soname] (`DT_SONAME`), if it declares one.
	 */
	private data class DynamicLinkage(val needed: List<String>, val soname: String?)
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
 *
 * The third-party legal files are staged alongside it under {@code legal/}, so the installed
 * package carries the licence of every bundled library as readable files and not only inside the
 * fat JAR. They are plain text and Markdown, so unlike a second JAR they never reach the classpath.
 */
val prepareJpackageInput = tasks.register<Copy>("prepareJpackageInput") {
	group = "distribution"
	description = "Stages the shadow JAR and the third-party legal files into build/jpackage/input."
	dependsOn(shadowJarTask)
	from(shadowJarFile)
	from(bundledLegalFiles) { into("legal") }
	from(bundledLicenseTexts) { into("legal/licenses") }
	into(layout.buildDirectory.dir("jpackage/input"))
}

val jpackageInputDir: Provider<Directory> = layout.buildDirectory.dir("jpackage/input")

/**
 * Sub-directory the Windows installer places the CLI in, under the per-user install root.
 *
 * Deliberately *not* the `--name` default. jpackage would otherwise derive the folder from the
 * launcher name and install to `%LOCALAPPDATA%\omnisign`, which on a case-insensitive filesystem is
 * the same directory the desktop app uses for `%LOCALAPPDATA%\OmniSign`. Both app images contain a
 * `runtime\` directory, so whichever was installed second replaced the other's jlinked runtime and
 * left its launcher running against a module set the bundled CDS archive no longer matched — which
 * surfaces as a JDK-internal class missing during VM initialisation rather than anything legible.
 * Mirrors what `--linux-package-name` already does for the DEB and RPM.
 */
val windowsInstallSubdir = "omnisign-cli"

/**
 * Upgrade code identifying the CLI to the Windows Installer across versions.
 *
 * Must stay fixed for upgrades and uninstalls to track the same product, and must differ from the
 * desktop app's own `upgradeUuid`; jpackage otherwise derives one that is not stable across builds.
 */
val windowsUpgradeUuid = "8dd0e055-1fb3-4664-90a4-d39e502930f3"

/**
 * Directory the macOS installers place the CLI's application bundle in.
 *
 * Same collision as on Windows, for the same reason: APFS is case-insensitive by default, so the
 * bundle this produces would otherwise sit at `/Applications/omnisign.app` alongside — that is, on
 * top of — the desktop app's `/Applications/OmniSign.app`. Nesting the CLI one level down keeps the
 * launcher named `omnisign`, so the command users type is unchanged on every platform.
 */
val macosInstallDir = "/Applications/omnisign-cli"

/**
 * Common jpackage arguments shared by every package type.
 *
 * `--vendor` matches the desktop app's, since every package format surfaces it as the publisher: the
 * Windows MSI's `Manufacturer` (the Publisher column of Installed apps, which a winget manifest's
 * `AppsAndFeaturesEntries` records), the DEB `Maintainer` name and the RPM `Vendor` tag.
 */
val commonJpackageArgsList: List<String> = listOf(
	"--name", "omnisign",
	"--app-version", project.version.toString().toNativeDistributionVersion(),
	"--vendor", "Pizavo",
	"--description", "Multiplatform digital signature verification, signing and re-timestamping tool",
	"--copyright", "Copyright (C) 2026 Pizavo",
	"--main-class", "cz.pizavo.omnisign.CliKt",
	"--add-modules", "java.logging,java.naming,java.desktop,java.management,java.smartcardio,java.sql,java.xml.crypto,jdk.crypto.cryptoki,jdk.unsupported",
	"--java-options", "--enable-native-access=ALL-UNNAMED",
	"--java-options", "--add-opens=java.smartcardio/sun.security.smartcardio=ALL-UNNAMED",
)

/** Arguments valid only for installer types (not app-image). */
val installerOnlyArgsList: List<String> = listOf(
	"--license-file", installerLicenseFile.absolutePath,
	"--about-url", "https://pizavo.github.io/omnisign/cli/",
)

/**
 * Arguments every Linux package type adds: the `jdk.security.auth` module, whose
 * `com.sun.security.auth.module.UnixSystem` dbus-java needs to log in to the session bus with the
 * user's id (D-Bus `EXTERNAL` authentication). Without it no Secret Service keyring is reachable,
 * and the CLI behaves as if none were running. The desktop app adds the same module on Linux.
 */
val linuxJpackageArgsList: List<String> = listOf("--add-modules", "jdk.security.auth")


/**
 * Registers a [JPackageTask] with the given [name] and platform-specific [extraArgsList].
 *
 * [iconFile] is left out for the Linux packages, which carry no icon; see [JPackageTask.iconFile].
 * [libraryScanTask] names the app-image task whose output the package's shared-library
 * requirements are read from, wired through [JPackageTask.libraryScanImage].
 */
fun registerJPackageTask(
	name: String,
	description: String,
	type: String,
	destSubdir: String,
	iconFile: File? = null,
	extraArgsList: List<String>,
	resourceDirPath: String? = null,
	libraryScanTask: String? = null,
) {
	tasks.register<JPackageTask>(name) {
		this.group = "distribution"
		this.description = description
		dependsOn("prepareJpackageInput")
		if (type != "app-image") dependsOn(":generateInstallerLicense")
		jpackageBin.set(jpackageBinPath)
		shadowJar.set(shadowJarFile)
		inputDir.set(jpackageInputDir)
		packageType.set(type)
		destDir.set(layout.buildDirectory.dir("jpackage/$destSubdir"))
		val effectiveCommon = if (type != "app-image") commonJpackageArgsList + installerOnlyArgsList else commonJpackageArgsList
		commonArgs.set(effectiveCommon)
		if (iconFile != null) this.iconFile.set(iconFile)
		extraArgs.set(extraArgsList)
		if (resourceDirPath != null) resourceDir.set(file(resourceDirPath))
		if (libraryScanTask != null) libraryScanImage.set(tasks.named<JPackageTask>(libraryScanTask).flatMap { it.destDir })
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
	description = "Packages the CLI as a Windows MSI installer that puts it on the PATH, with a directory chooser.",
	type = "msi",
	destSubdir = "win-msi",
	iconFile = File(iconsDir, "omnisign-logo-cli.ico"),
	extraArgsList = listOf(
		"--resource-dir", "$jpackageResourcesDir/win",
		"--win-per-user-install",
		"--win-dir-chooser",
		"--win-console",
		"--win-help-url", "https://pizavo.github.io/omnisign/cli/",
		"--win-update-url", "https://github.com/pizavo/omnisign/releases",
		"--install-dir", windowsInstallSubdir,
		"--win-upgrade-uuid", windowsUpgradeUuid,
		"--add-modules", "jdk.crypto.mscapi",
		"--java-options", "--add-modules=jdk.crypto.mscapi",
	),
	resourceDirPath = "$jpackageResourcesDir/win",
)
renamePackageOutput("jpackageWinMsi", "msi")

registerJPackageTask(
	name = "jpackageDeb",
	description = "Packages the CLI as a Debian/Ubuntu .deb package with a /usr/bin/omnisign symlink.",
	type = "deb",
	destSubdir = "linux-deb",
	extraArgsList = linuxJpackageArgsList + listOf(
		"--linux-app-category", "utils",
		"--linux-deb-maintainer", "pizavo@gmail.com",
		"--linux-package-name", "omnisign-cli",
	),
)

registerJPackageTask(
	name = "jpackageRpm",
	description = "Packages the CLI as a Red Hat/Fedora .rpm package with a /usr/bin/omnisign symlink.",
	type = "rpm",
	destSubdir = "linux-rpm",
	extraArgsList = linuxJpackageArgsList + listOf(
		"--resource-dir", "$jpackageResourcesDir/linux-rpm",
		"--linux-app-category", "utils",
		"--linux-rpm-license-type", "AGPL-3.0-or-later",
		"--linux-package-name", "omnisign-cli",
	),
	resourceDirPath = "$jpackageResourcesDir/linux-rpm",
	libraryScanTask = "jpackageLinuxImage",
)

registerJPackageTask(
	name = "jpackageLinuxImage",
	description =
		"Packages the CLI as a portable Linux app-image directory (for Arch and other non-DEB/RPM distributions).",
	type = "app-image",
	destSubdir = "linux-image",
	extraArgsList = linuxJpackageArgsList,
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
		"--install-dir", macosInstallDir,
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
		"--install-dir", macosInstallDir,
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

/**
 * Raises the Jackson 2 that Dokka 2.3.0 brings onto its generator classpath, 2.22.2, to a
 * release without its advisories. Only documentation generation runs it.
 */
dependencies {
	constraints {
		dokkaHtmlGeneratorRuntime(libs.jackson2.bom)
	}
}

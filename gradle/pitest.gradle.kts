import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Usage
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PIT and its Kotest plugin. The plugin comes without its dependencies, because the module's test runtime
 * classpath, which the `pitest` task runs on, already holds Kotest and the Kotlin standard library in the versions
 * the module uses.
 */
val pitestDependencies = configurations.dependencyScope("pitest")

/**
 * The resolved classpath of [pitestDependencies].
 */
val pitestClasspath = configurations.resolvable("pitestClasspath") {
	extendsFrom(pitestDependencies.get())
	attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME)) }
}

/**
 * The version catalog, whose `pitest` and `kotest` versions the dependencies take.
 */
val catalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
	add(pitestDependencies.name, catalog.findLibrary("pitest-command-line").get())
	addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
		pitestDependencies.name,
		catalog.findLibrary("kotest-extensions-pitest").get(),
	) { isTransitive = false }
}

/**
 * Mutation-tests the classes named by `-PpitClasses` with the specs named by `-PpitTests`, and prints the mutants
 * those specs did not kill.
 *
 * PIT changes the compiled classes one small step at a time (a negated condition, a removed call, a constant
 * return) and runs the specs that cover the changed line against each change. A change the specs still pass is a
 * behaviour they do not pin down. Both properties take comma-separated class-name globs:
 *
 * ```
 * ./gradlew :shared:pitest -PpitClasses=cz.pizavo.omnisign.data.repository.TspErrorDetector* \
 *     -PpitTests=cz.pizavo.omnisign.data.repository.TspErrorDetectorTest
 * ```
 *
 * The task runs on the module's unit test classpath, Java launcher and JVM arguments. Kotlin adds code of its own
 * that a mutant in is no finding: null checks on parameters, platform types and `!!`; code inlined from other files,
 * such as the standard library's collection functions, which keeps line numbers past the end of the file; and the
 * state machine of a suspend function, which checks each resumption and returns early when the function suspends,
 * on the line of the `suspend fun` declaration. The summary counts those mutants but does not list them, except on
 * a one-line `suspend fun … = …`, where they share the line with real code. The default-argument bridges and the
 * `componentN` and `copy` functions of data classes are not mutated at all.
 *
 * PIT tests as many mutants at once as half the machine's processors, each in a JVM of its own, and `-PpitThreads`
 * sets another number. A spec that starts the server, through `testApplication` or `embeddedServer`, runs one in
 * each of those JVMs, so for such specs keep it at three or fewer (`-PpitThreads=3`), and fewer still while other
 * specs that start the server run beside it.
 *
 * PIT's own output goes to `build/reports/pitest/pit-output.txt`, the summary to `survivors.txt` beside it, and the
 * full report to `index.html`, with every mutant in `mutations.xml`. The task fails when PIT does, and when no spec
 * matches `-PpitTests`, since PIT would then report every mutant as not covered.
 *
 * The task never runs the whole suite: PIT runs every named spec once to measure coverage, and some specs, such as
 * the scheduler ones, change the machine they run on.
 */
tasks.register<JavaExec>("pitest") {
	group = "verification"
	description = "Mutation-tests -PpitClasses with the specs in -PpitTests and prints the mutants that survived."

	val multiplatform = plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")
	val unitTests = tasks.named<Test>(if (multiplatform) "jvmTest" else "test").get()
	val sourceRoots = (if (multiplatform) listOf("src/commonMain/kotlin", "src/jvmMain/kotlin") else listOf("src/main/kotlin"))
		.map { file(it) }
		.filter { it.isDirectory }
	val mutableCode = layout.buildDirectory.dir(if (multiplatform) "classes/kotlin/jvm/main" else "classes/kotlin/main")
	val reportDirectory = layout.buildDirectory.dir("reports/pitest")
	val repositoryRoot = rootDir
	val targetClasses = providers.gradleProperty("pitClasses")
	val targetTests = providers.gradleProperty("pitTests")
	val minionJvmArgs = unitTests.jvmArgs.orEmpty()
	val threads = providers.gradleProperty("pitThreads")
		.map { text ->
			text.toIntOrNull()?.takeIf { it > 0 }
				?: throw GradleException("-PpitThreads takes a whole number above zero, not '$text'")
		}
		.orElse((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1))
	val outputTail = 30
	val moduleClasspath = unitTests.classpath
	val classpathFile = layout.buildDirectory.file("tmp/pitest/classpath.txt")

	javaLauncher.set(unitTests.javaLauncher)
	classpath(pitestClasspath)
	inputs.files(moduleClasspath).withPropertyName("moduleClasspath").withNormalizer(ClasspathNormalizer::class.java)
	mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
	argumentProviders.add(
		CommandLineArgumentProvider {
			val classes = targetClasses.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException(
				"Name the classes to mutate, e.g. -PpitClasses=cz.pizavo.omnisign.data.repository.TspErrorDetector*",
			)
			val tests = targetTests.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException(
				"Name the specs that test them, e.g. -PpitTests=cz.pizavo.omnisign.data.repository.TspErrorDetectorTest",
			)
			listOf(
				"--targetClasses=$classes",
				"--targetTests=$tests",
				"--classPathFile=${classpathFile.get().asFile}",
				"--sourceDirs=${sourceRoots.joinToString(",")}",
				"--mutableCodePaths=${mutableCode.get().asFile}",
				"--reportDir=${reportDirectory.get().asFile}",
				"--outputFormats=XML,HTML",
				"--verbosity=QUIET",
				"--timestampedReports=false",
				"--excludedMethods=*\$default,component*,copy",
				"--threads=${threads.get()}",
				"--useClasspathJar=true",
				"--jvmArgs=${minionJvmArgs.joinToString(",")}",
				"--inputEncoding=UTF-8",
				"--outputEncoding=UTF-8",
			)
		},
	)

	isIgnoreExitValue = true
	doFirst {
		val report = reportDirectory.get().asFile
		report.deleteRecursively()
		report.mkdirs()
		classpathFile.get().asFile.apply { parentFile.mkdirs() }
			.writeText(moduleClasspath.files.joinToString(System.lineSeparator()) { it.absolutePath })
		(this as JavaExec).standardOutput = report.resolve("pit-output.txt").outputStream()
	}

	doLast {
		val report = reportDirectory.get().asFile
		val pit = this as JavaExec
		pit.standardOutput.close()
		val exitValue = pit.executionResult.get().exitValue
		if (exitValue != 0) {
			val output = report.resolve("pit-output.txt")
			val tail = output.readLines().takeLast(outputTail)
			throw GradleException(
				if (tail.isEmpty()) {
					"PIT stopped with exit code $exitValue; its error is printed above."
				} else {
					"PIT stopped with exit code $exitValue. The end of its output, all of which is in " +
						"${output.relativeTo(repositoryRoot).invariantSeparatorsPath}:\n" + tail.joinToString("\n")
				},
			)
		}
		val examined = Regex(""">> (\d+) tests examined""")
			.find(report.resolve("pit-output.txt").readText())?.groupValues?.get(1)?.toInt()
		if (examined == 0) {
			throw GradleException(
				"No spec matches -PpitTests=${targetTests.get()}, so PIT ran no tests. Name specs by their fully " +
					"qualified class name, or a glob such as cz.pizavo.omnisign.data.repository.TspErrorDetector*Test.",
			)
		}
		val sourceLines = mutableMapOf<String, List<String>?>()
		val text = { mutation: Element, tag: String ->
			mutation.getElementsByTagName(tag).item(0)?.textContent.orEmpty()
		}
		val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(report.resolve("mutations.xml"))
			.getElementsByTagName("mutation")
		val statuses = sortedMapOf<String, Int>()
		val noise = sortedMapOf<String, Int>()
		val listed = mutableMapOf<String, MutableList<Triple<String, Int, String>>>()
		val redundantTarget = Regex(""" for [\w/$]+::\S+$""")
		val suspendHeader = Regex("""\bsuspend\s+fun\b[^=]*$""")

		for (index in 0 until nodes.length) {
			val mutation = nodes.item(index) as Element
			val status = mutation.getAttribute("status")
			val relativePath = text(mutation, "mutatedClass").substringBeforeLast('.', "").replace('.', '/')
				.let { if (it.isEmpty()) "" else "$it/" } + text(mutation, "sourceFile")
			val source = sourceRoots.map { it.resolve(relativePath) }.firstOrNull { it.isFile }
			val lines = source?.let { sourceLines.getOrPut(it.path) { it.readLines() } }
			val line = text(mutation, "lineNumber").toIntOrNull() ?: 0
			val description = text(mutation, "description")
			val noiseKind = when {
				"kotlin/jvm/internal/Intrinsics" in description -> "null checks"
				"kotlin/ResultKt::throwOnFailure" in description -> "coroutine state machines"
				lines != null && line > lines.size -> "inlined code"
				lines != null && line >= 1 && suspendHeader.containsMatchIn(lines[line - 1]) -> "coroutine state machines"
				else -> null
			}
			if (noiseKind != null) {
				noise.merge(noiseKind, 1) { a, b -> a + b }
				continue
			}
			statuses.merge(status, 1) { a, b -> a + b }
			if (status == "SURVIVED" || status == "NO_COVERAGE") {
				val location = source?.relativeTo(repositoryRoot)?.invariantSeparatorsPath ?: relativePath
				val change = description.replace(redundantTarget, "")
				listed.getOrPut(status) { mutableListOf() } +=
					Triple(location, line, "${text(mutation, "mutatedMethod")} — $change")
			}
		}

		val summary = buildString {
			append("PIT: ${statuses.values.sum()} mutants")
			if (statuses.isNotEmpty()) {
				append(" (")
				append(statuses.entries.joinToString(", ") { "${it.value} ${it.key.lowercase().replace('_', ' ')}" })
				append(")")
			}
			if (noise.isNotEmpty()) {
				append("; ${noise.values.sum()} more in compiler-generated code left out (")
				append(noise.entries.joinToString(", ") { "${it.value} ${it.key}" })
				append(")")
			}
			appendLine(".")
			listOf(
				"SURVIVED" to "Survived, so no spec noticed the change:",
				"NO_COVERAGE" to "Not run by any of the named specs:",
			).forEach { (status, heading) ->
				listed[status]?.sortedWith(compareBy({ it.first }, { it.second }))?.let { entries ->
					appendLine(heading)
					entries.forEach { (location, line, change) -> appendLine("  $location:$line $change") }
				}
			}
			append("Full report: ${report.resolve("index.html").relativeTo(repositoryRoot).invariantSeparatorsPath}")
		}
		report.resolve("survivors.txt").writeText(summary + "\n")
		logger.lifecycle(summary)
	}
}

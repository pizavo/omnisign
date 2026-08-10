import java.util.zip.ZipFile
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    id("java-base")
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotest) apply false
    alias(libs.plugins.decoroutinator) apply false
    alias(libs.plugins.lumo) apply false
    alias(libs.plugins.dokka)
}

/**
 * Root-level Dokka configuration that aggregates API documentation from all subprojects
 * into a single unified HTML site. Run `:dokkaGenerate` to produce the combined output.
 */
dokka {
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
    pluginsConfiguration.html {
        footerMessage.set("OmniSign — API reference")
    }
}

dependencies {
    dokka(project(":shared"))
    dokka(project(":cli"))
    dokka(project(":server"))
    dokka(project(":composeApp"))
}

/**
 * Marks a configuration as a resolvable view of one shipping module's JVM runtime
 * classpath. Depending on the module rather than reading its configurations directly
 * keeps the resolution local to this project, so the notices task stays compatible
 * with the configuration cache.
 *
 * The Kotlin platform attribute is required because `:composeApp` is a multiplatform
 * project: without it the JVM and Wasm variants are ambiguous. Nothing else may be
 * pinned here — forcing `Category` or `Bundling` makes dependency BOMs and shadowed
 * variants unresolvable.
 */
fun Configuration.asJvmRuntimeAggregate() {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
}

/**
 * Trailing OS/architecture classifier on artifacts Gradle selects for the *building* host, such as
 * `skiko-awt-runtime-windows-x64`.
 *
 * Only one variant resolves per machine, so leaving the classifier in place would make the
 * generated notices host-dependent: a file written on Windows can never match the same file checked
 * on a Linux CI runner, and the check would fail forever. Collapsing the classifier to a placeholder
 * keeps the output identical everywhere and is the more honest statement anyway — the component
 * genuinely ships as a per-platform native artifact, and every desktop release produces all of them.
 */
val HOST_VARIANT_CLASSIFIER = Regex("-(windows|linux|macos)-(x64|arm64)$")

/** Everything the CLI ships on its runtime classpath. */
val cliRuntimeClasspath: Configuration by configurations.creating { asJvmRuntimeAggregate() }

/** Everything the server ships on its runtime classpath. */
val serverRuntimeClasspath: Configuration by configurations.creating { asJvmRuntimeAggregate() }

/** Everything the desktop application ships on its runtime classpath. */
val desktopRuntimeClasspath: Configuration by configurations.creating { asJvmRuntimeAggregate() }

dependencies {
    cliRuntimeClasspath(project(":cli"))
    serverRuntimeClasspath(project(":server"))
    desktopRuntimeClasspath(project(":composeApp"))
}

/**
 * Regenerates `THIRD-PARTY.md`, the notice file that accompanies every OmniSign
 * distribution, and the credits list each package surfaces at runtime.
 *
 * That list is written twice, byte for byte identical: once as a Compose resource, which is
 * the only form the web target can read, and once into `shared`'s JVM resources, from where
 * the CLI's `credits` command and the server's `GET /api/v1/credits` read it on the classpath
 * with no packaging step of their own. Both copies are committed and both are checked, so
 * they cannot drift.
 *
 * Each shipping surface is resolved separately — the CLI, server and desktop runtime
 * classpaths here, the web one through `:composeApp:collectWebRuntimeCoordinates` plus
 * the Wasm npm lockfile — so every component records which packages actually contain it.
 * Without that split the web build would credit the whole JVM stack it cannot even load,
 * and would still miss its own npm dependencies.
 *
 * Every artifact is described using the curated facts in
 * `gradle/third-party-licenses.json`. One that matches no entry there fails the build, so
 * a dependency bump cannot quietly ship a library whose licence nobody has looked at.
 *
 * Licence texts are not scraped from published metadata, which is unreliable: roughly a
 * quarter of the artifacts either declare nothing at all or spell the same licence three
 * different ways, and several are dual-licensed, where only a human can record which
 * branch the project takes. The JSON file is therefore the source of truth and this task
 * is only the renderer.
 *
 * Run it after any dependency change and commit the result:
 * ```
 * .\gradlew generateThirdPartyNotices
 * ```
 *
 * Passing `-PnoticesCheck=true` verifies the committed files instead of rewriting
 * them, which is what CI runs.
 */
tasks.register("generateThirdPartyNotices") {
    group = "documentation"
    description = "Regenerates THIRD-PARTY.md and the runtime credits list from the curated licence data."

    dependsOn(":composeApp:collectWebRuntimeCoordinates")

    val jvmSurfaces: Map<String, Provider<Set<ResolvedArtifactResult>>> = mapOf(
        "cli" to cliRuntimeClasspath.incoming.artifacts.resolvedArtifacts,
        "server" to serverRuntimeClasspath.incoming.artifacts.resolvedArtifacts,
        "desktop" to desktopRuntimeClasspath.incoming.artifacts.resolvedArtifacts,
    )
    val webCoordinatesFile = project(":composeApp").layout.buildDirectory
        .file("notices/web-coordinates.txt")
    val npmLockFile = rootProject.file("kotlin-js-store/wasm/package-lock.json")
    val licenseData = rootProject.file("gradle/third-party-licenses.json")
    val licenseTextDir = rootProject.file("licenses")
    val noticeFile = rootProject.file("THIRD-PARTY.md")
    val creditsFile = rootProject.file(
        "composeApp/src/commonMain/composeResources/files/third-party-credits.json",
    )
    val sharedCreditsFile = rootProject.file(
        "shared/src/jvmMain/resources/third-party-credits.json",
    )
    val verifyOnly = providers.gradleProperty("noticesCheck").map { it.toBoolean() }.orElse(false)
    val surfaceOrder = listOf("cli", "server", "desktop", "web")
    val hostVariantClassifier = HOST_VARIANT_CLASSIFIER

    val docsPages: Map<String, Map<String, String>> = mapOf(
        "cli" to mapOf(
            "file" to "docs/docs-cli/credits.mdx",
            "position" to "5",
            "product" to "The OmniSign CLI",
            "location" to "The full text of every licence below is installed next to the CLI, in a `legal/licenses/` folder, and is also carried inside the executable JAR under `META-INF/legal/`. The same list is printed by the tool itself with `omnisign credits`.",
        ),
        "server" to mapOf(
            "file" to "docs/docs-server/credits.mdx",
            "position" to "7",
            "product" to "The OmniSign server",
            "location" to "The full text of every licence below travels inside the server JAR under `META-INF/legal/`, so it is present in the container image without any extra mount. A running deployment serves the same list at `GET /api/v1/credits`, without authentication.",
        ),
        "desktop" to mapOf(
            "file" to "docs/docs-desktop/credits.mdx",
            "position" to "7",
            "product" to "The OmniSign desktop application",
            "location" to "The full text of every licence below is installed with the application, in the `resources/licenses/` folder next to the executable. The same list is available in the app itself under Help then Credits.",
        ),
        "web" to mapOf(
            "file" to "docs/docs-web/credits.mdx",
            "position" to "6",
            "product" to "The OmniSign web application",
            "location" to "The full text of every licence below is served alongside the bundle, in a `licenses/` folder next to `index.html`. The same list is available in the app itself under Help then Credits, where a second section additionally credits the components running on the connected server — the browser downloads none of the signing stack, so that is where the signing actually happens.",
        ),
    )
    val docsPageFiles: Map<String, File> = docsPages.mapValues { (_, page) ->
        rootProject.file(page.getValue("file"))
    }

    doLast {
        @Suppress("UNCHECKED_CAST")
        val data = groovy.json.JsonSlurper().parse(licenseData) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val licenses = data["licenses"] as Map<String, Map<String, String>>

        @Suppress("UNCHECKED_CAST")
        val components = data["components"] as List<Map<String, Any>>

        val componentByArtifact = mutableMapOf<String, Map<String, Any>>()
        val componentByGroup = mutableMapOf<String, Map<String, Any>>()
        val componentByNpmPackage = mutableMapOf<String, Map<String, Any>>()
        components.forEach { component ->
            @Suppress("UNCHECKED_CAST")
            (component["artifacts"] as? List<String>)?.forEach { componentByArtifact[it] = component }

            @Suppress("UNCHECKED_CAST")
            (component["groups"] as? List<String>)?.forEach { componentByGroup[it] = component }

            @Suppress("UNCHECKED_CAST")
            (component["npmPackages"] as? List<String>)?.forEach { componentByNpmPackage[it] = component }
        }

        val surfacesByCoordinate = sortedMapOf<String, MutableSet<String>>()
        val moduleKeyByCoordinate = mutableMapOf<String, String>()
        val fileByCoordinate = mutableMapOf<String, File>()

        fun record(coordinate: String, moduleKey: String, surface: String, file: File?) {
            surfacesByCoordinate.getOrPut(coordinate) { sortedSetOf() } += surface
            moduleKeyByCoordinate[coordinate] = moduleKey
            if (file != null) fileByCoordinate.putIfAbsent(coordinate, file)
        }

        jvmSurfaces.forEach { (surface, artifacts) ->
            artifacts.get().forEach { artifact ->
                val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@forEach
                val module = id.module.replace(hostVariantClassifier, "-<os>-<arch>")
                record(
                    coordinate = "${id.group}:$module:${id.version}",
                    moduleKey = "${id.group}:$module",
                    surface = surface,
                    file = artifact.file,
                )
            }
        }

        val webCoordinates = webCoordinatesFile.get().asFile
        if (!webCoordinates.isFile) {
            throw GradleException(
                "Missing ${webCoordinates.name}; run :composeApp:collectWebRuntimeCoordinates first.",
            )
        }
        webCoordinates.readLines().filter { it.isNotBlank() }.forEach { coordinate ->
            record(
                coordinate = coordinate,
                moduleKey = coordinate.substringBeforeLast(':'),
                surface = "web",
                file = null,
            )
        }

        @Suppress("UNCHECKED_CAST")
        val lock = groovy.json.JsonSlurper().parse(npmLockFile) as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val lockPackages = lock["packages"] as Map<String, Map<String, Any>>
        val npmLicenseHints = mutableMapOf<String, String>()
        lockPackages.forEach { (path, entry) ->
            if (!path.startsWith("node_modules/")) return@forEach
            if (entry["link"] == true || entry["dev"] == true) return@forEach
            val version = entry["version"] as? String ?: return@forEach
            val name = path.removePrefix("node_modules/")
            (entry["license"] as? String)?.let { npmLicenseHints["npm:$name"] = it }
            record(
                coordinate = "$name@$version",
                moduleKey = "npm:$name",
                surface = "web",
                file = null,
            )
        }

        val unmapped = mutableListOf<String>()
        val artifactsByComponent = sortedMapOf<String, MutableList<String>>()
        val surfacesByComponent = sortedMapOf<String, MutableSet<String>>()
        surfacesByCoordinate.forEach { (coordinate, surfaces) ->
            val moduleKey = moduleKeyByCoordinate.getValue(coordinate)
            val component = componentByArtifact[moduleKey]
                ?: componentByNpmPackage[moduleKey.removePrefix("npm:")].takeIf { moduleKey.startsWith("npm:") }
                ?: componentByGroup[moduleKey.substringBefore(':')].takeIf { !moduleKey.startsWith("npm:") }
            if (component == null) {
                val hint = npmLicenseHints[moduleKey]?.let { " (lockfile declares $it)" }.orEmpty()
                unmapped += "$coordinate$hint"
                return@forEach
            }
            val name = component["name"] as String
            artifactsByComponent.getOrPut(name) { mutableListOf() } += coordinate
            surfacesByComponent.getOrPut(name) { sortedSetOf() } += surfaces
        }

        if (unmapped.isNotEmpty()) {
            throw GradleException(
                "No licence entry for ${unmapped.size} distributed artifact(s). " +
                    "Add them to gradle/third-party-licenses.json:" +
                    unmapped.joinToString("") { "\n  - $it" },
            )
        }

        val usedComponents = components.filter { it["name"] as String in artifactsByComponent }
        val unusedComponents = components.map { it["name"] as String } - artifactsByComponent.keys
        if (unusedComponents.isNotEmpty()) {
            logger.lifecycle("Licence data has ${unusedComponents.size} entries no longer on any classpath: ${unusedComponents.joinToString()}")
        }

        val missingText = usedComponents.mapNotNull { component ->
            val licenseId = component["license"] as String
            val license = licenses[licenseId]
                ?: throw GradleException("Component '${component["name"]}' references unknown licence '$licenseId'.")
            val textFile = File(licenseTextDir, license["text"] as String)
            if (textFile.isFile) null else textFile.name
        }.distinct()
        if (missingText.isNotEmpty()) {
            throw GradleException("Missing licence text file(s) in licenses/: ${missingText.joinToString()}")
        }

        fun surfacesOf(component: Map<String, Any>): List<String> {
            val recorded = surfacesByComponent[component["name"] as String].orEmpty()
            return surfaceOrder.filter { it in recorded }
        }

        val noticeTexts = sortedMapOf<String, String>()
        fileByCoordinate.forEach { (coordinate, file) ->
            if (!file.name.endsWith(".jar") || !file.isFile) return@forEach
            ZipFile(file).use { zip ->
                val entry = listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md")
                    .firstNotNullOfOrNull { zip.getEntry(it) }
                if (entry != null) {
                    val text = zip.getInputStream(entry).use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }.trim()
                    if (text.isNotEmpty()) noticeTexts[coordinate] = text
                }
            }
        }

        val byLicense = usedComponents.groupBy { it["license"] as String }.toSortedMap()

        val out = StringBuilder()
        out.appendLine("# Third-party components")
        out.appendLine()
        out.appendLine("OmniSign is distributed under the GNU Affero General Public License v3.0 or later")
        out.appendLine("(see [LICENSE.md](LICENSE.md)). It also distributes the third-party components listed")
        out.appendLine("below, each under its own licence. The full text of every licence named here is in the")
        out.appendLine("[`licenses/`](licenses/) directory and is installed alongside the application.")
        out.appendLine()
        out.appendLine("Not every component ships everywhere. Each entry is tagged with the surfaces that")
        out.appendLine("actually contain it: `cli`, `server`, `desktop` (the three JVM packages) and `web` (the")
        out.appendLine("WebAssembly bundle, whose dependencies include npm packages the JVM builds never use).")
        out.appendLine()
        out.appendLine("Material copied directly into this repository, rather than resolved at build time, is")
        out.appendLine("recorded separately in [NOTICE.md](NOTICE.md).")
        out.appendLine()
        out.appendLine("This file is generated by `./gradlew generateThirdPartyNotices`; do not edit it by hand.")
        out.appendLine()

        val copyleft = usedComponents.filter { (it["license"] as String).let { id -> id.startsWith("LGPL") || id.startsWith("AGPL") || id == "MPL-2.0" } }
        if (copyleft.isNotEmpty()) {
            out.appendLine("## Notices required by the libraries themselves")
            out.appendLine()
            out.appendLine("The following components are used under a licence that requires this notice to be given")
            out.appendLine("with each copy of the work. OmniSign uses these libraries; they are covered by their own")
            out.appendLine("licences, whose full text accompanies this file; and their sources are available from the")
            out.appendLine("projects linked below and from the same place this distribution was obtained.")
            out.appendLine()
            copyleft.sortedBy { it["name"] as String }.forEach { component ->
                val license = licenses[component["license"] as String]!!
                out.appendLine("- **${component["name"]}** is used under the ${license["name"]}.")
                (component["copyright"] as? String)?.let { out.appendLine("  ${it}.") }
                out.appendLine("  Ships in: ${surfacesOf(component).joinToString(", ")}.")
                out.appendLine("  Full text: [`licenses/${license["text"]}`](licenses/${license["text"]}). Source: <${component["homepage"]}>.")
                (component["election"] as? String)?.let { out.appendLine("  $it") }
            }
            out.appendLine()
        }

        out.appendLine("## Summary")
        out.appendLine()
        out.appendLine("| Licence | Components | Artifacts |")
        out.appendLine("|---|---:|---:|")
        byLicense.forEach { (licenseId, group) ->
            val count = group.sumOf { artifactsByComponent[it["name"] as String]!!.size }
            out.appendLine("| ${licenses[licenseId]!!["name"]} | ${group.size} | $count |")
        }
        val totalArtifacts = artifactsByComponent.values.sumOf { it.size }
        out.appendLine("| **Total** | **${usedComponents.size}** | **$totalArtifacts** |")
        out.appendLine()
        out.appendLine("Per surface:")
        out.appendLine()
        out.appendLine("| Surface | Components | Artifacts |")
        out.appendLine("|---|---:|---:|")
        surfaceOrder.forEach { surface ->
            val componentCount = usedComponents.count { surface in surfacesOf(it) }
            val artifactCount = surfacesByCoordinate.count { surface in it.value }
            out.appendLine("| $surface | $componentCount | $artifactCount |")
        }
        out.appendLine()

        out.appendLine("## Components by licence")
        byLicense.forEach { (licenseId, group) ->
            val license = licenses[licenseId]!!
            out.appendLine()
            out.appendLine("### ${license["name"]}")
            out.appendLine()
            out.appendLine("SPDX identifier `$licenseId` — <${license["url"]}> — full text in [`licenses/${license["text"]}`](licenses/${license["text"]}).")
            group.sortedBy { it["name"] as String }.forEach { component ->
                val coordinates = artifactsByComponent[component["name"] as String]!!.sorted()
                out.appendLine()
                out.appendLine("#### ${component["name"]}")
                out.appendLine()
                (component["copyright"] as? String)?.let { out.appendLine("$it.") }
                (component["homepage"] as? String)?.let { out.appendLine("Homepage: <$it>.") }
                out.appendLine("Ships in: ${surfacesOf(component).joinToString(", ")}.")
                (component["election"] as? String)?.let { out.appendLine() ; out.appendLine("Licence election: $it") }
                (component["notes"] as? String)?.let { out.appendLine() ; out.appendLine("$it") }
                out.appendLine()
                out.appendLine("<details><summary>${coordinates.size} artifact(s)</summary>")
                out.appendLine()
                coordinates.forEach { out.appendLine("- `$it` — ${surfacesByCoordinate[it]!!.joinToString(", ")}") }
                out.appendLine()
                out.appendLine("</details>")
            }
        }

        if (noticeTexts.isNotEmpty()) {
            out.appendLine()
            out.appendLine("## Attribution notices carried by the artifacts")
            out.appendLine()
            out.appendLine("The Apache License 2.0 requires the contents of a component's own `NOTICE` file to travel")
            out.appendLine("with any distribution that includes it. The following notices are reproduced verbatim from")
            out.appendLine("the shipped artifacts.")
            noticeTexts.forEach { (coordinate, text) ->
                out.appendLine()
                out.appendLine("### $coordinate")
                out.appendLine()
                out.appendLine("```text")
                out.appendLine(text)
                out.appendLine("```")
            }
        }

        fun jsonString(value: String): String {
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace("\t", "\\t")
            return "\"$escaped\""
        }

        val credits = StringBuilder()
        credits.appendLine("[")
        val entries = usedComponents.sortedBy { (it["name"] as String).lowercase() }
        entries.forEachIndexed { index, component ->
            val licenseId = component["license"] as String
            val fields = buildList {
                add("\"name\": ${jsonString(component["name"] as String)}")
                add("\"licenseId\": ${jsonString(licenseId)}")
                add("\"licenseName\": ${jsonString(licenses[licenseId]!!["name"] as String)}")
                add("\"licenseText\": ${jsonString(licenses[licenseId]!!["text"] as String)}")
                (component["copyright"] as? String)?.let { add("\"copyright\": ${jsonString(it)}") }
                (component["homepage"] as? String)?.let { add("\"homepage\": ${jsonString(it)}") }
                add("\"surfaces\": [${surfacesOf(component).joinToString(", ") { jsonString(it) }}]")
                add("\"artifacts\": ${artifactsByComponent[component["name"] as String]!!.size}")
            }
            credits.append("\t{ ")
            credits.append(fields.joinToString(", "))
            credits.append(" }")
            credits.appendLine(if (index == entries.lastIndex) "" else ",")
        }
        credits.appendLine("]")

        fun mdxCell(value: String): String = value
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("<", "&lt;")
            .replace("{", "&#123;")

        val noticesUrl = "https://github.com/pizavo/omnisign/blob/main/THIRD-PARTY.md"
        val renderedPages = docsPages.mapValues { (surface, page) ->
            val shipped = usedComponents
                .filter { surface in surfacesOf(it) }
                .sortedBy { (it["name"] as String).lowercase() }
            val copyleftHere = shipped.filter {
                (it["license"] as String).let { id -> id.startsWith("LGPL") || id.startsWith("AGPL") || id == "MPL-2.0" }
            }

            val doc = StringBuilder()
            doc.appendLine("---")
            doc.appendLine("title: Credits")
            doc.appendLine("sidebar_position: ${page["position"]}")
            doc.appendLine("---")
            doc.appendLine()
            doc.appendLine("{/* Generated by ./gradlew generateThirdPartyNotices - do not edit by hand. */}")
            doc.appendLine()
            doc.appendLine("# Credits")
            doc.appendLine()
            doc.appendLine("${page["product"]} is built on the ${shipped.size} open-source components listed below.")
            doc.appendLine("Each is licensed by its own authors under its own terms, separately from OmniSign, which")
            doc.appendLine("is released under the GNU Affero General Public License v3.0 or later.")
            doc.appendLine()
            doc.appendLine(page["location"])
            doc.appendLine()

            if (copyleftHere.isNotEmpty()) {
                doc.appendLine(":::note[Components under a copyleft licence]")
                doc.appendLine("These require notice that they are used, a copy of their licence, and their copyright")
                doc.appendLine("shown alongside any the program displays. Their sources are available from the projects")
                doc.appendLine("linked below and from the same place this software was obtained.")
                doc.appendLine()
                copyleftHere.forEach { component ->
                    val license = licenses[component["license"] as String]!!
                    val copyright = (component["copyright"] as? String)?.let { " — ${mdxCell(it)}" }.orEmpty()
                    doc.appendLine("- **${mdxCell(component["name"] as String)}**, under the ${mdxCell(license["name"] as String)}$copyright")
                }
                doc.appendLine(":::")
                doc.appendLine()
            }

            doc.appendLine("## Components")
            doc.appendLine()
            doc.appendLine("| Component | Licence | Copyright |")
            doc.appendLine("|---|---|---|")
            shipped.forEach { component ->
                val license = licenses[component["license"] as String]!!
                val name = mdxCell(component["name"] as String)
                val nameCell = (component["homepage"] as? String)?.let { "[$name]($it)" } ?: name
                val licenseCell = "[${mdxCell(license["name"] as String)}](${license["url"]})"
                val copyrightCell = (component["copyright"] as? String)?.let { mdxCell(it) } ?: "—"
                doc.appendLine("| $nameCell | $licenseCell | $copyrightCell |")
            }
            doc.appendLine()
            doc.appendLine("## Full notices")
            doc.appendLine()
            doc.appendLine("Every component across all four OmniSign packages, together with the verbatim attribution")
            doc.appendLine("notices their artifacts carry, is listed in [THIRD-PARTY.md]($noticesUrl).")
            doc.toString()
        }

        val rendered = out.toString()
        val renderedCredits = credits.toString()
        if (verifyOnly.get()) {
            val staleFiles = buildList {
                if (!noticeFile.isFile || noticeFile.readText() != rendered) add(noticeFile.name)
                if (!creditsFile.isFile || creditsFile.readText() != renderedCredits) add(creditsFile.name)
                if (!sharedCreditsFile.isFile || sharedCreditsFile.readText() != renderedCredits) {
                    add("${sharedCreditsFile.name} (shared)")
                }
                renderedPages.forEach { (surface, content) ->
                    val target = docsPageFiles.getValue(surface)
                    if (!target.isFile || target.readText() != content) add(target.name + " ($surface)")
                }
            }
            if (staleFiles.isNotEmpty()) {
                throw GradleException(
                    "${staleFiles.joinToString()} out of date. Run: gradlew generateThirdPartyNotices",
                )
            }
            logger.lifecycle("Notices are up to date (${usedComponents.size} components, $totalArtifacts artifacts).")
        } else {
            noticeFile.writeText(rendered)
            listOf(creditsFile, sharedCreditsFile).forEach { target ->
                target.parentFile.mkdirs()
                target.writeText(renderedCredits)
            }
            renderedPages.forEach { (surface, content) ->
                docsPageFiles.getValue(surface).writeText(content)
            }
            val perSurface = surfaceOrder.joinToString(", ") { surface ->
                "$surface=${usedComponents.count { surface in surfacesOf(it) }}"
            }
            logger.lifecycle("Wrote ${noticeFile.name}, 2x ${creditsFile.name} and ${renderedPages.size} docs pages: ${usedComponents.size} components ($perSurface), $totalArtifacts artifacts, ${noticeTexts.size} attribution notices.")
        }
    }
}


/**
 * Builds the licence text the native installers display for the user to accept.
 *
 * `LICENSE.md` alone states only OmniSign's own AGPL terms, which says nothing about
 * the libraries the installer is about to put on the user's machine — several of
 * which, EU DSS among them, are used under a licence that requires their use to be
 * disclosed. This task therefore prefixes the AGPL text with a summary naming those
 * components and pointing at the full notices installed beside the application.
 *
 * The summary is derived from the generated credits list rather than written by hand,
 * so it cannot claim a set of licences the build does not actually ship. One file is
 * produced per installable surface, because the CLI and desktop packages carry different
 * dependency sets and neither should ask the user to accept a count that includes the
 * other's libraries. It reads only committed files, which keeps packaging independent of
 * dependency resolution.
 */
val generateInstallerLicense by tasks.registering {
    group = "distribution"
    description = "Builds the per-surface installer licence texts: OmniSign's AGPL terms plus a third-party summary."

    val licenseSource = rootProject.file("LICENSE.md")
    val creditsSource = rootProject.file(
        "composeApp/src/commonMain/composeResources/files/third-party-credits.json",
    )
    val outputDir = layout.buildDirectory.dir("legal")
    val installableSurfaces = listOf("cli", "desktop")

    inputs.file(licenseSource)
    inputs.file(creditsSource)
    outputs.dir(outputDir)

    doLast {
        @Suppress("UNCHECKED_CAST")
        val credits = groovy.json.JsonSlurper().parse(creditsSource) as List<Map<String, Any>>

        installableSurfaces.forEach { surface ->
            @Suppress("UNCHECKED_CAST")
            val shipped = credits.filter { surface in (it["surfaces"] as List<String>) }
            val licenseNames = shipped.map { it["licenseName"] as String }.distinct().sorted()
            val usesDss = shipped.any { (it["name"] as String).startsWith("EU DSS") }

            val preamble = buildString {
                appendLine("OmniSign")
                appendLine("Copyright (C) 2026 Pizavo")
                appendLine()
                appendLine("OmniSign is free software, licensed to you under the GNU Affero General Public")
                appendLine("License version 3 or later. Its full text follows below.")
                appendLine()
                appendLine("THIRD-PARTY COMPONENTS")
                appendLine()
                appendLine("This package also installs ${shipped.size} third-party open-source components, each")
                appendLine("licensed by its own authors under its own terms, separately from OmniSign.")
                if (usesDss) {
                    appendLine("Among them is EU DSS (Digital Signature Services), the European Commission's")
                    appendLine("reference signature library that OmniSign is built on, used under the GNU")
                    appendLine("Lesser General Public License version 2.1 or later.")
                }
                appendLine()
                appendLine("The licences involved are:")
                licenseNames.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Every component is listed with its licence and copyright in THIRD-PARTY.md, and")
                appendLine("the full text of each licence above is installed in the licenses folder, both")
                appendLine("placed alongside the application. Accepting these terms covers OmniSign itself;")
                appendLine("the third-party components remain governed by their own licences.")
                appendLine()
                appendLine("=".repeat(78))
                appendLine()
            }

            val target = outputDir.get().file("installer-license-$surface.md").asFile
            target.parentFile.mkdirs()
            target.writeText(preamble + licenseSource.readText())
            logger.lifecycle("Wrote ${target.name}: ${licenseNames.size} licences across ${shipped.size} components.")
        }
    }
}

/**
 * One-shot task that derives platform-specific icon formats from the two master
 * PNGs in `assets/icons/` using ImageMagick 7+ (`magick`).
 *
 * Run manually whenever the source PNGs change, then commit the results:
 * ```
 * .\gradlew generateIcons
 * ```
 *
 * Produced artefacts per master PNG (`omnisign-logo`, `omnisign-logo-cli`):
 *  - `.ico` — Windows multi-resolution (256 down to 16 px)
 *  - `.icns` — macOS icon bundle (via `icnsify`)
 *  - `-512.png` — 512×512 raster for Linux jpackage
 *
 * The desktop master (`omnisign-logo.png`) is additionally copied to:
 *  - `composeApp/src/commonMain/composeResources/drawable/` (in-app toolbar icon)
 *  - `composeApp/src/jvmMain/resources/` (runtime window / taskbar icon)
 *  - `composeApp/src/webMain/resources/` (Wasm favicon)
 *  - `docs/static/img/favicon.ico` (Docusaurus favicon, from generated .ico)
 *  - `docs/static/img/logo.png` (Docusaurus navbar logo)
 *
 * The `-512.png` raster is also copied to
 * `shared/src/jvmMain/resources/omnisign-toast-icon.png` (the Windows toast notification icon).
 */
tasks.register("generateIcons") {
    group = "distribution"
    description = "Generates .ico, .icns, and sized PNGs from the master PNGs in assets/icons/ via ImageMagick and icnsify."

    val iconsDir = rootProject.file("assets/icons")
    val composeDrawableIcon = rootProject.file("composeApp/src/commonMain/composeResources/drawable/icon_omnisign.png")
    val jvmResourcesIcon = rootProject.file("composeApp/src/jvmMain/resources/omnisign-logo.png")
    val webResourcesIcon = rootProject.file("composeApp/src/webMain/resources/omnisign-logo.png")
    val docsFavicon = rootProject.file("docs/static/img/favicon.ico")
    val docsLogo = rootProject.file("docs/static/img/logo.png")
    val sharedToastIcon = rootProject.file("shared/src/jvmMain/resources/omnisign-toast-icon.png")

    doLast {
        fun magick(vararg args: String) {
            val proc = ProcessBuilder("magick", *args)
                .inheritIO()
                .start()
            val exit = proc.waitFor()
            require(exit == 0) { "magick exited with code $exit" }
        }

        fun icnsify(vararg args: String) {
            val proc = ProcessBuilder("icnsify", *args)
                .inheritIO()
                .start()
            val exit = proc.waitFor()
            require(exit == 0) { "icnsify exited with code $exit" }
        }

        listOf("omnisign-logo", "omnisign-logo-cli").forEach { baseName ->
            val input = File(iconsDir, "$baseName.png")
            require(input.exists()) { "Master icon not found: ${input.absolutePath}" }

            magick(
                input.absolutePath,
                "-define", "icon:auto-resize=256,128,64,48,32,16",
                File(iconsDir, "$baseName.ico").absolutePath,
            )

            icnsify(
                "-i", input.absolutePath,
                "-o", File(iconsDir, "$baseName.icns").absolutePath,
            )

            magick(
                input.absolutePath,
                "-resize", "512x512",
                File(iconsDir, "$baseName-512.png").absolutePath,
            )
        }

        val desktopIcon = File(iconsDir, "omnisign-logo.png")
        desktopIcon.copyTo(composeDrawableIcon, overwrite = true)
        desktopIcon.copyTo(jvmResourcesIcon, overwrite = true)
        desktopIcon.copyTo(webResourcesIcon, overwrite = true)

        val desktopIco = File(iconsDir, "omnisign-logo.ico")
        desktopIco.copyTo(docsFavicon, overwrite = true)

        desktopIcon.copyTo(docsLogo, overwrite = true)

        File(iconsDir, "omnisign-logo-512.png").copyTo(sharedToastIcon, overwrite = true)
    }
}


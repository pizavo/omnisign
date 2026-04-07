plugins {
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
 */
tasks.register("generateIcons") {
    group = "distribution"
    description = "Generates .ico, .icns, and sized PNGs from the master PNGs in assets/icons/ via ImageMagick and icnsify."

    val iconsDir = rootProject.file("assets/icons")
    val composeDrawableIcon = rootProject.file("composeApp/src/commonMain/composeResources/drawable/icon_omnisign.png")
    val jvmResourcesIcon = rootProject.file("composeApp/src/jvmMain/resources/omnisign-logo.png")
    val webResourcesIcon = rootProject.file("composeApp/src/webMain/resources/omnisign-logo.png")
    val docsFavicon = rootProject.file("docs/static/img/favicon.ico")

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
    }
}


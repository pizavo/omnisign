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


plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.dokka)
    application
}

group = "cz.pizavo.omnisign"
version = project.findProperty("releaseVersion")?.toString() ?: "1.0.0"
application {
    mainClass.set("cz.pizavo.omnisign.ApplicationKt")
    
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment", "--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.https.redirect)
    implementation(libs.ktor.server.headers.default)
    implementation(libs.ktor.server.headers.forwarded)
    implementation(libs.ktor.serialization.json)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.kotlin.logging)

    testImplementation(libs.ktor.server.test)
    testImplementation(libs.kotest.engine)
    testImplementation(libs.kotest.jvm.runner)
    testImplementation(libs.kotest.core)
    testImplementation(libs.kotest.ktor)
    testImplementation(libs.kotest.decoroutinator)
    testImplementation(libs.mockk)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.ktor)

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

/**
 * Dokka configuration for the server module API documentation.
 */
dokka {
	dokkaPublications.html {
		outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
	}
	pluginsConfiguration.html {
		footerMessage.set("OmniSign — server module API reference")
	}
}

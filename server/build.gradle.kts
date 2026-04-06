plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotest.engine)
    testImplementation(libs.kotest.jvm.runner)
    testImplementation(libs.kotest.core)
    testImplementation(libs.kotest.ktor)
    testImplementation(libs.kotest.decoroutinator)
    
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

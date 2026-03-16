plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
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

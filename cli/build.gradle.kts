plugins {
	alias(libs.plugins.kotlinJvm)
	alias(libs.plugins.shadow)
	application
}

group = "cz.pizavo.omnisign"
version = "0.4.0"

dependencies {
	implementation(projects.shared)
	testImplementation(libs.kotlin.testJunit)

	implementation(libs.clikt)
	implementation(libs.clikt.core)
	implementation(libs.clikt.markdown)
	implementation(libs.mordant)

	implementation(project.dependencies.platform(libs.koin.bom))
	implementation(libs.koin.core)

	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.kotlinx.datetime)
	runtimeOnly(libs.logback)
}

application {
	mainClass.set("cz.pizavo.omnisign.CliKt")
	applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
	archiveBaseName.set("omnisign-cli")
	archiveClassifier.set("")
	archiveVersion.set(project.version.toString())
	manifest {
		attributes["Main-Class"] = "cz.pizavo.omnisign.CliKt"
	}
	mergeServiceFiles()
}


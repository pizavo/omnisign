plugins {
	alias(libs.plugins.kotlinJvm)
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
	
	implementation(project.dependencies.platform(libs.koin.bom))
	implementation(libs.koin.core)
	
	implementation(libs.kotlinx.coroutines.core)
}

application {
	mainClass.set("cz.pizavo.omnisign.CliKt")
}

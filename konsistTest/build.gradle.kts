import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Runs the convention checks on a JDK 25 toolchain, like the rest of the build.
 */
kotlin {
    jvmToolchain(25)
}

dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotest.engine)
    testImplementation(libs.kotest.jvm.runner)
    testImplementation(libs.kotest.core)
}

/**
 * Konsist parses the Kotlin sources of every module straight from disk, not through a classpath, so the
 * test task declares them as inputs. Without that, Gradle would treat the task as up to date, or take its
 * result from the build cache, after an edit to another module.
 *
 * A failed check prints its message, which lists each new or vanished violation, so the console and the
 * CI log show what to fix without opening the test report.
 */
tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events(TestLogEvent.FAILED)
        exceptionFormat = TestExceptionFormat.FULL
        showStackTraces = false
    }
    inputs.files(fileTree(rootDir) { include("*/src/**/*.kt") })
        .withPropertyName("kotlinSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

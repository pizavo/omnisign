package cz.pizavo.omnisign.ui.platform

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import cz.pizavo.omnisign.BuildConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

private val logger = KotlinLogging.logger {}

private const val APP_LOGGER = "cz.pizavo.omnisign"
private const val PROPS_FILE = "support.properties"
private const val KEY_DEBUG = "debug"
private const val KEY_EXTENDED = "extended"

/**
 * Third-party logger names lowered to DEBUG when extended logging is on, mapped
 * to the baseline level they are restored to (their explicit level in
 * `logback.xml`). The `eu.europa.esig.dss.tsl.*` loggers keep their own
 * explicit ERROR level and are intentionally never touched here, so the
 * trusted-list firehose stays suppressed even with extended logging enabled.
 */
private val LIBRARY_BASELINE: Map<String, Level> = mapOf(
    "eu.europa.esig" to Level.ERROR,
    "org.apache" to Level.WARN,
)

/**
 * Resolved log directory, sourced from the `omnisign.log.dir` system property
 * that the desktop entry point sets before Logback initializes (the same value
 * Logback's file appender writes to). `null` when the property is absent, e.g.
 * under tests or any non-desktop launch.
 */
private fun logDir(): File? =
    System.getProperty("omnisign.log.dir")?.takeIf { it.isNotBlank() }?.let { File(it) }

/**
 * Path of the persisted Support preferences file, in the same platform-native
 * configuration directory used by the other OmniSign preference stores.
 */
private val propsPath: Path by lazy {
    val userHome = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    val dir = when {
        os.contains("win") -> Paths.get(System.getenv("APPDATA") ?: "$userHome/AppData/Roaming", "omnisign")
        os.contains("mac") -> Paths.get(userHome, "Library", "Application Support", "omnisign")
        else -> Paths.get(userHome, ".config", "omnisign")
    }
    dir.resolve(PROPS_FILE)
}

/**
 * Loads the Support preferences from [path], returning empty defaults on any
 * failure or when the file does not exist yet. `internal` so it can be unit
 * tested against a temporary path.
 */
internal fun loadSupportProps(path: Path): Properties {
    val props = Properties()
    return try {
        if (path.exists()) path.inputStream().use { props.load(it) }
        props
    } catch (e: Exception) {
        logger.warn(e) { "Failed to load support preferences from $path" }
        props
    }
}

/**
 * Reads a single boolean flag from the Support preferences at [path].
 * `internal` for unit testing.
 */
internal fun readSupportFlag(path: Path, key: String): Boolean =
    loadSupportProps(path).getProperty(key)?.toBooleanStrictOrNull() == true

/**
 * Writes a single boolean flag to the Support preferences at [path], preserving
 * any other stored keys. `internal` for unit testing.
 */
internal fun writeSupportFlag(path: Path, key: String, value: Boolean) {
    try {
        val props = loadSupportProps(path)
        props.setProperty(key, value.toString())
        path.parent?.createDirectories()
        path.outputStream().use { props.store(it, "OmniSign support preferences") }
    } catch (e: Exception) {
        logger.warn(e) { "Failed to save support preference '$key' to $path" }
    }
}

/**
 * Reads a persisted flag from the resolved [propsPath].
 */
private fun readFlag(key: String): Boolean = readSupportFlag(propsPath, key)

/**
 * Persists a flag to the resolved [propsPath].
 */
private fun writeFlag(key: String, value: Boolean) = writeSupportFlag(propsPath, key, value)

/**
 * The level the application logger should take: DEBUG when debug logging is on,
 * or `null` to inherit the WARN root when off. `internal` for unit testing.
 */
internal fun appLoggerLevel(debug: Boolean): Level? = if (debug) Level.DEBUG else null

/**
 * The level a library logger should take: DEBUG only when both debug and
 * extended logging are on, otherwise its [baseline]. `internal` for unit
 * testing.
 */
internal fun libraryLoggerLevel(debug: Boolean, extended: Boolean, baseline: Level): Level =
    if (debug && extended) Level.DEBUG else baseline

/**
 * Applies the resolved levels to the running Logback context using
 * [appLoggerLevel] and [libraryLoggerLevel].
 */
private fun applyLevels(debug: Boolean, extended: Boolean) {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    context.getLogger(APP_LOGGER).level = appLoggerLevel(debug)
    for ((name, baseline) in LIBRARY_BASELINE) {
        context.getLogger(name).level = libraryLoggerLevel(debug, extended, baseline)
    }
}

/**
 * JVM implementation — support logging is available whenever the desktop entry
 * point has resolved a log directory.
 */
actual fun isSupportLogAvailable(): Boolean = logDir() != null

/**
 * JVM implementation — reveals the log directory in the OS file manager,
 * creating it if necessary (delegated to [openInFileExplorer]).
 */
actual fun openSupportLogDirectory(): Boolean {
    val path = logDir()?.absolutePath ?: return false
    return openInFileExplorer(path)
}

/**
 * JVM implementation — zips the log files plus a `diagnostics.txt` header into
 * a single archive written through FileKit's native save dialog.
 */
actual suspend fun exportSupportLogArchive(): Boolean {
    val dir = logDir() ?: return false
    val logFiles = dir.listFiles { file ->
        file.isFile && (file.name == "omnisign.log" || file.name.endsWith(".log") || file.name.endsWith(".log.gz"))
    }?.sortedBy { it.name } ?: emptyList()

    val bytes = try {
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostics.txt"))
                zip.write(diagnosticsHeader().encodeToByteArray())
                zip.closeEntry()
                for (file in logFiles) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    } catch (e: Exception) {
        logger.warn(e) { "Failed to build support log archive from $dir" }
        return false
    }

    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val destination = FileKit.openFileSaver(
        suggestedName = "omnisign-logs-$stamp",
        defaultExtension = "zip",
    ) ?: return false
    return try {
        destination.write(bytes)
        true
    } catch (e: Exception) {
        logger.warn(e) { "Failed to write support log archive" }
        false
    }
}

/**
 * Builds the human-readable environment header embedded in the archive so a
 * report includes the app version and runtime without a separate round-trip.
 */
private fun diagnosticsHeader(): String = buildString {
    appendLine("OmniSign support diagnostics")
    appendLine("Generated: " + LocalDateTime.now())
    appendLine("App version: " + BuildConfig.VERSION)
    appendLine("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")")
    appendLine("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")")
    appendLine("Debug logging: " + isDebugLoggingEnabled())
    appendLine("Extended logging: " + isExtendedLoggingEnabled())
}

/**
 * JVM implementation — reads the persisted debug-logging flag.
 */
actual fun isDebugLoggingEnabled(): Boolean = readFlag(KEY_DEBUG)

/**
 * JVM implementation — persists the debug-logging flag and applies it live.
 */
actual fun setDebugLoggingEnabled(enabled: Boolean) {
    writeFlag(KEY_DEBUG, enabled)
    applyLevels(debug = enabled, extended = readFlag(KEY_EXTENDED))
}

/**
 * JVM implementation — reads the persisted extended-logging flag.
 */
actual fun isExtendedLoggingEnabled(): Boolean = readFlag(KEY_EXTENDED)

/**
 * JVM implementation — persists the extended-logging flag and applies it live
 * (only effective while debug logging is enabled).
 */
actual fun setExtendedLoggingEnabled(enabled: Boolean) {
    writeFlag(KEY_EXTENDED, enabled)
    applyLevels(debug = readFlag(KEY_DEBUG), extended = enabled)
}

/**
 * JVM implementation — applies both persisted flags to the running context.
 */
actual fun applyPersistedDebugLogging() {
    applyLevels(debug = readFlag(KEY_DEBUG), extended = readFlag(KEY_EXTENDED))
}

package cz.pizavo.omnisign.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import java.io.File

/** Name of the Logback appender added by [attachLogFileAppender]. */
internal const val CliLogFileAppenderName = "omnisign-cli-file"

/** Encoder pattern mirroring the desktop file log for cross-surface consistency. */
private const val CliLogFilePattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"

/**
 * Attaches a [FileAppender] to the Logback root logger so the current CLI
 * invocation's log is also written to [path], in addition to stderr.
 *
 * The file is opened in append mode (repeated runs accumulate), and missing
 * parent directories are created. The appender inherits the root level, so it
 * captures DEBUG output when `--verbose` has lowered the root level.
 *
 * @param path Destination log file path (relative paths resolve against the
 *   working directory).
 * @return `true` if the appender started and the file is being written;
 *   `false` if Logback is not the active binding or the file could not be
 *   opened — stderr logging is unaffected either way, so the caller should
 *   merely warn the user.
 */
internal fun attachLogFileAppender(path: String): Boolean {
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return false
    val target = File(path)
    target.absoluteFile.parentFile?.mkdirs()

    val patternEncoder = PatternLayoutEncoder().apply {
        this.context = context
        pattern = CliLogFilePattern
        start()
    }
    val appender = FileAppender<ILoggingEvent>().apply {
        this.context = context
        name = CliLogFileAppenderName
        file = target.path
        isAppend = true
        encoder = patternEncoder
        start()
    }
    if (!appender.isStarted) return false

    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.addAppender(appender)
    return true
}

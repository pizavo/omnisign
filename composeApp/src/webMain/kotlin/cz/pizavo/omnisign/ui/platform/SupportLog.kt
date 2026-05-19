package cz.pizavo.omnisign.ui.platform

/**
 * Wasm/web stub — the browser sandbox has no persistent log file and no Logback
 * binding, so support logging is unavailable. The Help panel hides its Support
 * section when this returns `false`.
 */
actual fun isSupportLogAvailable(): Boolean = false

/**
 * Wasm/web stub — no local file manager to reveal.
 */
actual fun openSupportLogDirectory(): Boolean = false

/**
 * Wasm/web stub — no log files to archive.
 */
actual suspend fun exportSupportLogArchive(): Boolean = false

/**
 * Wasm/web stub — log levels cannot be controlled without a Logback backend.
 */
actual fun isDebugLoggingEnabled(): Boolean = false

/**
 * Wasm/web stub — no-op.
 */
actual fun setDebugLoggingEnabled(enabled: Boolean) = Unit

/**
 * Wasm/web stub — no library loggers to control.
 */
actual fun isExtendedLoggingEnabled(): Boolean = false

/**
 * Wasm/web stub — no-op.
 */
actual fun setExtendedLoggingEnabled(enabled: Boolean) = Unit

/**
 * Wasm/web stub — nothing to apply.
 */
actual fun applyPersistedDebugLogging() = Unit

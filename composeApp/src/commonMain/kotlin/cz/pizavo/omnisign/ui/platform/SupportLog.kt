package cz.pizavo.omnisign.ui.platform

/**
 * Whether this platform keeps a retrievable, on-disk log and can change log
 * levels at runtime.
 *
 * `true` on the desktop (JVM) target, `false` on the web (Wasm) target, which
 * has no filesystem or Logback binding. Callers use this to show or hide the
 * Help panel's Support section entirely.
 */
expect fun isSupportLogAvailable(): Boolean

/**
 * Reveals the log directory in the host OS file manager.
 *
 * @return `true` if the platform accepted the request, `false` when support
 *   logging is unavailable or the file manager could not be opened.
 */
expect fun openSupportLogDirectory(): Boolean

/**
 * Bundles the current and rotated log files plus a small diagnostics header
 * (app version, OS, JVM, debug-logging state) into a single `.zip` and writes
 * it to a user-chosen location via a native save dialog.
 *
 * @return `true` if the user picked a destination and the archive was written,
 *   `false` if the dialog was cancelled or support logging is unavailable.
 */
expect suspend fun exportSupportLogArchive(): Boolean

/**
 * Whether debug (DEBUG-level) logging is currently enabled for the
 * application's own loggers (`cz.pizavo.omnisign`).
 */
expect fun isDebugLoggingEnabled(): Boolean

/**
 * Enables or disables debug (DEBUG-level) logging for the application's own
 * loggers.
 *
 * The change is applied to the running logger context immediately (no restart)
 * and persisted so it survives application restarts.
 *
 * @param enabled `true` to log at DEBUG, `false` to return to the default WARN.
 */
expect fun setDebugLoggingEnabled(enabled: Boolean)

/**
 * Whether debug logging also lowers third-party library loggers (the DSS stack
 * and Apache) — "extended logging". Only meaningful while
 * [isDebugLoggingEnabled] is `true`.
 */
expect fun isExtendedLoggingEnabled(): Boolean

/**
 * Enables or disables extended logging: lowering third-party library loggers to
 * DEBUG together with the application loggers. Applied immediately when debug
 * logging is on, and persisted. Library output (especially DSS) is heavy — this
 * is an advanced opt-in for deep diagnosis.
 *
 * @param enabled `true` to also include library logs, `false` to keep libraries
 *   at their default suppressed levels.
 */
expect fun setExtendedLoggingEnabled(enabled: Boolean)

/**
 * Applies the persisted debug- and extended-logging preferences to the running
 * logger context. Called once during desktop startup so a saved preference
 * takes effect before any logging happens. No-op when support logging is
 * unavailable.
 */
expect fun applyPersistedDebugLogging()

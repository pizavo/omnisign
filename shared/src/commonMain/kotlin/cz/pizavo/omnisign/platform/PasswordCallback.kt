package cz.pizavo.omnisign.platform

/**
 * Callback interface for requesting password/PIN from user.
 *
 * Implementation must be provided via dependency injection based on the platform:
 * - CLI: Use standard input (System.console() or Scanner)
 * - Desktop: Use Compose dialog (see ComposePasswordCallback.kt.example)
 * - Server: Return error or use configuration-based passwords
 *
 * Register in Koin:
 * ```kotlin
 * single<PasswordCallback> { CliPasswordCallback() }
 * ```
 */
interface PasswordCallback {
    /**
     * Request password/PIN from user.
     *
     * @param prompt Message to display to the user
     * @param title Dialog title (optional, mainly for GUI implementations)
     * @return Password entered by user, or null if cancelled
     */
    fun requestPassword(prompt: String, title: String = "Password Required"): String?
}


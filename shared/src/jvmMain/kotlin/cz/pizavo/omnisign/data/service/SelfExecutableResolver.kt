package cz.pizavo.omnisign.data.service


/**
 * Resolves the absolute path of the currently running executable.
 *
 * When the application was launched through a native launcher (e.g., a
 * `jpackage`-generated binary), [resolve] returns the launcher path.
 * When started via `java -jar` the launcher is a JVM binary and
 * cannot be used directly — [resolve] returns `null` in that case.
 */
class SelfExecutableResolver {

    /**
     * Attempt to determine the running executable path.
     *
     * @return The absolute path to the native launcher, or `null` when the
     *   process was started via a JVM (`java` / `java.exe`).
     */
    fun resolve(): String? {
        val cmd = ProcessHandle.current().info().command().orElse(null) ?: return null
        val isJvm = cmd.endsWith("java") || cmd.endsWith("java.exe")
        return if (isJvm) null else cmd
    }
}


package cz.pizavo.omnisign.data.util

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Whether [glob]'s root — the literal prefix before its first wildcard (`*`, `?`, `[`, `{`), or the
 * whole pattern when it has none — is an absolute path on the current platform.
 *
 * Renewal globs must be absolute: a scheduled run's working directory is set by the OS scheduler
 * (cron, Task Scheduler, launchd), not by where the user added the job, so a relative — or, on
 * Windows, a drive-less (`/docs/...`) — pattern resolves against an unpredictable root and silently
 * matches the wrong tree or nothing.
 */
fun isAbsoluteGlobRoot(glob: String): Boolean {
	val wildcardIndex = glob.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
	val root = if (wildcardIndex == -1) glob else glob.substring(0, wildcardIndex)
	return runCatching { Path(root).isAbsolute }.getOrDefault(false)
}

/**
 * Whether [glob]'s wildcard tail is a pattern this platform's filesystem can parse.
 *
 * [isAbsoluteGlobRoot] inspects only the literal prefix, so a glob with a valid absolute root but a
 * malformed pattern — an unclosed `[` or `{` — passes that check and gets persisted. At run time it
 * then throws out of the scheduled run, taking every remaining job with it and skipping the run
 * record. Callers validate here so the pattern is rejected while the user can still fix it; the batch
 * guards the same calls again, since a job may also come from a hand-edited configuration file.
 *
 * A wildcard-free pattern is a literal path, and always parseable.
 */
fun isParseableGlob(glob: String): Boolean {
	val (_, tail) = globRootAndTail(glob) ?: return true
	return runCatching { renewalGlobMatcher(FileSystems.getDefault(), tail) }.isSuccess
}

/**
 * A wildcard renewal [glob] split, with backslashes read as separators, into the directory it is
 * rooted in, the prefix up to the last separator before its first wildcard (`.` when there is
 * none), and the wildcard tail after that separator; `null` for a literal pattern, which has no
 * wildcard.
 */
fun globRootAndTail(glob: String): Pair<String, String>? {
	val normalised = glob.replace('\\', '/')
	val wildcardIndex = normalised.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
	if (wildcardIndex == -1) return null
	val lastSlash = normalised.substring(0, wildcardIndex).lastIndexOf('/')
	val root = if (lastSlash == -1) "." else normalised.substring(0, lastSlash)
	return root to normalised.substring(lastSlash + 1)
}

/**
 * The matcher of a renewal glob's wildcard [tail], with forward slashes as its separators, on
 * [fileSystem]. In it a `**` segment followed by a separator matches zero or more directories, as
 * most glob tools read it, where [java.nio.file.FileSystem.getPathMatcher] needs at least one. A
 * pattern of the form `C:\Docs\**\*.pdf`, which the desktop's folder picker writes and the
 * documentation shows, thus selects the PDFs directly in its directory as well as those below it.
 *
 * Each such segment outside a `{...}` group and a `[...]` class is replaced with a group whose
 * alternatives are the segment and nothing, so that it also matches no directory at all. One inside
 * a group is left as it is, as groups cannot nest.
 *
 * @throws java.util.regex.PatternSyntaxException when [tail] is not a pattern the platform can
 *   parse, as one with an unclosed `[` or `{` is not.
 */
fun renewalGlobMatcher(fileSystem: FileSystem, tail: String): PathMatcher {
	val pattern = StringBuilder()
	var inGroup = false
	var inClass = false
	var index = 0
	while (index < tail.length) {
		val char = tail[index]
		when {
			inClass -> if (char == ']') inClass = false
			char == '[' -> inClass = true
			char == '{' -> inGroup = true
			char == '}' -> inGroup = false
			!inGroup && tail.startsWith("**/", index) && (index == 0 || tail[index - 1] == '/') -> {
				pattern.append("{**/,}")
				index += 3
				continue
			}
		}
		pattern.append(char)
		index++
	}
	return fileSystem.getPathMatcher("glob:$pattern")
}

/**
 * Whether a renewal run selects the file at [filePath] through [glob], judged as
 * [cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase] judges the files it finds: a wildcard
 * pattern selects a PDF under the directory it is rooted in whose path from there its
 * [renewalGlobMatcher] matches, and a literal pattern selects the file it names. Paths are compared
 * under the platform's rules, case-insensitively on Windows and case-sensitively elsewhere, as the
 * run compares them. The desktop's renewal offer uses it, so that it calls a file covered by a job
 * only when a run would renew it. A pattern or path the platform cannot parse selects nothing.
 */
fun globSelects(glob: String, filePath: String): Boolean = runCatching {
	val target = Path(filePath).toAbsolutePath().normalize()
	val (rootPart, tail) = globRootAndTail(glob)
		?: return@runCatching Path(glob).toAbsolutePath().normalize() == target
	val root = Path(rootPart).toAbsolutePath().normalize()
	target.startsWith(root) &&
		target.fileName.toString().endsWith(".pdf", ignoreCase = true) &&
		renewalGlobMatcher(root.fileSystem, tail).matches(root.relativize(target))
}.getOrDefault(false)

/**
 * Rewrite a relative renewal [glob] to an absolute one by resolving its directory part against
 * [base] (the process working directory by default), leaving any wildcard tail untouched.
 *
 * Renewal jobs are persisted and run later by an OS scheduler whose working directory differs from
 * the one the job was added in, so a stored glob must be absolute (see [isAbsoluteGlobRoot]). The
 * CLI, however, is normally invoked from the directory the user wants to watch, so it can freeze
 * that directory here at add time — turning a `*.pdf` typed in `C:\Docs` into a glob rooted there.
 *
 * An already-absolute glob is returned unchanged. Otherwise the directory part — the prefix up to
 * the last separator before the first wildcard, or the whole pattern when it has none — is resolved
 * against [base] and normalised, and the wildcard tail is re-appended. The result uses forward
 * slashes (accepted on every platform), since a wildcard tail cannot pass through the path APIs that
 * would otherwise choose the native separator. A glob that cannot be parsed is returned unchanged,
 * so the caller's absolute-path check still rejects it.
 *
 * @param glob The possibly-relative glob the user typed.
 * @param base The directory relative globs resolve against; the process working directory by default.
 * @return An absolute glob, or [glob] unchanged when it is already absolute or cannot be parsed.
 */
fun absolutizeGlob(glob: String, base: Path = Path("").absolute()): String =
	runCatching {
		if (isAbsoluteGlobRoot(glob)) return@runCatching glob
		val normalised = glob.replace('\\', '/')
		val wildcardIndex = normalised.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
		if (wildcardIndex == -1) {
			return@runCatching (base / normalised).normalize().toString().replace('\\', '/')
		}
		val prefix = normalised.substring(0, wildcardIndex)
		val lastSlash = prefix.lastIndexOf('/')
		val rootStr = if (lastSlash == -1) "." else normalised.substring(0, lastSlash)
		val tail = normalised.substring(lastSlash + 1)
		val root = (base / rootStr).normalize().toString().replace('\\', '/').trimEnd('/')
		"$root/$tail"
	}.getOrDefault(glob)

/**
 * A platform-appropriate example of an absolute renewal glob, for placeholders and error hints.
 */
fun absoluteGlobExample(): String =
	if (System.getProperty("os.name", "").lowercase().contains("win")) {
		"C:\\Docs\\**\\*.pdf"
	} else {
		"/srv/docs/**/*.pdf"
	}

/**
 * Whether [glob]'s target currently exists: for a wildcard pattern, the directory it is rooted in
 * (the prefix up to the last separator before the first wildcard); for a literal path, the file
 * itself. Best-effort — used only to warn, never to block — so a malformed path resolves to `false`.
 */
fun globRootExists(glob: String): Boolean {
	val wildcardIndex = glob.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
	if (wildcardIndex == -1) {
		return runCatching { Path(glob).exists() }.getOrDefault(false)
	}
	val prefix = glob.substring(0, wildcardIndex)
	val lastSeparator = prefix.indexOfLast { it == '/' || it == '\\' }
	val directory = if (lastSeparator == -1) prefix else prefix.substring(0, lastSeparator)
	return runCatching { Path(directory).isDirectory() }.getOrDefault(false)
}

/**
 * Whether [glob] still needs a file pattern: it has no wildcard and is not an existing file (so it is
 * a directory or a non-existent path). Such a pattern matches no files — a renewal glob must be a
 * concrete file or end in a pattern (e.g. a recursive PDF glob) — so callers reject it rather than
 * create a chip that silently never matches.
 */
fun globNeedsFilePattern(glob: String): Boolean {
	val hasWildcard = glob.any { it == '*' || it == '?' || it == '[' || it == '{' }
	if (hasWildcard) return false
	return runCatching { !Path(glob).isRegularFile() }.getOrDefault(true)
}

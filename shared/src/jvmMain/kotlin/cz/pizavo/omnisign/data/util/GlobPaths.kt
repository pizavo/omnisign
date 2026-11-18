package cz.pizavo.omnisign.data.util

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.LinkOption
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
 *
 * Where that separator is the root's own, as in `C:\**\*.pdf` or `\**\*.pdf`, the directory keeps
 * it: cut off, it would leave the bare drive `C:` or an empty path, which both name the working
 * directory, so a glob meant for a whole drive would search the folder the process was started in.
 */
fun globRootAndTail(glob: String): Pair<String, String>? {
	val normalised = glob.replace('\\', '/')
	val wildcardIndex = normalised.indexOfFirst { it == '*' || it == '?' || it == '[' || it == '{' }
	if (wildcardIndex == -1) return null
	val lastSlash = normalised.substring(0, wildcardIndex).lastIndexOf('/')
	val tail = normalised.substring(lastSlash + 1)
	if (lastSlash == -1) return "." to tail
	val parent = normalised.substring(0, lastSlash)
	return (if (parent.isEmpty() || parent.matches(driveSpecifier)) "$parent/" else parent) to tail
}

/**
 * A Windows drive with no path after it, such as `C:`.
 */
private val driveSpecifier = Regex("[A-Za-z]:")

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
 * [renewalGlobMatcher] matches, and a literal pattern selects the file it names. The desktop's
 * renewal offer uses it, so that it calls a file covered by a job only when a run would renew it.
 *
 * Paths are compared as the file system stores them, since the run reaches files through it. Where
 * a pattern's directory, or a literal pattern, is spelled as the file's path is but for letter case,
 * the file system's stored spelling of both decides, so letter case does not count there where the
 * file system ignores it, as on Windows and on macOS's usual volumes. Below that directory the
 * matcher reads the names as stored, comparing them under the platform's rules, case-insensitively
 * on Windows and case-sensitively elsewhere, as the run's matcher does. Symbolic links on either
 * path are read as plain names. Only paths spelled like the file's own are looked up, so the check
 * never waits on another volume, such as a network share that has gone away. A pattern or path the
 * platform cannot parse selects nothing.
 */
fun globSelects(glob: String, filePath: String): Boolean = globSelects(glob, filePath) { it.storedPath() }

/**
 * [globSelects] with the file system's spelling of a path, absolute and normalized, taken from
 * [stored], which is asked only about paths spelled like the file's own but for letter case, so a
 * spec can stand in for a file system of any kind and see which paths would be looked up.
 */
internal fun globSelects(glob: String, filePath: String, stored: (Path) -> Path): Boolean = runCatching {
	val spelled = Path(filePath).toAbsolutePath().normalize()
	val split = globRootAndTail(glob)
	if (split == null) {
		val literal = Path(glob).toAbsolutePath().normalize()
		return@runCatching literal.toString().equals(spelled.toString(), ignoreCase = true) &&
			stored(literal) == stored(spelled)
	}
	val (rootPart, tail) = split
	val root = Path(rootPart).toAbsolutePath().normalize()
	val ancestors = generateSequence(spelled.parent) { it.parent }
	if (ancestors.none { it.toString().equals(root.toString(), ignoreCase = true) }) return@runCatching false
	val storedRoot = stored(root)
	val target = stored(spelled)
	target.startsWith(storedRoot) &&
		target.fileName.toString().endsWith(".pdf", ignoreCase = true) &&
		renewalGlobMatcher(root.fileSystem, tail).matches(storedRoot.relativize(target))
}.getOrDefault(false)

/**
 * This path, which is absolute and normalized, spelled as the file system stores its names, letter
 * case and Windows' long names included, as far as it exists, with any symbolic link on it left in
 * place. The names past its longest part that exists and can be read are kept as written, so two
 * paths through the same folder are spelled alike whether or not the names below it exist; a path
 * no part of which exists comes back as written.
 */
private fun Path.storedPath(): Path = generateSequence(this) { it.parent }
	.firstNotNullOfOrNull { part ->
		runCatching { part.toRealPath(LinkOption.NOFOLLOW_LINKS).resolve(part.relativize(this)) }.getOrNull()
	}
	?: this

/**
 * Rewrite a relative renewal [glob] to an absolute one by resolving its directory part against
 * [base] (the process working directory by default), leaving any wildcard tail untouched.
 *
 * Renewal jobs are persisted and run later by an OS scheduler whose working directory differs from
 * the one the job was added in, so a stored glob must be absolute (see [isAbsoluteGlobRoot]). The
 * CLI, however, is normally invoked from the directory the user wants to watch, so it can freeze
 * that directory here at add time — turning a `*.pdf` typed in `C:\Docs` into a glob rooted there.
 *
 * An already-absolute glob is returned unchanged. Otherwise the directory part, as
 * [globRootAndTail] cuts it, or the whole pattern when it has no wildcard, is resolved against
 * [base] and normalised, and the wildcard tail is re-appended. A pattern that starts with a
 * separator, such as `\**\*.pdf` on Windows, thus starts at the root of [base]'s drive. The result
 * uses forward slashes (accepted on every platform), since a wildcard tail cannot pass through the
 * path APIs that would otherwise choose the native separator. A glob that cannot be parsed is
 * returned unchanged, so the caller's absolute-path check still rejects it.
 *
 * @param glob The possibly-relative glob the user typed.
 * @param base The directory relative globs resolve against; the process working directory by default.
 * @return An absolute glob, or [glob] unchanged when it is already absolute or cannot be parsed.
 */
fun absolutizeGlob(glob: String, base: Path = Path("").absolute()): String =
	runCatching {
		if (isAbsoluteGlobRoot(glob)) return@runCatching glob
		val (rootStr, tail) = globRootAndTail(glob)
			?: return@runCatching (base / glob.replace('\\', '/')).normalize().toString().replace('\\', '/')
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

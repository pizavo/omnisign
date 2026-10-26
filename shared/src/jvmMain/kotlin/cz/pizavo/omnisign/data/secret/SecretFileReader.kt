package cz.pizavo.omnisign.data.secret

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import java.io.IOException
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.getAttribute
import kotlin.io.path.inputStream
import kotlin.jvm.optionals.getOrNull

/**
 * Takes a secret from a file, but only from a file that is a safe place to keep one.
 *
 * The reader first resolves every symbolic link, so a Kubernetes secret volume — whose files are
 * links into a `..data` directory — is judged by the file the links lead to. It then refuses the
 * file unless all of these hold:
 *
 * - it is a regular file of at most [MAX_SIZE] bytes;
 * - it is not on an overlay file system, where a container keeps the files of its image and the
 *   files written into it, so a secret there is stored with the container;
 * - it is owned by the user this process runs as and nobody else may access it (mode 0600 or
 *   stricter), or it is owned by root and its group may at most read it (mode 0640 or stricter).
 *   These are the rules PostgreSQL applies to its private key file.
 *
 * The secret is the file's content as UTF-8 text without one line break at its end, so a file
 * written with `echo` holds the secret it was given. Each refusal names the file and how to fix
 * it, and none contains the secret.
 *
 * @param probe Reads the [SecretFileFacts] of a file; replaced in tests.
 * @param effectiveUid Returns the id of the user this process runs as, or `null` when that cannot
 *   be told; replaced in tests.
 */
class SecretFileReader(
	private val probe: (Path) -> SecretFileFacts = SecretFileFacts::of,
	private val effectiveUid: () -> Long? = ::currentEffectiveUid,
) {

	/**
	 * Read the secret in the file at [path].
	 *
	 * @param path The file, as the operator gave it.
	 * @param subject How a refusal refers to the file, such as
	 *   `The file '/run/secrets/jwt' named by OMNISIGN_JWT_SECRET_FILE`.
	 * @return The secret, or a [ConfigurationError.InvalidConfiguration] that says why the file
	 *   was refused.
	 */
	fun read(path: Path, subject: String): OperationResult<Sensitive<String>> {
		val realPath = try {
			path.toRealPath()
		} catch (_: NoSuchFileException) {
			return refused("$subject does not exist.").left()
		} catch (e: IOException) {
			return unreadable(subject, e).left()
		}
		val facts = try {
			probe(realPath)
		} catch (e: IOException) {
			return unreadable(subject, e).left()
		} catch (_: UnsupportedOperationException) {
			return refused(
				"$subject cannot be checked, because this platform does not report who owns a file " +
					"and who may access it.",
			).left()
		}
		problemWith(facts, realPath, subject)?.let { return refused(it).left() }
		val bytes = try {
			realPath.inputStream().use { it.readNBytes(MAX_SIZE + 1) }
		} catch (e: IOException) {
			return unreadable(subject, e).left()
		}
		if (bytes.size > MAX_SIZE) return refused(tooLarge(subject)).left()
		val text = try {
			bytes.decodeToString(throwOnInvalidSequence = true)
		} catch (_: CharacterCodingException) {
			return refused("$subject is not UTF-8 text.").left()
		}
		val secret = when {
			text.endsWith("\r\n") -> text.dropLast(2)
			text.endsWith('\n') -> text.dropLast(1)
			else -> text
		}
		if (secret.isBlank()) return refused("$subject is empty.").left()
		return secret.sensitive().right()
	}

	/**
	 * Say what makes the file that [facts] describe an unsafe place to take a secret from, or
	 * return `null` when nothing does.
	 *
	 * @param realPath The file with every symbolic link resolved, which the advice names.
	 * @param subject How the message refers to the file.
	 */
	private fun problemWith(facts: SecretFileFacts, realPath: Path, subject: String): String? {
		if (!facts.isRegularFile) return "$subject is not a regular file."
		if (facts.size > MAX_SIZE) return tooLarge(subject)
		if (facts.fileSystemType in IMAGE_LAYER_FILE_SYSTEMS) {
			return "$subject is on a file system of type '${facts.fileSystemType}', where a container keeps " +
				"the files of its image and the files written into it, so the secret would be stored with " +
				"the container. Mount the secret when the container starts instead, for example as a Docker " +
				"or Kubernetes secret."
		}
		val uid = effectiveUid()
			?: return "$subject cannot be checked, because it is not known which user this process runs as."
		val mode = facts.mode.toString(8).padStart(4, '0')
		return when {
			facts.ownerUid == ROOT_UID && (facts.mode and BEYOND_GROUP_READ_BITS) != 0 ->
				"$subject has mode $mode, but a secret file owned by root may give read access to its group " +
					"and no access to others (mode 0640 or stricter). Restrict it, for example with: " +
					"chmod 640 '$realPath'"

			facts.ownerUid == ROOT_UID -> null

			facts.ownerUid != uid ->
				"$subject is owned by user ${facts.ownerUid}, but a secret file must be owned by root or by " +
					"the user this process runs as ($uid). Change its owner, for example with: " +
					"chown $uid '$realPath'"

			(facts.mode and BEYOND_OWNER_BITS) != 0 ->
				"$subject has mode $mode, but a secret file may give no access to anyone except its owner " +
					"(mode 0600 or stricter). Restrict it, for example with: chmod 600 '$realPath'"

			else -> null
		}
	}

	/** Say that the file [subject] names is too large to be a secret file. */
	private fun tooLarge(subject: String): String =
		"$subject is larger than ${MAX_SIZE / 1024} KiB, which is more than a secret file holds."

	/** Refuse the file [subject] names, because reading it failed with [e]. */
	private fun unreadable(subject: String, e: IOException): ConfigurationError.InvalidConfiguration =
		if (e is AccessDeniedException) {
			refused("$subject cannot be read by the user this process runs as.")
		} else {
			refused("$subject cannot be read: ${e.message ?: e::class.simpleName}", e)
		}

	/** Wrap [message] in the error every refusal is. */
	private fun refused(message: String, cause: Throwable? = null): ConfigurationError.InvalidConfiguration =
		ConfigurationError.InvalidConfiguration(LocalizableText.Literal(message), cause = cause)

	companion object {

		/** The size of the largest file the reader takes a secret from, in bytes: 64 KiB. */
		const val MAX_SIZE = 64 * 1024

		/** The user id of root. */
		private const val ROOT_UID = 0L

		/** The mode bits a secret file must not have: any access for its group or others (0077). */
		private const val BEYOND_OWNER_BITS = 0b000_111_111

		/**
		 * The mode bits a secret file owned by root must not have: write or execute access for its
		 * group, or any access for others (0037).
		 */
		private const val BEYOND_GROUP_READ_BITS = 0b000_011_111

		/** The types of the file systems a container keeps its files on: overlayfs, fuse-overlayfs and aufs. */
		private val IMAGE_LAYER_FILE_SYSTEMS = setOf("overlay", "overlayfs", "fuse.fuse-overlayfs", "aufs")
	}
}

/**
 * Return the effective user id of this process — the user it acts as — or `null` when that
 * cannot be told.
 *
 * Calls the C library's `geteuid` through the foreign function API, which needs no native library
 * of its own and, unlike a lookup in the user database, also works for a container user that has
 * no entry there. When native access is denied, Linux still tells the user through the owner of
 * `/proc/self`.
 */
internal fun currentEffectiveUid(): Long? = nativeEffectiveUid() ?: procSelfOwnerUid()

/** Ask the C library's `geteuid` for this process's effective user id, or return `null` when it cannot be called. */
private fun nativeEffectiveUid(): Long? = try {
	val linker = Linker.nativeLinker()
	linker.defaultLookup().find("geteuid").getOrNull()?.let { symbol ->
		val geteuid = linker.downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT))
		(geteuid.invokeWithArguments() as Int).toUInt().toLong()
	}
} catch (_: Exception) {
	null
}

/** Return the owner of `/proc/self`, which Linux makes the process's effective user, or `null` without `/proc`. */
private fun procSelfOwnerUid(): Long? = try {
	(Path("/proc/self").getAttribute("unix:uid") as Int).toUInt().toLong()
} catch (_: Exception) {
	null
}

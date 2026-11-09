package cz.pizavo.omnisign.data.secret

import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.fileStore
import kotlin.io.path.getAttribute
import kotlin.io.path.readAttributes

/**
 * What [SecretFileReader] learns about a file before it takes a secret from it: whether it is a
 * plain file, who owns it, who else may access it, and what kind of file system holds it.
 *
 * @property isRegularFile Whether the file is a regular file rather than a directory, a device,
 *   a pipe or a symbolic link.
 * @property size The file's size in bytes.
 * @property ownerUid The numeric id of the user who owns the file.
 * @property mode The file's read, write and execute bits for its owner, its group and all others,
 *   as `ls -l` shows them: `0b110_100_000` is mode 0640.
 * @property fileSystemType The type of the file system that holds the file, as the operating
 *   system names it — such as `tmpfs`, `ext4`, `apfs` or `overlay` — or `null` when the operating
 *   system does not say.
 */
data class SecretFileFacts(
	val isRegularFile: Boolean,
	val size: Long,
	val ownerUid: Long,
	val mode: Int,
	val fileSystemType: String?,
) {

	companion object {

		/** The bits of a Unix file mode that grant read, write and execute access. */
		private const val PERMISSION_BITS = 0b111_111_111

		/**
		 * Read the facts of the file at [path], without following [path] if it is a symbolic link.
		 *
		 * @throws IOException when the file cannot be inspected.
		 * @throws UnsupportedOperationException when the file system does not report a file's
		 *   owner and mode the Unix way, as on Windows.
		 */
		fun of(path: Path): SecretFileFacts {
			val attributes = path.readAttributes<BasicFileAttributes>(LinkOption.NOFOLLOW_LINKS)
			return SecretFileFacts(
				isRegularFile = attributes.isRegularFile,
				size = attributes.size(),
				ownerUid = (path.getAttribute("unix:uid", LinkOption.NOFOLLOW_LINKS) as Int).toUInt().toLong(),
				mode = (path.getAttribute("unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and PERMISSION_BITS,
				fileSystemType = try {
					path.fileStore().type()
				} catch (_: IOException) {
					null
				},
			)
		}
	}
}

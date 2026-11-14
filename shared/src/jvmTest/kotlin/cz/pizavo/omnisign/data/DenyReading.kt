package cz.pizavo.omnisign.data

import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.fileAttributesViewOrNull
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.setPosixFilePermissions

/**
 * Deny this user the reading of the file at [path]: through an ACL entry where the file system has
 * ACLs, as on Windows, and through the file's POSIX permissions elsewhere, which do not stop root.
 *
 * @return A function that allows the reading again, putting the ACL or the permissions back as they
 *   were.
 */
internal fun denyReading(path: Path): () -> Unit {
	val acl = path.fileAttributesViewOrNull<AclFileAttributeView>()
	if (acl != null) {
		val entries = acl.acl
		val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
		val deny = AclEntry.newBuilder()
			.setType(AclEntryType.DENY)
			.setPrincipal(user)
			.setPermissions(AclEntryPermission.READ_DATA)
			.build()
		acl.acl = listOf(deny) + entries
		return { acl.acl = entries }
	}
	val permissions = path.getPosixFilePermissions()
	path.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_WRITE))
	return { path.setPosixFilePermissions(permissions) }
}

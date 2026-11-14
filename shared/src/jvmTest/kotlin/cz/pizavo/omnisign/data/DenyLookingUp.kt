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
 * Deny this user the looking up of the file at [path], so that whether it exists cannot be told,
 * as when its directory is on a share that went offline: through ACL entries where the file system
 * has ACLs, as on Windows, that deny the reading of the file and of its attributes and the listing
 * of its directory, and elsewhere by taking the search permission of its directory away, which does
 * not stop root.
 *
 * @return A function that allows the looking up again, putting the ACLs or the permissions back as
 *   they were.
 */
internal fun denyLookingUp(path: Path): () -> Unit {
	val directory = path.parent
	val fileAcl = path.fileAttributesViewOrNull<AclFileAttributeView>()
	val directoryAcl = directory.fileAttributesViewOrNull<AclFileAttributeView>()
	if (fileAcl != null && directoryAcl != null) {
		val user = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
		val fileEntries = fileAcl.acl
		val directoryEntries = directoryAcl.acl
		fileAcl.acl = listOf(
			AclEntry.newBuilder()
				.setType(AclEntryType.DENY)
				.setPrincipal(user)
				.setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES)
				.build()
		) + fileEntries
		directoryAcl.acl = listOf(
			AclEntry.newBuilder()
				.setType(AclEntryType.DENY)
				.setPrincipal(user)
				.setPermissions(AclEntryPermission.LIST_DIRECTORY)
				.build()
		) + directoryEntries
		return {
			directoryAcl.acl = directoryEntries
			fileAcl.acl = fileEntries
		}
	}
	val permissions = directory.getPosixFilePermissions()
	directory.setPosixFilePermissions(permissions - PosixFilePermission.OWNER_EXECUTE)
	return { directory.setPosixFilePermissions(permissions) }
}

package cz.pizavo.omnisign.data.secret

import cz.pizavo.omnisign.domain.model.value.Sensitive
import java.nio.file.Path

/**
 * The place where [EnvironmentSecretReader] found a secret.
 *
 * Each place belongs to a secret [name], such as `OMNISIGN_JWT_SECRET`, and has a [label] that
 * names the place in messages and logs without revealing the secret.
 */
sealed interface SecretSource {

	/** The name of the secret, which is also the name of the variable that may hold it. */
	val name: String

	/** How messages and logs name this place, such as `OMNISIGN_JWT_SECRET_FILE`. */
	val label: String

	/**
	 * The variable [name] holds the secret itself.
	 *
	 * @property value The secret.
	 */
	data class Variable(override val name: String, val value: Sensitive<String>) : SecretSource {

		override val label: String get() = name
	}

	/**
	 * The variable [variable] holds the path of a file with the secret, the way Docker and
	 * Kubernetes pass secrets to a container.
	 *
	 * @property path The file's path, as the variable gives it.
	 */
	data class File(override val name: String, val path: String) : SecretSource {

		/** The variable that holds [path]: [name] followed by [FILE_SUFFIX]. */
		val variable: String get() = name + FILE_SUFFIX

		override val label: String get() = variable
	}

	/**
	 * systemd passes the secret as the credential [name]: a file in the directory the
	 * [EnvironmentSecretReader.CREDENTIALS_DIRECTORY] variable names, filled from the unit's
	 * `LoadCredential=` or `SetCredential=` settings.
	 *
	 * @property path The credential's file.
	 */
	data class Credential(override val name: String, val path: Path) : SecretSource {

		override val label: String get() = "the systemd credential $name"
	}

	companion object {

		/** What the name of the variable holding a secret file's path adds to the secret's name. */
		const val FILE_SUFFIX = "_FILE"
	}
}

package cz.pizavo.omnisign.data.secret

import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.model.value.sensitive
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Reads a secret that the environment passes under a name, such as `OMNISIGN_JWT_SECRET`, from
 * whichever of three places holds it:
 *
 * - the variable `NAME` itself;
 * - a file whose path is in the variable `NAME_FILE`, the way Docker and Kubernetes pass secrets
 *   to a container;
 * - the systemd credential `NAME`: a file in the directory `$CREDENTIALS_DIRECTORY`, which systemd
 *   fills from the unit's `LoadCredential=` or `SetCredential=` settings.
 *
 * The secret may be set in only one of them, so its value never depends on an order of
 * precedence the operator would have to know. A file is read only through [SecretFileReader],
 * which refuses a file that is not a safe place to keep a secret. Windows, where that cannot be
 * checked, takes the variable only.
 *
 * @param fileReader Reads a secret from a file.
 * @param osName The name of the operating system, as the `os.name` system property gives it;
 *   replaced in tests.
 */
class EnvironmentSecretReader(
	private val fileReader: SecretFileReader,
	osName: String = System.getProperty("os.name", ""),
) {

	/** Whether this process runs on Windows, where no secret file is read. */
	private val isWindows = osName.startsWith("Windows", ignoreCase = true)

	/**
	 * Find where the secret [name] is set, without reading a file.
	 *
	 * A blank variable counts as unset, and a credential only when its file exists. Windows has no
	 * systemd credentials, so `CREDENTIALS_DIRECTORY` is not consulted there.
	 *
	 * @param environment Returns the value of a variable; the process environment by default.
	 * @return The one place the secret is set, `null` when it is set nowhere, or a
	 *   [ConfigurationError.InvalidConfiguration] naming each place when it is set in more than one.
	 */
	fun locate(name: String, environment: (String) -> String? = System::getenv): OperationResult<SecretSource?> {
		val sources = listOfNotNull(
			environment(name)?.takeIf { it.isNotBlank() }?.let { SecretSource.Variable(name, it.sensitive()) },
			environment(name + SecretSource.FILE_SUFFIX)?.takeIf { it.isNotBlank() }
				?.let { SecretSource.File(name, it) },
			credentialPath(name, environment)?.let { SecretSource.Credential(name, it) },
		)
		return if (sources.size > 1) ambiguous(sources).left() else sources.singleOrNull().right()
	}

	/**
	 * Read the secret from [source].
	 *
	 * @return The secret, or a [ConfigurationError.InvalidConfiguration] when the file [source]
	 *   names is refused, or [source] is a file named on Windows.
	 */
	fun read(source: SecretSource): OperationResult<Sensitive<String>> = when (source) {
		is SecretSource.Variable -> source.value.right()

		is SecretSource.File -> if (isWindows) {
			refused(
				"${source.variable} cannot be used on Windows, where it cannot be checked who may read a " +
					"file. Set ${source.name} instead.",
			).left()
		} else {
			fileReader.read(Path(source.path), "The file '${source.path}' named by ${source.variable}")
		}

		is SecretSource.Credential -> fileReader.read(source.path, "The systemd credential file '${source.path}'")
	}

	/**
	 * Find where the secret [name] is set and read it from there.
	 *
	 * @param environment Returns the value of a variable; the process environment by default.
	 * @return The secret, `null` when it is set nowhere, or the
	 *   [ConfigurationError.InvalidConfiguration] that [locate] or [read] returned.
	 */
	fun read(name: String, environment: (String) -> String? = System::getenv): OperationResult<Sensitive<String>?> =
		locate(name, environment).flatMap { source -> source?.let { read(it) } ?: null.right() }

	/**
	 * Return the file of the systemd credential [name] when `CREDENTIALS_DIRECTORY` holds one, or
	 * `null` when it holds none, the variable is unset, or this is Windows.
	 */
	private fun credentialPath(name: String, environment: (String) -> String?): Path? {
		if (isWindows || !CREDENTIAL_NAME.matches(name)) return null
		val directory = environment(CREDENTIALS_DIRECTORY)?.takeIf { it.isNotBlank() } ?: return null
		val path = try {
			Path(directory, name)
		} catch (_: InvalidPathException) {
			return null
		}
		return path.takeIf { it.exists(LinkOption.NOFOLLOW_LINKS) }
	}

	/** Refuse a secret that is set in each of [sources], which are more than one place. */
	private fun ambiguous(sources: List<SecretSource>): ConfigurationError.InvalidConfiguration {
		val labels = sources.map { it.label }
		val listed = labels.dropLast(1).joinToString(", ") + " and " + labels.last()
		val allSet = if (labels.size == 2) "Both $listed are set" else "$listed are all set"
		return refused("$allSet. Set only one of them.")
	}

	/** Wrap [message] in the error every refusal is. */
	private fun refused(message: String): ConfigurationError.InvalidConfiguration =
		ConfigurationError.InvalidConfiguration(LocalizableText.Literal(message))

	companion object {

		/** The variable in which systemd names the directory with a service's credentials. */
		const val CREDENTIALS_DIRECTORY = "CREDENTIALS_DIRECTORY"

		/** The names a credential is looked up for: those a variable may have, which stay inside the directory. */
		private val CREDENTIAL_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
	}
}

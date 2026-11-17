package cz.pizavo.omnisign.data.service

import java.io.File
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * A SoftHSM2 installation that a spec fills with tokens of its own.
 *
 * SoftHSM2 keeps its tokens in the directory its configuration file names, and finds that file through the
 * `SOFTHSM2_CONF` environment variable, which a process reads once, when it first loads the module. The `jvmTest`
 * task points the variable at a file in the build directory and names the same file in
 * `OMNISIGN_SOFTHSM2_TEST_CONF`; [locate] accepts only that pairing, so a spec can never empty the token directory
 * of a SoftHSM2 the machine uses for real. [reset] writes the file and empties the directory, and must run before
 * anything in the test JVM loads the module.
 *
 * @property module The SoftHSM2 PKCS#11 module.
 * @param config The configuration file `SOFTHSM2_CONF` names.
 * @param softHsmUtil The `softhsm2-util` executable, which creates tokens and imports keys.
 * @param pkcs11Tool OpenSC's `pkcs11-tool` executable, which imports certificates.
 */
class SoftHsm(
	val module: Path,
	private val config: Path,
	private val softHsmUtil: Path,
	private val pkcs11Tool: Path,
) {
	private val workDirectory: Path = config.toAbsolutePath().parent
	private val tokenDirectory: Path = workDirectory.resolve("tokens")

	/**
	 * Writes the configuration file and empties the token directory, leaving no tokens behind from an earlier run.
	 */
	@OptIn(ExperimentalPathApi::class)
	fun reset() {
		tokenDirectory.deleteRecursively()
		tokenDirectory.createDirectories()
		config.writeText(
			"""
			directories.tokendir = ${tokenDirectory.toString().replace('\\', '/')}
			objectstore.backend = file
			log.level = ERROR
			slots.removable = false
			""".trimIndent() + "\n",
		)
	}

	/**
	 * Initializes a token in the first free slot.
	 *
	 * @param label The token label, which discovery reports as the token's name.
	 * @param pin The user PIN.
	 */
	fun initToken(label: String, pin: String) {
		run(softHsmUtil, "--init-token", "--free", "--label", label, "--so-pin", SO_PIN, "--pin", pin)
	}

	/**
	 * Stores a private key and its certificate on a token under the same object id, so that SunPKCS11 pairs them
	 * into one key entry. The key is a private object; the certificate is a public one, readable without a login.
	 *
	 * @param tokenLabel The label of the token to write to.
	 * @param pin The token's user PIN.
	 * @param key The private key, which the token keeps from then on.
	 * @param certificate The certificate of the key.
	 */
	fun importKey(tokenLabel: String, pin: String, key: PrivateKey, certificate: X509Certificate) {
		val keyFile = workDirectory.resolve("import-key.pem")
		val certificateFile = workDirectory.resolve("import-certificate.der")
		try {
			val pem = Base64.Default.encode(key.encoded).chunked(PEM_LINE_LENGTH).joinToString("\n")
			keyFile.writeText("-----BEGIN PRIVATE KEY-----\n$pem\n-----END PRIVATE KEY-----\n")
			certificateFile.writeBytes(certificate.encoded)
			run(
				softHsmUtil, "--import", keyFile.toString(), "--token", tokenLabel, "--label", OBJECT_LABEL,
				"--id", OBJECT_ID, "--pin", pin,
			)
			run(
				pkcs11Tool, "--module", module.toString(), "--token-label", tokenLabel, "--login", "--pin", pin,
				"--write-object", certificateFile.toString(), "--type", "cert", "--id", OBJECT_ID,
				"--label", OBJECT_LABEL,
			)
		} finally {
			keyFile.deleteIfExists()
			certificateFile.deleteIfExists()
		}
	}

	/**
	 * Runs a SoftHSM2 or OpenSC tool and fails with its output when it does not succeed within a minute.
	 */
	private fun run(executable: Path, vararg arguments: String) {
		val outputFile = workDirectory.resolve("tool-output.txt")
		try {
			val process = ProcessBuilder(executable.toString(), *arguments)
				.redirectErrorStream(true)
				.redirectOutput(outputFile.toFile())
				.start()
			val finished = process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
			if (!finished) process.destroyForcibly().waitFor()
			check(finished && process.exitValue() == 0) {
				val outcome = if (finished) "exit code ${process.exitValue()}" else "no exit within $TOOL_TIMEOUT_SECONDS s"
				"${executable.fileName} ${arguments.first()} failed with $outcome:\n${outputFile.readText()}"
			}
		} finally {
			outputFile.deleteIfExists()
		}
	}

	/**
	 * Finds the SoftHSM2 module and tools, and the configuration file the test task set aside for them.
	 */
	companion object {
		/**
		 * The security officer PIN of every token, which no test uses beyond initializing the token.
		 */
		const val SO_PIN = "12345678"

		private const val OBJECT_LABEL = "signer"
		private const val OBJECT_ID = "01"
		private const val TOOL_TIMEOUT_SECONDS = 60L
		private const val PEM_LINE_LENGTH = 64

		/**
		 * Where Linux distributions and Homebrew install the module; `OMNISIGN_SOFTHSM2_MODULE` names another.
		 */
		private val MODULE_PATHS = listOf(
			"/usr/lib/softhsm/libsofthsm2.so",
			"/usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so",
			"/usr/lib/aarch64-linux-gnu/softhsm/libsofthsm2.so",
			"/usr/lib64/pkcs11/libsofthsm2.so",
			"/usr/local/lib/softhsm/libsofthsm2.so",
			"/opt/homebrew/lib/softhsm/libsofthsm2.so",
		)

		/**
		 * Returns the installation, or `null` when the module or a tool is missing, or when the test task did not set
		 * aside a configuration file, in which case a spec that needs SoftHSM2 is skipped.
		 *
		 * @return The installation, ready for [reset].
		 */
		fun locate(): SoftHsm? {
			val config = System.getenv("SOFTHSM2_CONF")
				?.takeIf { it.isNotBlank() && it == System.getenv("OMNISIGN_SOFTHSM2_TEST_CONF") }
				?.let(::Path)
				?: return null
			val module = (listOfNotNull(System.getenv("OMNISIGN_SOFTHSM2_MODULE")) + MODULE_PATHS)
				.map(::Path)
				.firstOrNull { it.isRegularFile() }
				?: return null
			val softHsmUtil = executableOnPath("softhsm2-util") ?: return null
			val pkcs11Tool = executableOnPath("pkcs11-tool") ?: return null
			return SoftHsm(module, config, softHsmUtil, pkcs11Tool)
		}

		/**
		 * Finds an executable in the directories of the `PATH` variable, with `.exe` appended on Windows.
		 */
		private fun executableOnPath(name: String): Path? {
			val fileName = if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name
			return System.getenv("PATH").orEmpty()
				.split(File.pathSeparatorChar)
				.filter { it.isNotBlank() }
				.map { Path(it, fileName) }
				.firstOrNull { it.isRegularFile() && it.isExecutable() }
		}
	}
}

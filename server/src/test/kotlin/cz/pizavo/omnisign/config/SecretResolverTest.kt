package cz.pizavo.omnisign.config

import cz.pizavo.omnisign.data.secret.EnvironmentSecretReader
import cz.pizavo.omnisign.data.secret.SecretFileFacts
import cz.pizavo.omnisign.data.secret.SecretFileReader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.fileSize
import kotlin.io.path.writeText

/**
 * Unit tests for [oidcClientSecretEnvVar] and [ServerSecrets.resolveFromEnv].
 *
 * The env-var derivation rule is operator-facing — once a deployment chooses provider
 * names, the resulting env var names are part of the contract. Pin every shape with a
 * test so the rule cannot accidentally drift.
 *
 * [ServerSecrets.resolveFromEnv] is given the environment to read instead of the process's,
 * and a secret reader that accepts every file and acts as it does on Linux, so the tests of a
 * secret kept in a file run on every operating system; the rules for such a file are tested with
 * the reader itself.
 */
class SecretResolverTest : FunSpec({

    val directory = tempdir().toPath()
    val secretReader = EnvironmentSecretReader(
        SecretFileReader(
            probe = { SecretFileFacts(isRegularFile = true, size = it.fileSize(), ownerUid = 1000, mode = 0b110_000_000, fileSystemType = "tmpfs") },
            effectiveUid = { 1000 },
        ),
        osName = "Linux",
    )
    val authEnabled = ServerConfig(auth = AuthConfig(enabled = true))
    val tlsConfigured = ServerConfig(tls = TlsConfig(keystorePath = "tls.p12"))

    /** Resolve the secrets [config] needs from an environment that holds only [variables]. */
    fun resolve(config: ServerConfig, vararg variables: Pair<String, String>): ServerSecrets =
        ServerSecrets.resolveFromEnv(config, secretReader, mapOf(*variables)::get)

    /** Write [content] to a new file and return its path. */
    fun secretFile(content: String): String =
        createTempFile(directory, "secret").also { it.writeText(content) }.toString()

    test("simple lowercase name produces an uppercase env var") {
        oidcClientSecretEnvVar("google") shouldBe "OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET"
    }

    test("hyphenated name maps each hyphen to underscore") {
        oidcClientSecretEnvVar("google-workspace") shouldBe "OMNISIGN_OIDC_GOOGLE_WORKSPACE_CLIENT_SECRET"
    }

    test("dotted name maps each dot to underscore (eduID.cz pattern)") {
        oidcClientSecretEnvVar("eduid.cz") shouldBe "OMNISIGN_OIDC_EDUID_CZ_CLIENT_SECRET"
    }

    test("mixed-case name is uppercased") {
        oidcClientSecretEnvVar("Auth0") shouldBe "OMNISIGN_OIDC_AUTH0_CLIENT_SECRET"
    }

    test("digits are preserved") {
        oidcClientSecretEnvVar("provider1") shouldBe "OMNISIGN_OIDC_PROVIDER1_CLIENT_SECRET"
    }

    test("multiple non-alphanumeric characters collapse to single underscores at each position") {
        oidcClientSecretEnvVar("my-weird.name+extra") shouldBe "OMNISIGN_OIDC_MY_WEIRD_NAME_EXTRA_CLIENT_SECRET"
    }

    test("resolves no secret when nothing that needs one is configured") {
        val secrets = resolve(ServerConfig(), "OMNISIGN_JWT_SECRET" to "unused")

        secrets.jwtSecret.shouldBeNull()
        secrets.tlsKeystorePassword.shouldBeNull()
        secrets.tlsPrivateKeyPassword.shouldBeNull()
        secrets.signingKeystorePassword.shouldBeNull()
        secrets.oidcClientSecrets.shouldBeEmpty()
    }

    test("takes a secret from its variable") {
        resolve(authEnabled, "OMNISIGN_JWT_SECRET" to "jwt-secret").jwtSecret?.value shouldBe "jwt-secret"
    }

    test("takes a secret from the file its variable with _FILE names") {
        val file = secretFile("jwt-from-file\n")

        resolve(authEnabled, "OMNISIGN_JWT_SECRET_FILE" to file).jwtSecret?.value shouldBe "jwt-from-file"
    }

    test("takes a secret from the systemd credential named like its variable") {
        val credentials = createTempDirectory(directory, "credentials")
            .also { it.resolve("OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET").writeText("client-secret\n") }
        val config = ServerConfig(
            auth = AuthConfig(
                providers = listOf(
                    OidcProviderConfig(name = "google", clientId = "client-id", allowedEmailDomains = listOf("example.org")),
                ),
            ),
        )

        val secrets = resolve(config, EnvironmentSecretReader.CREDENTIALS_DIRECTORY to credentials.toString())

        secrets.oidcClientSecrets["google"]?.value shouldBe "client-secret"
    }

    test("refuses a required secret that is set nowhere, naming the variable and the file variable") {
        val error = shouldThrow<IllegalStateException> { resolve(authEnabled) }

        error.message shouldBe "OMNISIGN_JWT_SECRET is required (auth.enabled is true) but is not set. Set it " +
            "before starting the server, or set OMNISIGN_JWT_SECRET_FILE to the path of a file that holds it."
    }

    test("refuses a required secret whose variable is empty") {
        val error = shouldThrow<IllegalStateException> { resolve(authEnabled, "OMNISIGN_JWT_SECRET" to " ") }

        error.message shouldContain "OMNISIGN_JWT_SECRET is set but empty"
    }

    test("takes a secret from a file even when its variable is set but empty") {
        val file = secretFile("jwt-from-file")

        resolve(authEnabled, "OMNISIGN_JWT_SECRET" to "", "OMNISIGN_JWT_SECRET_FILE" to file)
            .jwtSecret?.value shouldBe "jwt-from-file"
    }

    test("refuses a secret set both in its variable and in a file") {
        val error = shouldThrow<IllegalStateException> {
            resolve(authEnabled, "OMNISIGN_JWT_SECRET" to "jwt-secret", "OMNISIGN_JWT_SECRET_FILE" to secretFile("other"))
        }

        error.message shouldBe "Both OMNISIGN_JWT_SECRET and OMNISIGN_JWT_SECRET_FILE are set. Set only one of them."
    }

    test("passes on why a secret file was refused") {
        val missing = directory.resolve("missing").toString()

        val error = shouldThrow<IllegalStateException> { resolve(authEnabled, "OMNISIGN_JWT_SECRET_FILE" to missing) }

        error.message shouldBe "The file '$missing' named by OMNISIGN_JWT_SECRET_FILE does not exist."
    }

    test("falls back to the TLS keystore password for the private key") {
        val secrets = resolve(tlsConfigured, "OMNISIGN_TLS_KEYSTORE_PASSWORD" to "keystore-password")

        secrets.tlsPrivateKeyPassword?.value shouldBe "keystore-password"
    }

    test("takes the TLS private key password from its own file") {
        val secrets = resolve(
            tlsConfigured,
            "OMNISIGN_TLS_KEYSTORE_PASSWORD" to "keystore-password",
            "OMNISIGN_TLS_PRIVATE_KEY_PASSWORD_FILE" to secretFile("key-password\n"),
        )

        secrets.tlsPrivateKeyPassword?.value shouldBe "key-password"
    }

    test("refuses an optional secret set in two places as well") {
        val error = shouldThrow<IllegalStateException> {
            resolve(
                tlsConfigured,
                "OMNISIGN_TLS_KEYSTORE_PASSWORD" to "keystore-password",
                "OMNISIGN_TLS_PRIVATE_KEY_PASSWORD" to "key-password",
                "OMNISIGN_TLS_PRIVATE_KEY_PASSWORD_FILE" to secretFile("other"),
            )
        }

        error.message shouldContain "Set only one of them"
    }
})

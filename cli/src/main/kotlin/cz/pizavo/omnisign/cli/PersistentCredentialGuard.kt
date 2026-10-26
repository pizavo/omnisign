package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import cz.pizavo.omnisign.domain.service.CredentialStore

/**
 * Stop a command that would save [rawPassword] to [credentialStore] when the store cannot keep it
 * past this process.
 *
 * Without a keyring the store falls back to memory. The command would then record a
 * `credentialKey` in the saved configuration that points at nothing once it exits, and later
 * operations would reach the timestamp server without a password. The check takes the raw option
 * value, so it runs before `-` prompts for a password that would only be thrown away.
 *
 * @param rawPassword The raw `--timestamp-password` value, or `null` when the option was not given.
 * @param credentialStore The store the command would save the password to.
 * @throws ProgramResult with status 1, after printing why and what to do instead, when the
 *   password cannot be kept.
 */
fun CliktCommand.requirePersistentCredentialStore(rawPassword: String?, credentialStore: CredentialStore) {
	if (rawPassword == null || credentialStore.isPersistent) return
	echo(
		"❌ Cannot store the timestamp server password: no keyring is available, so it would be lost " +
				"when this command exits.",
		err = true
	)
	echo(
		"Save the other settings without --timestamp-password, and pass the password on each run " +
				"instead: --timestamp-password or ${OperationConfigOptions.TIMESTAMP_PASSWORD_ENVVAR} on " +
				"sign, timestamp and validate.",
		err = true
	)
	echo(
		"On Linux, the keyring is a Secret Service provider such as GNOME Keyring, KWallet or KeePassXC.",
		err = true
	)
	throw ProgramResult(1)
}

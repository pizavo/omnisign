package cz.pizavo.omnisign.cli

import arrow.core.getOrElse
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import cz.pizavo.omnisign.commands.config.ConfigSet
import cz.pizavo.omnisign.data.secret.SecretSource
import cz.pizavo.omnisign.domain.service.CredentialStore

/**
 * Store the timestamp server [password] of [account] in [credentialStore], under
 * [ConfigSet.TSA_CREDENTIAL_SERVICE], before the command saves a configuration that refers to it.
 *
 * The saved configuration names the stored password by a `credentialKey` only. Saved after the store
 * failed, that key would point at no password, or at one stored there before, so the command stops
 * here instead, having saved nothing — not even the other settings it was given.
 *
 * @param credentialStore The store to save the password to.
 * @param account The timestamp server username the password belongs to.
 * @param password The password to store.
 * @throws ProgramResult with status 1, after printing why and what to do instead, when the store
 *   does not keep the password.
 */
fun CliktCommand.storeTimestampPassword(credentialStore: CredentialStore, account: String, password: String) {
	credentialStore.setPassword(ConfigSet.TSA_CREDENTIAL_SERVICE, account, password).getOrElse { error ->
		echo("❌ ${error.message}, so nothing was saved.", err = true)
		error.details?.let { echo("Details: $it", err = true) }
		error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
		echo("If the keyring is locked, unlock it and run the command again.", err = true)
		echo(
			"Otherwise, save the other settings without --timestamp-password, and pass the password on each " +
					"run instead: --timestamp-password, ${OperationConfigOptions.TIMESTAMP_PASSWORD_ENVVAR}, or a " +
					"file named by ${OperationConfigOptions.TIMESTAMP_PASSWORD_ENVVAR}${SecretSource.FILE_SUFFIX}, " +
					"on sign, timestamp and validate.",
			err = true
		)
		throw ProgramResult(1)
	}
}

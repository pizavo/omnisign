package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import cz.pizavo.omnisign.domain.model.error.OperationError

/**
 * End the command with status 1, after printing [error] to stderr.
 *
 * The first line gives the error's message, after [summary] when there is one. The error's details
 * follow, and then its cause, unless the cause only repeats the details. A command that reports a
 * failed operation through here cannot go on to exit with status 0, so a script can tell the failure
 * from a success by the exit status alone.
 *
 * @param error The error the failed operation returned.
 * @param summary What failed, such as `Failed to save profile`, printed before the error's message.
 * @throws ProgramResult with status 1, always.
 */
fun CliktCommand.failWith(error: OperationError, summary: String? = null): Nothing {
	echo("❌ ${summary?.let { "$it: " }.orEmpty()}${error.message}", err = true)
	error.details?.let { echo("Details: $it", err = true) }
	error.cause?.message?.takeIf { it != error.details }?.let { echo("Cause: $it", err = true) }
	throw ProgramResult(1)
}

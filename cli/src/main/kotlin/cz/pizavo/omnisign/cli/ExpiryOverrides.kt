package cz.pizavo.omnisign.cli

import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.RawOption
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import cz.pizavo.omnisign.domain.model.config.algorithmConstraintDate

/**
 * This option as `--algo-expiry-override` reads it: repeatable, with each value an `ALGORITHM=DATE`
 * or `ALGORITHM:KEY_SIZE=DATE` entry for
 * [cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig.expirationDateOverrides], split
 * at its first `=` into a key and a date, both trimmed.
 *
 * A value with no `=`, with nothing before it, or with a date that is not in the form `YYYY-MM-DD`,
 * as [algorithmConstraintDate] reads it, fails the command with a usage error that names the value,
 * before anything is saved. The validation policy and the pre-signing check would pass over such a
 * date and keep the one they hold, while the command reported the change as made.
 *
 * @return The option, whose value lists the entries as the command line gives them.
 */
fun RawOption.expiryOverrides():
	OptionWithValues<List<Pair<String, String>>, Pair<String, String>, Pair<String, String>> =
	convert("ALG[:SIZE]=DATE") { entry ->
		val key = entry.substringBefore('=', missingDelimiterValue = "").trim()
		if (key.isEmpty()) {
			fail("'$entry' is not ALGORITHM=DATE or ALGORITHM:KEY_SIZE=DATE, such as SHA256=2030-12-31")
		}
		val date = entry.substringAfter('=').trim()
		if (algorithmConstraintDate(date) == null) {
			fail("the date of $key, '$date', is not in the form YYYY-MM-DD, such as 2030-12-31")
		}
		key to date
	}.multiple()

package cz.pizavo.omnisign.data.repository

import java.io.IOException

/**
 * The details an error caused by this exception gives: the class of an I/O failure, whose message
 * often holds only the path, as for a file this user may not read or a replace that Windows refused,
 * or the message of any other failure, such as a decoder's account of what is wrong with a file.
 */
internal fun Exception.failureDetails(): String? =
	if (this is IOException) this::class.simpleName else message

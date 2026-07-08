package cz.pizavo.omnisign.ui.platform

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download

/**
 * Wasm/JS — hands the configuration-export [bytes] to the browser download flow via FileKit, named
 * `<suggestedName>.zip`. The archive is built server-side and fetched over the API (see
 * [cz.pizavo.omnisign.data.remote.RemoteConfigArchive]); the browser has no filesystem to write to
 * via a path, so a download is the web equivalent of the desktop save dialog.
 *
 * @return `true` once the download is triggered, `false` if the browser rejects it.
 */
actual suspend fun exportConfigArchive(bytes: ByteArray, suggestedName: String): Boolean =
	try {
		FileKit.download(bytes = bytes, fileName = "$suggestedName.zip")
		true
	} catch (_: Throwable) {
		false
	}

/**
 * Wasm/JS stub — configuration import has no browser backend: the server's configuration is
 * read-only over the API, so there is nothing to import into on the web target.
 */
actual suspend fun importConfigArchive(): ByteArray? = null

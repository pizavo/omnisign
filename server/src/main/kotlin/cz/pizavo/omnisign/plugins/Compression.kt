package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.CompressionConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*

/**
 * Install Ktor [Compression] plugin driven by the [CompressionConfig] from `server.yml`.
 *
 * When [config] has [CompressionConfig.enabled] set to `false`, compression is not installed.
 * Otherwise, gzip and deflate encoders are registered with priorities and minimum size
 * thresholds from the configuration.
 *
 * Binary and already-compressed content types ([ContentType.Application.Pdf],
 * [ContentType.Application.OctetStream]) are excluded from compression because attempting
 * to gzip them wastes CPU without reducing response size.
 *
 * @param config Compression configuration loaded from the server YAML.
 */
fun Application.configureCompression(config: CompressionConfig) {
	if (!config.enabled) return

	install(Compression) {
		gzip {
			priority = config.gzipPriority
			minimumSize(config.gzipMinimumSize)
			excludeContentType(
				ContentType.Application.Pdf,
				ContentType.Application.OctetStream,
			)
		}
		deflate {
			priority = config.deflatePriority
			minimumSize(config.deflateMinimumSize)
			excludeContentType(
				ContentType.Application.Pdf,
				ContentType.Application.OctetStream,
			)
		}
	}
}


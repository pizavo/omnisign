package cz.pizavo.omnisign.plugins

import cz.pizavo.omnisign.config.CompressionConfig
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*

/**
 * Install Ktor [Compression] plugin driven by the [CompressionConfig] from `server.yml`.
 *
 * When [config] has [CompressionConfig.enabled] set to `false`, compression is not installed.
 * Otherwise, gzip and deflate encoders are registered with priorities and minimum size
 * thresholds from the configuration.
 *
 * @param config Compression configuration loaded from the server YAML.
 */
fun Application.configureCompression(config: CompressionConfig) {
	if (!config.enabled) return

	install(Compression) {
		gzip {
			priority = config.gzipPriority
			minimumSize(config.gzipMinimumSize)
		}
		deflate {
			priority = config.deflatePriority
			minimumSize(config.deflateMinimumSize)
		}
	}
}


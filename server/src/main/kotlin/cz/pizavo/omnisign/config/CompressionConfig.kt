package cz.pizavo.omnisign.config

/**
 * Response compression configuration.
 *
 * @property enabled Whether response compression is active. Defaults to `true`.
 * @property gzipPriority Quality priority for gzip encoding (higher = preferred). Defaults to `1.0`.
 * @property gzipMinimumSize Minimum response body size in bytes before gzip is applied. Defaults to `1024`.
 * @property deflatePriority Quality priority for deflate encoding. Defaults to `0.9`.
 * @property deflateMinimumSize Minimum response body size in bytes before deflate is applied. Defaults to `1024`.
 */
data class CompressionConfig(
	val enabled: Boolean = true,
	val gzipPriority: Double = 1.0,
	val gzipMinimumSize: Long = 1024L,
	val deflatePriority: Double = 0.9,
	val deflateMinimumSize: Long = 1024L,
)


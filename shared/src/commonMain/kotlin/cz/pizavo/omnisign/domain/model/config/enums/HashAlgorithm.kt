package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * Hash algorithm options for digital signatures.
 */
@Serializable
enum class HashAlgorithm {
    SHA256,
    SHA384,
    SHA512,
    SHA3_256,
    SHA3_384,
    SHA3_512,
    WHIRLPOOL,
    RIPEMD160;
    
    /**
     * Get the DSS algorithm name.
     */
    val dssName: String
        get() = when (this) {
            SHA256 -> "SHA256"
            SHA384 -> "SHA384"
            SHA512 -> "SHA512"
            SHA3_256 -> "SHA3-256"
            SHA3_384 -> "SHA3-384"
            SHA3_512 -> "SHA3-512"
            WHIRLPOOL -> "WHIRLPOOL"
            RIPEMD160 -> "RIPEMD160"
        }
}


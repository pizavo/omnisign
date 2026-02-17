package cz.pizavo.omnisign.domain.service

/**
 * Token connection for signing operations.
 * This wraps DSS token implementations.
 */
interface SigningToken {
    /**
     * Get the underlying DSS token connection.
     * This is JVM-specific and will be implemented in jvmMain.
     */
    fun getDssToken(): Any
    
    /**
     * Close the token connection.
     */
    fun close()
}


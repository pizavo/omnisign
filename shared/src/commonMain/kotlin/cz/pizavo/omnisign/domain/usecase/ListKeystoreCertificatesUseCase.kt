package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.model.value.Sensitive
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for listing the certificates held in a pre-configured PKCS#12 keystore.
 *
 * Delegates to [SigningRepository.listCertificatesFromKeystore], which opens the keystore
 * non-interactively with the supplied password. Unlike [ListCertificatesUseCase] this reads a
 * specific keystore file rather than discovering PKCS#11 / OS-store tokens, and it applies **no**
 * signing-capability filter: the keystore is a deliberately-configured signing identity, so the
 * listing mirrors exactly the key the signing path will use — no cert is hidden that the server
 * would nonetheless sign with.
 *
 * Used server-side to surface a file-keystore signing identity (`operations.signingKeystorePath`)
 * through the certificate-discovery route so remote clients can select it.
 */
class ListKeystoreCertificatesUseCase(
    private val signingRepository: SigningRepository,
) {
    /**
     * Open the PKCS#12 keystore at [keystoreFile] with [keystorePassword] and return its certificates.
     *
     * @param keystoreFile Absolute path to the PKCS#12 (.p12 / .pfx) keystore.
     * @param keystorePassword Keystore password, or `null` to attempt an empty password.
     * @return Certificates found in the keystore, or an error when it is missing or cannot be opened.
     */
    suspend operator fun invoke(
        keystoreFile: String,
        keystorePassword: Sensitive<String>?,
    ): OperationResult<List<AvailableCertificateInfo>> =
        signingRepository.listCertificatesFromKeystore(keystoreFile, keystorePassword)
}

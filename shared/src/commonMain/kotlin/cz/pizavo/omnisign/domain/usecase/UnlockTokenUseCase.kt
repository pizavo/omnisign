package cz.pizavo.omnisign.domain.usecase

import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.AvailableCertificateInfo
import cz.pizavo.omnisign.domain.repository.SigningRepository

/**
 * Use case for unlocking a PIN-protected token interactively.
 *
 * Delegates to [SigningRepository.unlockToken], which prompts the user for
 * a PIN via the platform [cz.pizavo.omnisign.platform.PasswordCallback] and
 * returns the certificates found on the token.
 */
class UnlockTokenUseCase(
    private val signingRepository: SigningRepository,
) {
    /**
     * Unlock the token identified by [tokenId] and return its signing-capable certificates.
     *
     * @param tokenId Stable identifier from [cz.pizavo.omnisign.domain.repository.LockedTokenInfo.tokenId].
     * @return List of available certificates or an error.
     */
    suspend operator fun invoke(tokenId: String): OperationResult<List<AvailableCertificateInfo>> =
        signingRepository.unlockToken(tokenId)
}


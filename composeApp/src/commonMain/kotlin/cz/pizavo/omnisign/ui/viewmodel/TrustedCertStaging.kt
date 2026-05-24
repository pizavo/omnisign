package cz.pizavo.omnisign.ui.viewmodel

import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.ui.model.PendingTrustedCert

/**
 * Apply staged trusted-certificate changes for [scope] to the [store] when a form is saved.
 *
 * Removals are applied first, then additions. A removal of a fingerprint the scope no longer
 * references is tolerated (treated as success) and adding an already-present certificate replaces
 * its type, so applying the same staged change set again is safe — a save retried after a partial
 * failure does not error on changes that already took effect.
 *
 * @param store The app-managed trust store to write to.
 * @param scope The scope the changes belong to (global or a profile).
 * @param removals Fingerprints staged for removal from [scope].
 * @param additions Certificates staged for addition to [scope].
 * @return `null` on success, or a human-readable message describing the first failure.
 */
internal suspend fun applyStagedTrustedCertChanges(
    store: TrustStore,
    scope: TrustScope,
    removals: Set<String>,
    additions: List<PendingTrustedCert>,
): String? {
    for (fingerprint in removals) {
        val error = store.remove(scope, fingerprint).fold(
            ifLeft = { if (it is TrustStoreError.NotFound) null else it.message },
            ifRight = { null },
        )
        if (error != null) return error
    }
    for (addition in additions) {
        val error = store.add(scope, addition.bytes, addition.type, addition.source).fold(
            ifLeft = { it.message },
            ifRight = { null },
        )
        if (error != null) return error
    }
    return null
}

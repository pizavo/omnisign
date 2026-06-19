package cz.pizavo.omnisign.ui.viewmodel

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.TrustedCertificateType
import cz.pizavo.omnisign.domain.model.error.TrustStoreError
import cz.pizavo.omnisign.domain.model.text.LocalizableText
import cz.pizavo.omnisign.domain.model.trust.TrustScope
import cz.pizavo.omnisign.domain.model.trust.TrustedCertificate
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.ui.model.PendingTrustedCert
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

/**
 * Unit tests for [applyStagedTrustedCertChanges].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrustedCertStagingTest : FunSpec({

    fun storedCert() = TrustedCertificate(
        fingerprint = "sha256-new",
        subjectDN = "CN=new",
        notBefore = Instant.parse("2024-01-01T00:00:00Z"),
        notAfter = Instant.parse("2030-01-01T00:00:00Z"),
        type = TrustedCertificateType.CA,
    )

    test("applies removals and additions and returns null on success") {
        runTest {
            val store: TrustStore = mockk()
            coEvery { store.remove(TrustScope.Global, "sha256-old") } returns Unit.right()
            coEvery {
                store.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem")
            } returns storedCert().right()

            val error = applyStagedTrustedCertChanges(
                store = store,
                scope = TrustScope.Global,
                removals = setOf("sha256-old"),
                additions = listOf(
                    PendingTrustedCert(
                        source = "ca.pem",
                        type = TrustedCertificateType.CA,
                        bytes = byteArrayOf(1),
                        fingerprint = "sha256-ca",
                        subjectDN = "CN=ca",
                        notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                    ),
                ),
            )

            error.shouldBeNull()
            coVerify { store.remove(TrustScope.Global, "sha256-old") }
            coVerify { store.add(TrustScope.Global, any(), TrustedCertificateType.CA, "ca.pem") }
        }
    }

    test("tolerates a NotFound removal so a retried save does not error") {
        runTest {
            val store: TrustStore = mockk()
            coEvery {
                store.remove(TrustScope.Global, "sha256-gone")
            } returns TrustStoreError.NotFound(LocalizableText.Literal("sha256-gone")).left()

            val error = applyStagedTrustedCertChanges(
                store = store,
                scope = TrustScope.Global,
                removals = setOf("sha256-gone"),
                additions = emptyList(),
            )

            error.shouldBeNull()
        }
    }

    test("surfaces a non-NotFound removal failure") {
        runTest {
            val store: TrustStore = mockk()
            coEvery { store.remove(TrustScope.Global, any()) } returns TrustStoreError.StorageFailed(LocalizableText.Literal("disk")).left()

            val error = applyStagedTrustedCertChanges(
                store = store,
                scope = TrustScope.Global,
                removals = setOf("sha256-x"),
                additions = emptyList(),
            )

            error.shouldNotBeNull()
        }
    }

    test("surfaces an addition failure") {
        runTest {
            val store: TrustStore = mockk()
            coEvery { store.add(any(), any(), any(), any()) } returns TrustStoreError.ParseFailed(LocalizableText.Literal("bad")).left()

            val error = applyStagedTrustedCertChanges(
                store = store,
                scope = TrustScope.Global,
                removals = emptySet(),
                additions = listOf(
                    PendingTrustedCert(
                        source = "bad.pem",
                        type = TrustedCertificateType.ANY,
                        bytes = byteArrayOf(0),
                        fingerprint = "sha256-bad",
                        subjectDN = "CN=bad",
                        notAfter = Instant.parse("2030-01-01T00:00:00Z"),
                    ),
                ),
            )

            error.shouldNotBeNull()
        }
    }
})

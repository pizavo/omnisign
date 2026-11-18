package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.enumerations.CertificationPermission
import eu.europa.esig.dss.enumerations.PdfLockAction
import eu.europa.esig.dss.pades.SignatureFieldParameters
import eu.europa.esig.dss.pades.exception.ProtectedDocumentException
import eu.europa.esig.dss.pades.validation.PdfSignatureDictionary
import eu.europa.esig.dss.pades.validation.PdfSignatureField
import eu.europa.esig.dss.pdf.PdfDocumentReader
import eu.europa.esig.dss.pdf.SigFieldPermissions
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * Verifies [SignatureRestrictionChecker] raises a [SignatureRestrictionException] with DSS's reason for each
 * restriction DSS checks before signing — a certification that permits no changes, an earlier signature's FieldMDP
 * dictionary, and the Lock dictionary of an earlier signature's field — passes a document none of them restricts,
 * and leaves the refusal by an encrypted document's permissions to DSS's own [ProtectedDocumentException].
 */
class SignatureRestrictionCheckerTest : FunSpec({

	val checker = SignatureRestrictionChecker()
	val newField = SignatureFieldParameters()

	/** A reader of an unencrypted PDF whose certification allows [certification], holding [signatures]. */
	fun reader(
		certification: CertificationPermission? = null,
		signatures: List<PdfSignatureDictionary> = emptyList(),
	): PdfDocumentReader = mockk {
		every { certificationPermission } returns certification
		every { isUsageRightsSignaturePresent } returns false
		every { extractSigDictionaries() } returns signatures
	}

	/** An earlier signature whose FieldMDP dictionary is [fieldMdp] and whose field's Lock dictionary is [lock]. */
	fun signature(fieldMdp: SigFieldPermissions? = null, lock: SigFieldPermissions? = null): PdfSignatureDictionary {
		val field = mockk<PdfSignatureField> { every { lockDictionary } returns lock }
		return mockk {
			every { fieldMDP } returns fieldMdp
			every { signatureFields } returns listOf(field)
		}
	}

	/** Field permissions that lock every field, with [permission] as their `P` entry. */
	fun lockingEveryField(permission: CertificationPermission? = null) = SigFieldPermissions().apply {
		action = PdfLockAction.ALL
		certificationPermission = permission
	}

	test("a certification that permits no changes refuses the signature as a restriction, with DSS's reason and no cause") {
		val refusal = shouldThrow<SignatureRestrictionException> {
			checker.checkSignatureRestrictionDictionaries(reader(CertificationPermission.NO_CHANGE_PERMITTED), newField)
		}

		refusal.message.shouldNotBeNull() shouldContain "DocMDP dictionary does not permit a new signature creation"
		refusal.cause.shouldBeNull()
	}

	test("an earlier signature's FieldMDP dictionary that locks every field refuses the signature as a restriction") {
		shouldThrow<SignatureRestrictionException> {
			checker.checkSignatureRestrictionDictionaries(
				reader(signatures = listOf(signature(fieldMdp = lockingEveryField()))),
				newField,
			)
		}.message.shouldNotBeNull() shouldContain "FieldMDP dictionary"
	}

	test("the Lock dictionary of an earlier signature's field refuses the signature as a restriction") {
		shouldThrow<SignatureRestrictionException> {
			checker.checkSignatureRestrictionDictionaries(
				reader(signatures = listOf(signature(lock = lockingEveryField(CertificationPermission.NO_CHANGE_PERMITTED)))),
				newField,
			)
		}.message.shouldNotBeNull() shouldContain "Lock dictionary"
	}

	test("a document whose certification allows form filling and signing, and which nothing locks, passes") {
		shouldNotThrowAny {
			checker.checkSignatureRestrictionDictionaries(
				reader(CertificationPermission.MINIMAL_CHANGES_PERMITTED, listOf(signature())),
				newField,
			)
		}
	}

	test("an encrypted document whose permissions refuse a new field is refused with DSS's own exception") {
		val encrypted = mockk<PdfDocumentReader> {
			every { isEncrypted } returns true
			every { isOpenWithOwnerAccess } returns false
			every { canCreateSignatureField() } returns false
		}

		shouldThrow<ProtectedDocumentException> { checker.checkDocumentPermissions(encrypted, newField) }
			.shouldNotBeInstanceOf<SignatureRestrictionException>()
	}
})

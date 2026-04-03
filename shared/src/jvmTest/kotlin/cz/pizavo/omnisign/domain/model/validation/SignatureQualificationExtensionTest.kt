package cz.pizavo.omnisign.domain.model.validation

import eu.europa.esig.dss.enumerations.SignatureQualification
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Verifies the [SignatureQualification.toTrustTier] extension function
 * maps every DSS qualification value to the correct [SignatureTrustTier].
 */
class SignatureQualificationExtensionTest : FunSpec({

	val qscdValues = setOf(
		SignatureQualification.QESIG,
		SignatureQualification.QESEAL,
		SignatureQualification.NOT_ADES_QC_QSCD,
		SignatureQualification.UNKNOWN_QC_QSCD,
		SignatureQualification.INDETERMINATE_QESIG,
		SignatureQualification.INDETERMINATE_QESEAL,
		SignatureQualification.INDETERMINATE_UNKNOWN_QC_QSCD,
	)

	val qualifiedValues = setOf(
		SignatureQualification.ADESIG_QC,
		SignatureQualification.ADESEAL_QC,
		SignatureQualification.NOT_ADES_QC,
		SignatureQualification.UNKNOWN_QC,
		SignatureQualification.INDETERMINATE_ADESIG_QC,
		SignatureQualification.INDETERMINATE_ADESEAL_QC,
		SignatureQualification.INDETERMINATE_UNKNOWN_QC,
	)

	val notQualifiedValues = setOf(
		SignatureQualification.ADESIG,
		SignatureQualification.ADESEAL,
		SignatureQualification.NOT_ADES,
		SignatureQualification.UNKNOWN,
		SignatureQualification.INDETERMINATE_ADESIG,
		SignatureQualification.INDETERMINATE_ADESEAL,
		SignatureQualification.INDETERMINATE_UNKNOWN,
		SignatureQualification.NA,
	)

	qscdValues.forEach { qualification ->
		test("${qualification.name} maps to QUALIFIED_QSCD") {
			qualification.toTrustTier() shouldBe SignatureTrustTier.QUALIFIED_QSCD
		}
	}

	qualifiedValues.forEach { qualification ->
		test("${qualification.name} maps to QUALIFIED") {
			qualification.toTrustTier() shouldBe SignatureTrustTier.QUALIFIED
		}
	}

	notQualifiedValues.forEach { qualification ->
		test("${qualification.name} maps to NOT_QUALIFIED") {
			qualification.toTrustTier() shouldBe SignatureTrustTier.NOT_QUALIFIED
		}
	}

	test("all SignatureQualification values are covered by test sets") {
		val covered = qscdValues + qualifiedValues + notQualifiedValues
		covered shouldBe SignatureQualification.entries.toSet()
	}
})

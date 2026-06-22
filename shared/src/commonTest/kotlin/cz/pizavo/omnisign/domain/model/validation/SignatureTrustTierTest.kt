package cz.pizavo.omnisign.domain.model.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain

/**
 * Verifies [qscdResidenceInfo] — the positive QSCD-residence confirmation surfaced only for the
 * [SignatureTrustTier.QUALIFIED_QSCD] tier (the inverse of DSS's two "does not reside in a QSCD …"
 * qualification warnings).
 */
class SignatureTrustTierTest : FunSpec({

	test("qscdResidenceInfo confirms QSCD residence at both times only for QUALIFIED_QSCD") {
		val info = SignatureTrustTier.QUALIFIED_QSCD.qscdResidenceInfo().shouldNotBeNull().english()
		info shouldContain "QSCD"
		info shouldContain "issuance"
		info shouldContain "signing"
	}

	test("qscdResidenceInfo is null for tiers without confirmed QSCD") {
		SignatureTrustTier.QUALIFIED.qscdResidenceInfo().shouldBeNull()
		SignatureTrustTier.NOT_QUALIFIED.qscdResidenceInfo().shouldBeNull()
	}
})

package cz.pizavo.omnisign.ades.policy

import cz.pizavo.omnisign.domain.model.config.AlgorithmConstraintsConfig
import cz.pizavo.omnisign.domain.model.config.enums.HashAlgorithm
import cz.pizavo.omnisign.domain.model.config.enums.toDss
import cz.pizavo.omnisign.domain.service.AlgorithmExpirationChecker
import eu.europa.esig.dss.policy.EtsiValidationPolicy
import eu.europa.esig.dss.policy.jaxb.AlgoExpirationDate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Guards the `commonMain` snapshot of the algorithm schedule in DSS's default ETSI validation policy.
 *
 * The pre-signing [AlgorithmExpirationChecker] runs on every platform, the browser included, so it
 * cannot read the DSS policy and works from copies of it instead:
 * [AlgorithmExpirationChecker.DEFAULT_UPDATE_DATE], [HashAlgorithm.expirationDate], and the severity
 * levels of [AlgorithmConstraintsConfig.DEFAULT]. DSS revises that schedule between releases — 6.5.1
 * moved the `UpdateDate` from 2024-10-13 to 2026-02-01 — and a stale copy makes the pre-signing check
 * disagree with the validation DSS performs on the very same signature. These tests therefore fail on
 * such an upgrade by design; the fix is to refresh the snapshot, not to relax the test.
 */
class AlgorithmPolicySnapshotTest : FunSpec({

	fun defaultAlgoExpirationDate(): AlgoExpirationDate =
		(AdESPolicy().load(policyFile = null) as EtsiValidationPolicy)
			.cryptographic?.algoExpirationDate.shouldNotBeNull()

	test("the default update date matches the DSS default policy") {
		AlgorithmExpirationChecker.DEFAULT_UPDATE_DATE.toString() shouldBe defaultAlgoExpirationDate().updateDate
	}

	test("every hash algorithm carries the expiration date the DSS default policy gives it") {
		val dssDates = defaultAlgoExpirationDate().algos
			.filter { it.size == null }
			.associate { it.value to it.date }
		val mismatched = HashAlgorithm.entries.filter { algorithm ->
			algorithm.dssName !in dssDates || dssDates[algorithm.dssName] != algorithm.expirationDate?.toString()
		}

		mismatched.shouldBeEmpty()
	}

	test("the default severity levels match the DSS default policy") {
		val dss = defaultAlgoExpirationDate()

		AlgorithmConstraintsConfig.DEFAULT.expirationLevel?.toDss() shouldBe dss.level
		AlgorithmConstraintsConfig.DEFAULT.expirationLevelAfterUpdate?.toDss() shouldBe dss.levelAfterUpdate
	}
})

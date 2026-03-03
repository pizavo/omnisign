package cz.pizavo.omnisign.domain.model.config.enums

import kotlinx.serialization.Serializable

/**
 * Severity level used when a cryptographic algorithm constraint is violated during validation.
 *
 * Maps 1:1 to [eu.europa.esig.dss.enumerations.Level] and is intentionally kept as a separate
 * domain type so the common module remains free of JVM-only DSS classes.
 */
@Serializable
enum class AlgorithmConstraintLevel {
	
	/** Validation fails — the signature is considered invalid. */
	FAIL,
	
	/** A warning is emitted — the signature is still considered valid but the issue is noted. */
	WARN,
	
	/** An informational message is emitted — no impact on the overall result. */
	INFORM,
	
	/** The constraint is completely skipped — no message is produced. */
	IGNORE;
	
	/**
	 * Human-readable description of the level.
	 */
	val description: String
		get() = when (this) {
			FAIL -> "Validation fails — signature is considered invalid"
			WARN -> "Warning only — signature is still considered valid but the issue is noted"
			INFORM -> "Informational — no impact on the overall validation result"
			IGNORE -> "Constraint is skipped — no message is produced"
		}
}


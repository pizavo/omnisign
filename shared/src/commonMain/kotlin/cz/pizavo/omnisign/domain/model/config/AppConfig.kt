package cz.pizavo.omnisign.domain.model.config

import kotlinx.serialization.Serializable

/**
 * Application configuration with support for profiles.
 */
@Serializable
data class AppConfig(
	/**
	 * Global settings applied to all operations.
	 */
	val global: GlobalConfig = GlobalConfig(),
	
	/**
	 * Named profiles for different use cases.
	 * Each profile can override global settings.
	 */
	val profiles: Map<String, ProfileConfig> = emptyMap(),
	
	/**
	 * The currently active profile name.
	 */
	val activeProfile: String? = null,
	
	/**
	 * In-progress trusted list builder drafts, keyed by draft name.
	 * Drafts are compiled into XML files via `config tl build compile`.
	 */
	val tlDrafts: Map<String, CustomTrustedListDraft> = emptyMap(),
	
	/**
	 * Named renewal jobs that are executed by the `omnisign renew` command.
	 * Keyed by [RenewalJob.name].
	 */
	val renewalJobs: Map<String, RenewalJob> = emptyMap(),
)


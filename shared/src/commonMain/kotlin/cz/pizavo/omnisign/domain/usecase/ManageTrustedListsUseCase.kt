package cz.pizavo.omnisign.domain.usecase

import arrow.core.left
import arrow.core.right
import cz.pizavo.omnisign.domain.model.config.*
import cz.pizavo.omnisign.domain.model.error.ConfigurationError
import cz.pizavo.omnisign.domain.model.result.OperationResult
import cz.pizavo.omnisign.domain.repository.ConfigRepository

/**
 * Use case for managing custom trusted list sources and in-progress TL builder drafts.
 *
 * Trusted list sources are scoped either to a named profile or to the global
 * [ValidationConfig], controlled by the optional [profileName] parameter on each
 * source-management method.  Builder drafts are stored at the top-level
 * [cz.pizavo.omnisign.domain.model.config.AppConfig.tlDrafts] map and are always global.
 */
class ManageTrustedListsUseCase(
	private val configRepository: ConfigRepository
) {
	/**
	 * Register a custom trusted list source.
	 *
	 * When [profileName] is given the entry is stored inside that profile's
	 * [ValidationConfig]; otherwise it is stored in the global [ValidationConfig].
	 * Replaces any existing entry with the same [CustomTrustedListConfig.name].
	 *
	 * @param tl The trusted list configuration to register.
	 * @param profileName Target profile name, or null for global scope.
	 */
	suspend fun addTrustedList(tl: CustomTrustedListConfig, profileName: String? = null): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			val existingValidation = profile.validation ?: ValidationConfig()
			val updatedValidation = existingValidation.copy(
				customTrustedLists = existingValidation.customTrustedLists.filter { it.name != tl.name } + tl
			)
			val newConfig = current.copy(
				profiles = current.profiles + (profileName to profile.copy(validation = updatedValidation))
			)
			
			return configRepository.saveConfig(newConfig)
		}
		
		val existing = current.global.validation.customTrustedLists
		val newConfig = current.copy(
			global = current.global.copy(
				validation = current.global.validation.copy(
					customTrustedLists = existing.filter { it.name != tl.name } + tl
				)
			)
		)
		
		return configRepository.saveConfig(newConfig)
	}
	
	/**
	 * Remove a registered trusted list source by name.
	 *
	 * When [profileName] is given the entry is removed from that profile's
	 * [ValidationConfig]; otherwise it is removed from the global [ValidationConfig].
	 *
	 * @param name Name of the trusted list to remove.
	 * @param profileName Target profile name, or null for global scope.
	 * @return Unit on success or an error if the name or profile is not found.
	 */
	suspend fun removeTrustedList(name: String, profileName: String? = null): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			
			val existingValidation = profile.validation ?: ValidationConfig()
			
			if (existingValidation.customTrustedLists.none { it.name == name }) {
				return ConfigurationError.InvalidConfiguration(
					message = "No trusted list named '$name' found in profile '$profileName'"
				).left()
			}
			
			val updatedValidation = existingValidation.copy(
				customTrustedLists = existingValidation.customTrustedLists.filter { it.name != name }
			)
			val newConfig = current.copy(
				profiles = current.profiles + (profileName to profile.copy(validation = updatedValidation))
			)
			
			return configRepository.saveConfig(newConfig)
		}
		
		val existing = current.global.validation.customTrustedLists
		
		if (existing.none { it.name == name }) {
			return ConfigurationError.InvalidConfiguration(
				message = "No trusted list named '$name' found"
			).left()
		}
		
		val newConfig = current.copy(
			global = current.global.copy(
				validation = current.global.validation.copy(
					customTrustedLists = existing.filter { it.name != name }
				)
			)
		)
		
		return configRepository.saveConfig(newConfig)
	}
	
	/**
	 * Return all registered trusted list sources for the given scope.
	 *
	 * When [profileName] is given, returns only the TLs stored in that profile's
	 * [ValidationConfig]; otherwise returns the global list.
	 *
	 * @param profileName Target profile name, or null for global scope.
	 */
	suspend fun listTrustedLists(profileName: String? = null): OperationResult<List<CustomTrustedListConfig>> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			
			return (profile.validation?.customTrustedLists ?: emptyList()).right()
		}
		
		return current.global.validation.customTrustedLists.right()
	}
	
	/**
	 * Create or replace a TL builder draft.
	 *
	 * @param draft The draft to store.
	 */
	suspend fun upsertDraft(draft: CustomTrustedListDraft): OperationResult<Unit> =
		configRepository.run {
			getCurrentConfig().let { saveConfig(it.copy(tlDrafts = it.tlDrafts + (draft.name to draft))) }
		}
	
	/**
	 * Retrieve a TL builder draft by name.
	 *
	 * @param name Draft name.
	 * @return The [CustomTrustedListDraft] or an error if not found.
	 */
	suspend fun getDraft(name: String): OperationResult<CustomTrustedListDraft> =
		configRepository.getCurrentConfig().tlDrafts[name]?.right()
			?: ConfigurationError.InvalidConfiguration(
				message = "No TL draft named '$name' found. Create one first with: config tl build create $name"
			).left()
	
	/**
	 * List all stored TL builder drafts.
	 */
	suspend fun listDrafts(): OperationResult<Map<String, CustomTrustedListDraft>> =
		configRepository.getCurrentConfig().tlDrafts.right()
	
	/**
	 * Delete a TL builder draft by name.
	 *
	 * @param name Draft name.
	 */
	suspend fun deleteDraft(name: String): OperationResult<Unit> =
		configRepository.getCurrentConfig().run {
			if (!tlDrafts.containsKey(name)) {
				return ConfigurationError.InvalidConfiguration(
					message = "No TL draft named '$name' found"
				).left()
			}
			
			configRepository.saveConfig(copy(tlDrafts = tlDrafts - name))
		}
	
	/**
	 * Add or replace a [TrustServiceProviderDraft] inside an existing draft.
	 * If a TSP with the same [TrustServiceProviderDraft.name] already exists it is replaced.
	 *
	 * @param draftName Name of the parent draft.
	 * @param tsp The TSP to add or replace.
	 */
	suspend fun upsertTsp(draftName: String, tsp: TrustServiceProviderDraft): OperationResult<Unit> =
		getDraft(draftName).fold(
			ifLeft = { it.left() },
			ifRight = { draft ->
				draft.trustServiceProviders.filter { it.name != tsp.name }.let {
					upsertDraft(draft.copy(trustServiceProviders = it + tsp))
				}
			}
		)
	
	/**
	 * Remove a TSP (and all its services) from a draft.
	 *
	 * @param draftName Name of the parent draft.
	 * @param tspName Name of the TSP to remove.
	 */
	suspend fun removeTsp(draftName: String, tspName: String): OperationResult<Unit> =
		getDraft(draftName).fold(
			ifLeft = { it.left() },
			ifRight = { draft ->
				if (draft.trustServiceProviders.none { it.name == tspName }) {
					return ConfigurationError.InvalidConfiguration(
						message = "No TSP named '$tspName' in draft '$draftName'"
					).left()
				}
				
				upsertDraft(
					draft.copy(
						trustServiceProviders = draft.trustServiceProviders.filter { it.name != tspName }
					))
			}
		)
	
	/**
	 * Add or replace a [TrustServiceDraft] under a TSP inside a draft.
	 * If a service with the same [TrustServiceDraft.name] already exists it is replaced.
	 *
	 * @param draftName Name of the parent draft.
	 * @param tspName Name of the parent TSP.
	 * @param service The service to add or replace.
	 */
	suspend fun upsertService(
		draftName: String,
		tspName: String,
		service: TrustServiceDraft
	): OperationResult<Unit> =
		getDraft(draftName).fold(
			ifLeft = { it.left() },
			ifRight = { draft ->
				val tsp = draft.trustServiceProviders.find { it.name == tspName }
					?: return ConfigurationError.InvalidConfiguration(
						message = "No TSP named '$tspName' in draft '$draftName'"
					).left()
				
				tsp
					.run { services.filter { it.name != service.name } + service }
					.let {
						draft.trustServiceProviders.map { t ->
							if (t.name == tspName) t.copy(services = it) else t
						}
					}.let { upsertDraft(draft.copy(trustServiceProviders = it)) }
			}
		)
	
	/**
	 * Remove a service from a TSP inside a draft.
	 *
	 * @param draftName Name of the parent draft.
	 * @param tspName Name of the parent TSP.
	 * @param serviceName Name of the service to remove.
	 */
	suspend fun removeService(
		draftName: String,
		tspName: String,
		serviceName: String
	): OperationResult<Unit> =
		getDraft(draftName).fold(
			ifLeft = { it.left() },
			ifRight = { draft -> removeServiceFromDraft(draft, tspName, serviceName) }
		)
	
	private suspend fun removeServiceFromDraft(
		draft: CustomTrustedListDraft,
		tspName: String,
		serviceName: String
	): OperationResult<Unit> {
		val tsp = draft.trustServiceProviders.find { it.name == tspName }
			?: return ConfigurationError.InvalidConfiguration(
				message = "No TSP named '$tspName' in draft '${draft.name}'"
			).left()
		
		if (tsp.services.none { it.name == serviceName }) {
			return ConfigurationError.InvalidConfiguration(
				message = "No service named '$serviceName' in TSP '$tspName'"
			).left()
		}
		
		return draft.trustServiceProviders.map { t ->
			if (t.name == tspName) t.copy(services = t.services.filter { it.name != serviceName }) else t
		}.let { upsertDraft(draft.copy(trustServiceProviders = it)) }
	}
	
	/**
	 * Register a directly trusted certificate.
	 *
	 * The certificate's DER bytes are expected to be already Base64-encoded in [cert].
	 * When [profileName] is given the entry is stored in that profile; otherwise in
	 * the global config. Replaces any existing entry with the same name.
	 *
	 * @param cert The trusted certificate configuration to register.
	 * @param profileName Target profile name, or null for global scope.
	 */
	suspend fun addTrustedCertificate(
		cert: TrustedCertificateConfig,
		profileName: String? = null
	): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			val existingValidation = profile.validation ?: ValidationConfig()
			val updatedValidation = existingValidation.copy(
				trustedCertificates = existingValidation.trustedCertificates.filter { it.name != cert.name } + cert
			)
			val newConfig = current.copy(
				profiles = current.profiles + (profileName to profile.copy(validation = updatedValidation))
			)
			return configRepository.saveConfig(newConfig)
		}
		
		val existing = current.global.validation.trustedCertificates
		val newConfig = current.copy(
			global = current.global.copy(
				validation = current.global.validation.copy(
					trustedCertificates = existing.filter { it.name != cert.name } + cert
				)
			)
		)
		return configRepository.saveConfig(newConfig)
	}
	
	/**
	 * Remove a directly trusted certificate by name.
	 *
	 * When [profileName] is given the entry is removed from that profile's
	 * [ValidationConfig]; otherwise from the global [ValidationConfig].
	 *
	 * @param name Name of the trusted certificate to remove.
	 * @param profileName Target profile name, or null for global scope.
	 */
	suspend fun removeTrustedCertificate(
		name: String,
		profileName: String? = null
	): OperationResult<Unit> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			val existingValidation = profile.validation ?: ValidationConfig()
			if (existingValidation.trustedCertificates.none { it.name == name }) {
				return ConfigurationError.InvalidConfiguration(
					message = "No trusted certificate named '$name' found in profile '$profileName'"
				).left()
			}
			val updatedValidation = existingValidation.copy(
				trustedCertificates = existingValidation.trustedCertificates.filter { it.name != name }
			)
			val newConfig = current.copy(
				profiles = current.profiles + (profileName to profile.copy(validation = updatedValidation))
			)
			return configRepository.saveConfig(newConfig)
		}
		
		val existing = current.global.validation.trustedCertificates
		if (existing.none { it.name == name }) {
			return ConfigurationError.InvalidConfiguration(
				message = "No trusted certificate named '$name' found"
			).left()
		}
		val newConfig = current.copy(
			global = current.global.copy(
				validation = current.global.validation.copy(
					trustedCertificates = existing.filter { it.name != name }
				)
			)
		)
		return configRepository.saveConfig(newConfig)
	}
	
	/**
	 * Return all directly trusted certificates for the given scope.
	 *
	 * When [profileName] is given, returns only the certificates stored in that profile's
	 * [ValidationConfig]; otherwise returns the global list.
	 *
	 * @param profileName Target profile name, or null for global scope.
	 */
	suspend fun listTrustedCertificates(
		profileName: String? = null
	): OperationResult<List<TrustedCertificateConfig>> {
		val current = configRepository.getCurrentConfig()
		
		if (profileName != null) {
			val profile = current.profiles[profileName]
				?: return ConfigurationError.InvalidConfiguration(
					message = "No profile named '$profileName' found"
				).left()
			return (profile.validation?.trustedCertificates ?: emptyList()).right()
		}
		
		return current.global.validation.trustedCertificates.right()
	}
}

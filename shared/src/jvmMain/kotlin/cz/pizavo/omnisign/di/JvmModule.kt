package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.data.serializer.XmlConfigSerializer
import cz.pizavo.omnisign.data.serializer.YamlConfigSerializer
import cz.pizavo.omnisign.data.service.*
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * JVM-specific DI module for repository implementations.
 *
 * Note: PasswordCallback must be provided by the UI layer (Compose Desktop/CLI).
 * Register it in your application's Koin configuration:
 * ```
 * single<PasswordCallback> { ComposePasswordCallback() }
 * ```
 */
val jvmRepositoryModule = module {
	singleOf(::Pkcs11Discoverer)
	singleOf(::DssTokenService) bind TokenService::class
	singleOf(::KeyringCredentialStore) bind CredentialStore::class
	
	single<ConfigRepository> { FileConfigRepository() }
	singleOf(::DssServiceFactory)
	singleOf(::DssValidationRepository) bind ValidationRepository::class
	singleOf(::DssSigningRepository) bind SigningRepository::class
	singleOf(::DssArchivingRepository) bind ArchivingRepository::class
	singleOf(::TrustedListCompiler)
	
	single {
		ConfigSerializerRegistry(
			listOf(JsonConfigSerializer(), XmlConfigSerializer(), YamlConfigSerializer())
		)
	}
	single { ExportImportConfigUseCase(get(), get()) }
	
	single<OsSchedulerService> {
		val os = System.getProperty("os.name", "").lowercase()
		if (os.contains("win")) WindowsTaskSchedulerService() else CrontabSchedulerService()
	}
	
	single<OsNotificationService> {
		val os = System.getProperty("os.name", "").lowercase()
		when {
			os.contains("win") -> WindowsNotificationService()
			os.contains("mac") -> MacOsNotificationService()
			else -> LinuxNotificationService()
		}
	}
}

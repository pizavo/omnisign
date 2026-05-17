package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.data.serializer.XmlConfigSerializer
import cz.pizavo.omnisign.data.serializer.YamlConfigSerializer
import cz.pizavo.omnisign.data.service.*
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import cz.pizavo.omnisign.domain.usecase.RenewBatchUseCase
import kotlinx.coroutines.flow.MutableStateFlow
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
	single { Pkcs11CrashBlacklist() }
	single { PcscContextRecovery() }
	single { PcscMonitorService(recovery = get()) }
	single {
		Pkcs11Discoverer(
			crashBlacklist = get(),
			pcscRecovery = get(),
		)
	}
	single {
		Pkcs11WarmupService(
			discoverer = get(),
			crashBlacklist = get(),
			warmupSignal = getOrNull<MutableStateFlow<Boolean>>() ?: MutableStateFlow(true),
		)
	}
	single { Pkcs11NoLoginCertProbe() }
	single {
		Pkcs11DiagnosticsService(
			pkcs11Discoverer = get(),
			configRepository = get(),
			pcscMonitor = get(),
			noLoginProbe = get(),
		)
	}
	single {
		Pkcs11CacheInvalidator(
			monitor = get(),
			discoverer = get(),
			configRepository = get(),
			appDataPkcs11Dir = pkcs11DropDir(),
		)
	}
	single {
		DssTokenService(
			passwordCallback = get(),
			pkcs11Discoverer = get(),
			pkcs11CacheInvalidator = get(),
			pcscMonitorService = get(),
			configRepository = get(),
		)
	} bind TokenService::class
	singleOf(::KeyringCredentialStore) bind CredentialStore::class
	
	single<ConfigRepository> { FileConfigRepository() }
	singleOf(::DssServiceFactory)
	singleOf(::DssWarningSanitizer)
	singleOf(::TspErrorDetector)
	singleOf(::DssValidationRepository) bind ValidationRepository::class
	singleOf(::DssSigningRepository) bind SigningRepository::class
	singleOf(::DssArchivingRepository) bind ArchivingRepository::class
	singleOf(::TrustedListCompiler)
	singleOf(::DssTrustedListCompilerAdapter) bind TrustedListCompilerPort::class
	singleOf(::TrustedCertificateReader)
	singleOf(::SelfExecutableResolver)
	
	single {
		ConfigSerializerRegistry(
			listOf(JsonConfigSerializer(), XmlConfigSerializer(), YamlConfigSerializer())
		)
	}
	single { ExportImportConfigUseCase(get(), get()) }
	singleOf(::RenewBatchUseCase)
	
	single<OsSchedulerService> {
		val os = System.getProperty("os.name", "").lowercase()
		if (os.contains("win")) WindowsTaskSchedulerService() else CrontabSchedulerService()
	}
	
	single<SchedulerPort> { SchedulerPortAdapter(get()) }
	
	single<OsNotificationService> {
		val os = System.getProperty("os.name", "").lowercase()
		when {
			os.contains("win") -> WindowsNotificationService()
			os.contains("mac") -> MacOsNotificationService()
			else -> LinuxNotificationService()
		}
	}
}

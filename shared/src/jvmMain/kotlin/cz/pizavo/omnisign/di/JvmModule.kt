package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.*
import cz.pizavo.omnisign.data.trust.FileTrustStore
import cz.pizavo.omnisign.data.serializer.JsonConfigSerializer
import cz.pizavo.omnisign.data.serializer.XmlConfigSerializer
import cz.pizavo.omnisign.data.serializer.YamlConfigSerializer
import cz.pizavo.omnisign.data.service.*
import cz.pizavo.omnisign.domain.port.ConfigSerializerRegistry
import cz.pizavo.omnisign.domain.port.ConfigArchivePort
import cz.pizavo.omnisign.domain.port.RenewalCheckCache
import cz.pizavo.omnisign.domain.port.RenewalLock
import cz.pizavo.omnisign.domain.port.RenewalRunRecordStore
import cz.pizavo.omnisign.domain.port.SchedulerPort
import cz.pizavo.omnisign.domain.port.TrustedListCompilerPort
import cz.pizavo.omnisign.domain.port.TrustedListRefreshPort
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.TrustStore
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
import cz.pizavo.omnisign.domain.usecase.ConfigArchiveUseCase
import cz.pizavo.omnisign.domain.usecase.ExportImportConfigUseCase
import cz.pizavo.omnisign.domain.usecase.MigrateTrustedCertificatesUseCase
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
	single { Pkcs11DiscoverySignal() }
	single { Pkcs11ProbeTimeout() }
	single<Pkcs11Prober> { Pkcs11SubprocessProber(probeTimeout = get()) }
	single { Pkcs11ProbeCache(crashBlacklist = get(), prober = get()) }
	single { Pkcs11PcscCalaisResolver(pcscRecovery = get()) }
	single { Pkcs11LibP11KitModuleResolver(prober = get(), probeTimeout = get()) }
	single { Pkcs11CandidateCollector(pcscCalaisResolver = get(), libP11KitModuleResolver = get()) }
	single { Pkcs11TokenInfoDeduplicator() }
	single { PcscMonitorService(recovery = get()) }
	single {
		Pkcs11Discoverer(
			probeCache = get(),
			candidateCollector = get(),
			deduplicator = get(),
			discoverySignal = get(),
		)
	}
	single {
		Pkcs11WarmupService(
			candidateCollector = get(),
			probeCache = get(),
			prober = get(),
			crashBlacklist = get(),
			warmupSignal = getOrNull<MutableStateFlow<Boolean>>() ?: MutableStateFlow(true),
			discoverySignal = get(),
			probeTimeout = get(),
		)
	}
	single { Pkcs11NoLoginCertProbe() }
	single {
		Pkcs11DiagnosticsService(
			deduplicator = get(),
			candidateCollector = get(),
			prober = get(),
			configRepository = get(),
			pcscMonitor = get(),
			noLoginProbe = get(),
		)
	}
	single {
		Pkcs11CacheInvalidator(
			monitor = get(),
			discoverer = get(),
			probeCache = get(),
			candidateCollector = get(),
			configRepository = get(),
			appDataPkcs11Dir = pkcs11DropDir(),
			probeTimeout = get(),
		)
	}
	single {
		DssTokenService(
			passwordCallback = get(),
			pkcs11Discoverer = get(),
			probeCache = get(),
			candidateCollector = get(),
			prober = get(),
			pkcs11CacheInvalidator = get(),
			pcscMonitorService = get(),
			configRepository = get(),
		)
	} bind TokenService::class
	singleOf(::KeyringCredentialStore) bind CredentialStore::class
	
	single<ConfigRepository> { FileConfigRepository() }
	single<TrustStore> { FileTrustStore() }
	singleOf(::TrustedListRefreshSignal)
	single { TrustedSourceRegistry(get()) }
	single { DssServiceFactory(get(), get()) }
	singleOf(::TrustedListRefreshScheduler)
	single { DssTrustedListRefreshAdapter(get(), get()) } bind TrustedListRefreshPort::class
	singleOf(::DssWarningSanitizer)
	singleOf(::TspErrorDetector)
	singleOf(::DssValidationRepository) bind ValidationRepository::class
	singleOf(::DssSigningRepository) bind SigningRepository::class
	singleOf(::DssArchivingRepository) bind ArchivingRepository::class
	singleOf(::TrustedListCompiler)
	singleOf(::DssTrustedListCompilerAdapter) bind TrustedListCompilerPort::class
	singleOf(::SelfExecutableResolver)
	
	single {
		ConfigSerializerRegistry(
			listOf(JsonConfigSerializer(), XmlConfigSerializer(), YamlConfigSerializer())
		)
	}
	single { ExportImportConfigUseCase(get(), get()) }
	single { ConfigArchiveUseCase(get(), get(), get()) } bind ConfigArchivePort::class
	single<RenewalLock> {
		FileRenewalLock(FileConfigRepository.getDefaultConfigPath().resolveSibling("renewal.lock"))
	}
	single<RenewalRunRecordStore> {
		FileRenewalRunRecordStore(FileConfigRepository.getDefaultConfigPath().resolveSibling("last-renewal.json"))
	}
	single<RenewalCheckCache> {
		FileRenewalCheckCache(FileConfigRepository.getDefaultConfigPath().resolveSibling("renewal-check-cache.json"))
	}
	singleOf(::RenewBatchUseCase)
	single { MigrateTrustedCertificatesUseCase(get(), get()) }
	
	single<OsSchedulerService> {
		val os = System.getProperty("os.name", "").lowercase()
		when {
			os.contains("win") -> WindowsTaskSchedulerService()
			os.contains("mac") -> LaunchdSchedulerService()
			else -> SystemdSchedulerService()
		}
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

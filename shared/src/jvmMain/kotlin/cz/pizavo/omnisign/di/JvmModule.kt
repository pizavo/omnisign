package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.DssArchivingRepository
import cz.pizavo.omnisign.data.repository.DssSigningRepository
import cz.pizavo.omnisign.data.repository.DssValidationRepository
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.service.DssTokenService
import cz.pizavo.omnisign.data.service.KeyringCredentialStore
import cz.pizavo.omnisign.data.service.TrustedListCompiler
import cz.pizavo.omnisign.domain.repository.ArchivingRepository
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
import cz.pizavo.omnisign.domain.service.CredentialStore
import cz.pizavo.omnisign.domain.service.TokenService
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
    singleOf(::DssTokenService) bind TokenService::class
    singleOf(::KeyringCredentialStore) bind CredentialStore::class

    single<ConfigRepository> { FileConfigRepository() }
    singleOf(::DssValidationRepository) bind ValidationRepository::class
    singleOf(::DssSigningRepository) bind SigningRepository::class
    singleOf(::DssArchivingRepository) bind ArchivingRepository::class
    singleOf(::TrustedListCompiler)
}

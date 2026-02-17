package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.data.repository.DssSigningRepository
import cz.pizavo.omnisign.data.repository.DssValidationRepository
import cz.pizavo.omnisign.data.repository.FileConfigRepository
import cz.pizavo.omnisign.data.service.DssTokenService
import cz.pizavo.omnisign.domain.repository.ConfigRepository
import cz.pizavo.omnisign.domain.repository.SigningRepository
import cz.pizavo.omnisign.domain.repository.ValidationRepository
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
    // Services - PasswordCallback is injected, not created here
    singleOf(::DssTokenService) bind TokenService::class
    
    // Repositories
    singleOf(::DssValidationRepository) bind ValidationRepository::class
    singleOf(::DssSigningRepository) bind SigningRepository::class
    singleOf(::FileConfigRepository) bind ConfigRepository::class
    
    // TODO: Add TimestampRepository, ArchivingRepository implementations
}




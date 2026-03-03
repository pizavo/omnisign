package cz.pizavo.omnisign.di

import cz.pizavo.omnisign.domain.usecase.*
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Main application DI module.
 * Platform-specific implementations are registered in separate modules.
 */
val appModule = module {
	singleOf(::ValidateDocumentUseCase)
	singleOf(::SignDocumentUseCase)
	singleOf(::ListCertificatesUseCase)
	singleOf(::ExtendDocumentUseCase)
	singleOf(::CheckArchivalRenewalUseCase)
	singleOf(::GetConfigUseCase)
	singleOf(::SetGlobalConfigUseCase)
	singleOf(::ManageProfileUseCase)
	singleOf(::ManageTrustedListsUseCase)
	singleOf(::ManageRenewalJobsUseCase)
}

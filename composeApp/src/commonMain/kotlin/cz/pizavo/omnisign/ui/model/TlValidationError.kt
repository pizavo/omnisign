package cz.pizavo.omnisign.ui.model

import androidx.compose.runtime.Composable
import omnisign.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * A single Trusted-List-builder form validation failure.
 *
 * The view model emits these as locale-agnostic data; the UI turns them into a localized
 * message via [resolve], so no user-facing wording lives in the view model.
 */
sealed interface TlValidationError {
	/** The trusted-list name is empty. */
	data object NameRequired : TlValidationError

	/** The territory code is empty. */
	data object TerritoryRequired : TlValidationError

	/** The scheme operator name is empty. */
	data object SchemeOperatorRequired : TlValidationError

	/** The scheme name is empty. */
	data object SchemeNameRequired : TlValidationError

	/** The scheme information URI is empty. */
	data object SchemeInformationUriRequired : TlValidationError

	/** The scheme operator's address is missing a part ETSI TS 119612 requires. */
	data object SchemeOperatorAddressRequired : TlValidationError

	/** No Trust Service Provider has been added. */
	data object TspRequired : TlValidationError

	/** TSP [tspName] has no information URI. */
	data class TspInfoUrlRequired(val tspName: String) : TlValidationError

	/** TSP [tspName] is missing a part of its address that ETSI TS 119612 requires. */
	data class TspAddressRequired(val tspName: String) : TlValidationError

	/** The TSP at 1-based position [number] has an empty name. */
	data class TspNameRequired(val number: Int) : TlValidationError

	/** TSP [tspName] has no services. */
	data class TspServiceRequired(val tspName: String) : TlValidationError

	/** The service at 1-based position [number] under TSP [tspName] has an empty name. */
	data class ServiceNameRequired(val tspName: String, val number: Int) : TlValidationError

	/** Service [serviceName] under TSP [tspName] has no type identifier. */
	data class ServiceTypeRequired(val tspName: String, val serviceName: String) : TlValidationError

	/** Service [serviceName] under TSP [tspName] has no status. */
	data class ServiceStatusRequired(val tspName: String, val serviceName: String) : TlValidationError

	/** Service [serviceName] under TSP [tspName] has no certificate path. */
	data class ServiceCertRequired(val tspName: String, val serviceName: String) : TlValidationError
}

/**
 * Resolve this validation error to its localized, human-readable message.
 */
@Composable
fun TlValidationError.resolve(): String = when (this) {
	TlValidationError.NameRequired -> stringResource(Res.string.tlbuilder_validation_name)
	TlValidationError.TerritoryRequired -> stringResource(Res.string.tlbuilder_validation_territory)
	TlValidationError.SchemeOperatorRequired -> stringResource(Res.string.tlbuilder_validation_scheme_operator)
	TlValidationError.SchemeNameRequired -> stringResource(Res.string.tlbuilder_validation_scheme_name)
	TlValidationError.SchemeInformationUriRequired ->
		stringResource(Res.string.tlbuilder_validation_scheme_info_uri)
	TlValidationError.SchemeOperatorAddressRequired ->
		stringResource(Res.string.tlbuilder_validation_operator_address)
	is TlValidationError.TspInfoUrlRequired ->
		stringResource(Res.string.tlbuilder_validation_tsp_info_url, tspName)
	is TlValidationError.TspAddressRequired ->
		stringResource(Res.string.tlbuilder_validation_tsp_address, tspName)
	TlValidationError.TspRequired -> stringResource(Res.string.tlbuilder_validation_tsp_required)
	is TlValidationError.TspNameRequired -> stringResource(Res.string.tlbuilder_validation_tsp_name, number)
	is TlValidationError.TspServiceRequired -> stringResource(Res.string.tlbuilder_validation_tsp_service, tspName)
	is TlValidationError.ServiceNameRequired -> stringResource(Res.string.tlbuilder_validation_service_name, tspName, number)
	is TlValidationError.ServiceTypeRequired -> stringResource(Res.string.tlbuilder_validation_service_type, tspName, serviceName)
	is TlValidationError.ServiceStatusRequired -> stringResource(Res.string.tlbuilder_validation_service_status, tspName, serviceName)
	is TlValidationError.ServiceCertRequired -> stringResource(Res.string.tlbuilder_validation_service_cert, tspName, serviceName)
}

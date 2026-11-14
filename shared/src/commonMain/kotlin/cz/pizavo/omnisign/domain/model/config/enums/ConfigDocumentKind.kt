package cz.pizavo.omnisign.domain.model.config.enums

/**
 * The kinds of configuration document an export writes and an import reads.
 *
 * Every kind is written in the same formats and packed into the same archive layout, so an import
 * tells them apart by the document's content and refuses one of another kind: read as the kind it
 * expected, the document's own settings would be dropped and the defaults saved in their place.
 */
enum class ConfigDocumentKind {
	/** A full configuration: the global settings, every profile, the renewal jobs and the rest. */
	APP,

	/** The global settings alone. */
	GLOBAL,

	/** A single profile. */
	PROFILE,
}

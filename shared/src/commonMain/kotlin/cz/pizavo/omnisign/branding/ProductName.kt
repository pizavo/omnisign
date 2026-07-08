package cz.pizavo.omnisign.branding

/**
 * The fixed OmniSign product name.
 *
 * It is the single value every deployment's attribution is built from: the ` · OmniSign` final
 * segment of the app title, the `poweredBy` field of the `GET /api/v1/health` and
 * `GET /api/v1/capabilities` responses, and the `powered by OmniSign` mark in the web UI. A provider
 * may supply an organization label *around* it but can never remove or replace it — which is how the
 * OmniSign attribution is preserved whether the app is used through the official web client or purely
 * as an API.
 */
const val PRODUCT_NAME: String = "OmniSign"

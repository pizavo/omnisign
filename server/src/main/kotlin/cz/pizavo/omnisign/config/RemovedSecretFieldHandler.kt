package cz.pizavo.omnisign.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler

/**
 * Jackson [DeserializationProblemHandler] that translates a YAML attempt to set one of
 * the secret fields removed under [ServerSecrets] into a targeted error naming the
 * environment variable the operator should use.
 *
 * Without this handler, the secret-removal would still be enforced (Jackson's
 * `FAIL_ON_UNKNOWN_PROPERTIES = true` rejects unknown fields per [ServerConfigLoader]),
 * but the error would read `Unrecognized property "keystorePassword"` — accurate but
 * unhelpful. This handler intercepts the specific removed names and substitutes an
 * operator-actionable message pointing at the env var.
 *
 * Returns `true` only when the offending property is consumed (skipping the value) AFTER
 * the targeted exception is thrown; in practice the throw aborts deserialization before
 * the return matters.
 *
 * The set of intercepted names overlaps deliberately with `OMNISIGN_OIDC_<NAME>_CLIENT_SECRET`
 * derivation: `clientSecret` appears in every OIDC provider entry, so a YAML attempt to
 * set it triggers this handler regardless of which provider it belongs to. The targeted
 * error names the env-var convention rather than the specific provider's env var,
 * because at this point in Jackson's pipeline we cannot reliably look up the surrounding
 * provider context.
 */
class RemovedSecretFieldHandler : DeserializationProblemHandler() {

    override fun handleUnknownProperty(
        ctxt: DeserializationContext,
        p: JsonParser,
        deserializer: com.fasterxml.jackson.databind.JsonDeserializer<*>?,
        beanOrClass: Any?,
        propertyName: String,
    ): Boolean {
        when (propertyName) {
            "keystorePassword" -> throw IllegalArgumentException(
                "tls.keystorePassword cannot be set in YAML. Set the " +
                    "${ServerSecrets.TLS_KEYSTORE_PASSWORD_ENV} environment variable instead.",
            )
            "privateKeyPassword" -> throw IllegalArgumentException(
                "tls.privateKeyPassword cannot be set in YAML. Set the " +
                    "${ServerSecrets.TLS_PRIVATE_KEY_PASSWORD_ENV} environment variable instead " +
                    "(or omit it to fall back to the keystore password).",
            )
            "secret" -> {
                if (parentLooksLike(beanOrClass, "SessionConfig")) {
                    throw IllegalArgumentException(
                        "auth.session.secret cannot be set in YAML. Set the " +
                            "${ServerSecrets.JWT_SECRET_ENV} environment variable instead.",
                    )
                }
            }
            "clientSecret" -> throw IllegalArgumentException(
                "OIDC provider clientSecret cannot be set in YAML. Set the per-provider " +
                    "OMNISIGN_OIDC_<NAME>_CLIENT_SECRET environment variable instead — " +
                    "the <NAME> portion is the provider's `name` field uppercased with " +
                    "non-alphanumeric characters replaced by `_` (e.g. name: \"google\" → " +
                    "OMNISIGN_OIDC_GOOGLE_CLIENT_SECRET).",
            )
        }
        return false
    }

    /**
     * Cheap class-name probe used to scope the generic `secret` field name to the
     * `SessionConfig` context only — other YAML branches might legitimately use the
     * same key name in a future config addition, and we do not want to over-trigger.
     */
    private fun parentLooksLike(beanOrClass: Any?, simpleName: String): Boolean {
        val cls = when (beanOrClass) {
            is Class<*> -> beanOrClass
            null -> null
            else -> beanOrClass::class.java
        }
        return cls?.simpleName == simpleName
    }
}

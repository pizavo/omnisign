package cz.pizavo.omnisign.domain.model.value

import kotlin.jvm.JvmInline
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Opaque wrapper around a sensitive value of type [T].
 *
 * The sole purpose of the wrapping is safety: [toString] always returns `***`
 * regardless of the underlying content, preventing accidental credential
 * exposure in logs, debug output, or data-class–generated string
 * representations on all platforms.
 *
 * Serialization is intentionally blocked via [SensitiveSerializer]: both
 * [SensitiveSerializer.serialize] and [SensitiveSerializer.deserialize] throw
 * [SerializationException], so a field of this type can never reach a
 * serialized form even if the containing class omits
 * [@Transient][kotlinx.serialization.Transient].
 *
 * Use the [sensitive] extension on [String] to construct instances concisely.
 *
 * @property value The underlying sensitive value.
 */
@JvmInline
@Serializable(with = SensitiveSerializer::class)
value class Sensitive<T>(val value: T) {
    /**
     * Returns a fixed mask instead of the actual value, preventing accidental
     * exposure in any context where [toString] is called — including logging
     * frameworks, string interpolation, and data-class–generated output.
     */
    override fun toString(): String = "***"
}

/**
 * Kotlinx-serialization serializer for [Sensitive] that unconditionally rejects
 * both serialization and deserialization by throwing [SerializationException].
 *
 * Acts as a last line of defense: if a [Sensitive] field is accidentally not
 * annotated with [@Transient][kotlinx.serialization.Transient], the
 * serialization framework will fail loudly rather than silently leaking the
 * credential to the output.
 *
 * @param valueSerializer Serializer for the underlying type [T], required by
 *   the kotlinx.serialization plugin for generic classes but otherwise unused.
 */
class SensitiveSerializer<T>(
    @Suppress("UNUSED_PARAMETER") valueSerializer: KSerializer<T>,
) : KSerializer<Sensitive<T>> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Sensitive", PrimitiveKind.STRING)

    /** Always throws [SerializationException]. */
    override fun serialize(encoder: Encoder, value: Sensitive<T>) =
        throw SerializationException(
            "Sensitive<T> must not be serialized. " +
            "Annotate the containing field with @kotlinx.serialization.Transient.",
        )

    /** Always throws [SerializationException]. */
    override fun deserialize(decoder: Decoder): Sensitive<T> =
        throw SerializationException(
            "Sensitive<T> must not be deserialized. " +
            "Annotate the containing field with @kotlinx.serialization.Transient.",
        )
}

/**
 * Wraps this string in a [Sensitive], preventing its value from
 * appearing in any [toString] output.
 */
fun String.sensitive(): Sensitive<String> = Sensitive(this)

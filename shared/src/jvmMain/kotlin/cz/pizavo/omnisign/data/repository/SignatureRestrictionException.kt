package cz.pizavo.omnisign.data.repository

import eu.europa.esig.dss.pades.exception.ProtectedDocumentException

/**
 * DSS refused a new signature because the document forbids one: a certification that permits no
 * changes (a DocMDP dictionary with `P` 1), or a lock an earlier signature set (a FieldMDP or a
 * signature field's Lock dictionary).
 *
 * [SignatureRestrictionChecker] raises it in place of the [ProtectedDocumentException] DSS raises for
 * these refusals, which is also the one DSS raises when the permissions of an encrypted document
 * refuse the signature. It is still a [ProtectedDocumentException], so code that expects DSS's type
 * keeps working, while [DocumentInputErrorDetector.isLocked] can tell it apart from encryption. It
 * has no cause, since DSS's exception, as a cause, would read as encryption again.
 *
 * @param message DSS's message, which names the dictionary that refused.
 */
class SignatureRestrictionException(message: String?) : ProtectedDocumentException(message)
